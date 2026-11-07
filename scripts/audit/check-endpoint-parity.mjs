/**
 * 前后端接口对账：找出前端调用了、后端却没有映射的路径（孤儿接口）。
 *
 * 背景：dashboardApi.js 里存在若干请求路径，后端根本没有对应 @RequestMapping，
 *      运行时表现为 404；更隐蔽的是前端对这些调用写了 .catch 兜底假数据，
 *      页面看起来"有数据"，实际从未打到后端，问题长期不被发现。
 *
 * 做法：
 *   - 后端：解析 @RestController 的类级 @RequestMapping + 方法级 @XxxMapping，
 *           拼接成完整路径，路径变量归一化为 {}。
 *   - 前端：解析 src/services 下 request(`...`) / request("...") 调用，
 *           模板插值 ${...} 同样归一化为 {}。
 *   - 差集即候选孤儿接口。
 *
 * 用法：node scripts/audit/check-endpoint-parity.mjs
 * 注意：结果需要人工复核——后端可能存在通配符映射、正则映射或网关转发。
 */
import fs from 'node:fs';
import path from 'node:path';

const BACKEND = 'backend/src/main/java';
const FRONTEND = 'frontend/src/services';

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

// ---------------------------------------------------------------- 后端

const METHOD_MAP = {
  GetMapping: 'GET', PostMapping: 'POST', PutMapping: 'PUT',
  DeleteMapping: 'DELETE', PatchMapping: 'PATCH', RequestMapping: 'ANY',
};

/**
 * 归一化为可比较的路径。
 *
 * 难点：模板字面量里同时存在两类插值，必须区别对待——
 *   - `${withQuery({ kind, region })}` 生成查询串，应整体丢弃
 *     （否则 /zones/summary 会变成 /zones/summary{} 而匹配不上）
 *   - `${encodeURIComponent(taskCode)}` 是路径变量，应变成 {}
 *     （否则 /inspection/task/{} 会退化成 /inspection/task 而匹配不上）
 * 因此不能用简单正则 `[^}]*`（会卡在 withQuery 内部的第一个 }），
 * 这里做一次花括号配平扫描。
 */
function normalize(p) {
  let out = '';
  let i = 0;
  while (i < p.length) {
    if (p[i] === '$' && p[i + 1] === '{') {
      let depth = 1;
      let j = i + 2;
      while (j < p.length && depth > 0) {
        if (p[j] === '{') depth++;
        else if (p[j] === '}') depth--;
        j++;
      }
      const inner = p.slice(i + 2, j - 1).trim();
      // 先剔除可选链 ?. 与空值合并 ??，否则 `warning?.id ?? warning?.warningId`
      // 会被误判成三元查询串。剩下的 ? 才代表三元或查询串。
      const withoutOptional = inner.replace(/\?\./g, '').replace(/\?\?/g, '');
      // withQuery(...) 与 `cond ? "?a=b" : ""` 生成的是查询串，丢弃；
      // 其余（${id}、${encodeURIComponent(code)}）视为路径变量。
      out += (inner.startsWith('withQuery') || withoutOptional.includes('?')) ? '' : '{}';
      i = j;
      continue;
    }
    out += p[i];
    i++;
  }
  return out
    .replace(/\?[\s\S]*$/, '')       // 去掉查询串，只比较路径
    .replace(/\{[^}]*\}/g, '{}')     // 后端 /{id} 统一成 {}
    .replace(/\/+$/, '') || '/';
}

// 前端 request(path) 的 path 是相对 API_BASE 的，
// API_BASE 见 frontend/src/services/_architecture/core/runtime.js：
//   import.meta.env.VITE_API_BASE_URL || "http://localhost:8080/api"
// 因此与后端类级 @RequestMapping 对齐时需要补 /api 前缀。
const API_PREFIX = '/api';

function joinPath(base, sub) {
  if (!sub) return base || '/';
  const b = (base || '').replace(/\/+$/, '');
  const s = sub.startsWith('/') ? sub : '/' + sub;
  return b + s;
}

const backendPaths = new Map(); // "METHOD path" -> file

for (const file of walk(BACKEND).filter((f) => f.endsWith('.java'))) {
  const src = fs.readFileSync(file, 'utf8');
  if (!/@RestController|@Controller\b/.test(src)) continue;

  // 类级基路径
  const classMatch = src.match(/@RequestMapping\s*\(\s*(?:value\s*=\s*)?["']([^"']+)["']/);
  const base = classMatch ? classMatch[1] : '';

  // 括号可选：裸 @GetMapping（路径全在类级注解上）同样是一个有效映射
  // 允许全限定写法：@org.springframework.web.bind.annotation.PostMapping(...)
  const re = /@(?:[\w.]+\.)?(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\s*(\(([^)]*)\))?/g;
  let m;
  while ((m = re.exec(src))) {
    const ann = m[1];
    const args = m[3] || '';
    // 取第一个字符串字面量作为路径（可能是 value = "..." 或直接 "..."）
    const pathMatch = args.match(/["'](\/[^"']*)["']/);
    const sub = pathMatch ? pathMatch[1] : '';
    const methods = ann === 'RequestMapping'
      ? (args.match(/RequestMethod\.(\w+)/g) || ['ANY']).map((s) => s.replace('RequestMethod.', ''))
      : [METHOD_MAP[ann]];
    for (const method of methods) {
      backendPaths.set(`${method} ${normalize(joinPath(base, sub))}`, file);
    }
  }
}

// ---------------------------------------------------------------- 前端

const frontendPaths = new Map(); // "METHOD path" -> file

for (const file of walk(FRONTEND).filter((f) => /\.(js|vue)$/.test(f))) {
  // 去掉注释，避免 JSDoc 里的示例路径被当成真实调用
  const src = fs.readFileSync(file, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');
  // request(`/xxx/${id}`, { method: "PUT" })  /  request("/xxx")
  // R10 P2-5：aiApi.js 里的 15 处调用走的是 fetch / fetchApi，此前只解析 request(
  // 会把整个 AI 模块的端点漏掉，对账结果偏乐观。这里一并纳入。
  const re = /\b(?:request|fetchApi|fetch)\(\s*[`'"]([^`'"]+)[`'"]\s*(?:,\s*(\{[^}]*\}))?/g;
  let m;
  while ((m = re.exec(src))) {
    // 以插值开头的路径（如 `${AI_BASE}/feedback/stats`）是动态基址，静态阶段无法判定
    if (m[1].startsWith('${')) continue;
    // request() 收相对路径（内部再拼 API_BASE）；裸 fetch 可能已经写了 /api 前缀，
    // 无条件再拼一次会得到 /api/api/... 这种假孤儿（ws-ticket 就是这种情况）
    const p = normalize((m[1].startsWith('/api/') ? '' : API_PREFIX) + m[1]);
    if (!m[1].startsWith('/')) continue;            // 非相对路径跳过
    const opts = m[2] || '';
    const methodMatch = opts.match(/method\s*:\s*["']?(\w+)["']?/);
    const method = methodMatch ? methodMatch[1].toUpperCase() : 'GET';
    frontendPaths.set(`${method} ${p}`, file);
  }
}

// ---------------------------------------------------------------- 对账

// 已知非孤儿：aiServiceRequest 这类包装器把 request(`/ai/xxx${path}`) 当基址前缀，
// 静态部分本身不是完整端点，交由各 domain service 拼接子路径。
const IGNORE = new Set([
  'GET /api/ai/drift{}',
  'GET /api/ai/multimodal{}',
]);

const orphans = [];
for (const [key, file] of frontendPaths) {
  if (IGNORE.has(key)) continue;
  const [method, p] = key.split(' ');
  const candidates = [
    `${method} ${p}`,
    `ANY ${p}`,
    `${method} ${p}/`,
  ];
  if (candidates.some((c) => backendPaths.has(c))) continue;
  // 后端可能只声明了通配 GET（同路径任意方法）或相反
  if (['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].some((mth) => backendPaths.has(`${mth} ${p}`))) continue;
  orphans.push({ key, file });
}

console.log(`后端映射: ${backendPaths.size}`);
console.log(`前端调用: ${frontendPaths.size}`);
console.log(`疑似孤儿接口: ${orphans.length}`);
for (const o of orphans.sort((a, b) => a.key.localeCompare(b.key))) {
  console.log(`  ! ${o.key}   <- ${o.file}`);
}
