#!/usr/bin/env node
/**
 * 反向接口对账：找出「前端调了、后端没有」的请求。
 *
 * 与 scripts/audit/check-endpoint-parity.mjs 互补 —— 后者只查「后端有、前端没调」
 * 的孤儿接口，不查反向缺口。2026-09-21 复审发现的 P0（POST /api/warnings 后端缺失、
 * 前端向导页却已上线）正是这类缺口，故补上这条门禁。
 *
 * 用法（仓库根目录）：
 *   node scripts/audit/check-frontend-endpoints.mjs
 *
 * 退出码 0 = 无缺口；1 = 发现缺口（可接入 CI）。
 */

import { readFileSync, existsSync } from 'fs';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.cwd();
const BACKEND_SRC = path.join(ROOT, 'backend/src/main/java');
const FRONTEND_SRC = path.join(ROOT, 'frontend/src');

/**
 * 递归遍历目录，返回所有普通文件绝对路径。
 *
 * 用 Node 原生 readdirSync 而不是 `execSync('find ...')` —— 后者在 Windows
 * 上会调到 Windows 自带的 find.exe（字符串搜索工具），执行必失败；
 * 在 WSL / Ubuntu CI 上能跑也只是侥幸。统一用跨平台的纯 JS 实现。
 */
function walkFiles(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walkFiles(p));
    else out.push(p);
  }
  return out;
}

/** 把路径变量统一成占位符，便于前后端比对：{id} / {deviceCode} -> {x} */
function normalizePath(p) {
  return (p || '')
    .replace(/\{[^}]*\}/g, '{x}')
    .replace(/\$\{[^}]*\}/g, '{x}')
    .replace(/\/+/g, '/')
    .replace(/\/$/, '');
}

// ---------------------------------------------------------------- 后端映射

function collectBackendMappings() {
  // 跨平台遍历：以前用 execSync('find ...')，在 Windows 上调到的是
  // Windows 自带的 find.exe（字符串搜索工具），没有任何 .java 输出，
  // 后续自检必失败。现在改为 Node 原生 walkFiles。
  let files = walkFiles(BACKEND_SRC).filter(p => p.endsWith('.java'));

  const mappings = new Map(); // "METHOD PATH" -> file

  for (const abs of files) {
    const rel = path.relative(ROOT, abs);
    const lines = readFileSync(abs, 'utf8').split('\n');

    // 类级 basePath：找到 class 声明后向前回溯最近的类级 @RequestMapping
    let basePath = '';
    for (let i = 0; i < lines.length; i++) {
      if (!/\bclass\s+\w+/.test(lines[i])) continue;
      for (let j = i - 1; j >= Math.max(0, i - 8); j--) {
        const m = lines[j].match(/@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"/);
        if (m) { basePath = m[1]; break; }
        // 遇到另一个类/接口声明就停止回溯
        if (/\b(class|interface|enum)\s+\w+/.test(lines[j])) break;
      }
    }

    // 方法级映射
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];

      // @GetMapping("/x") / @GetMapping / @PostMapping(value = "/x")
      // 注意兼容全限定写法：@org.springframework.web.bind.annotation.PostMapping("/x")
      const shortcut = line.match(/@(?:[\w.]+\.)?(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(\s*(?:value\s*=\s*)?"([^"]*)")?/);
      if (shortcut) {
        const method = shortcut[1].toUpperCase();
        const sub = shortcut[2] || '';
        mappings.set(`${method} ${normalizePath(basePath + sub)}`, rel);
        continue;
      }

      // @RequestMapping(value = "/x", method = RequestMethod.POST)
      const rm = line.match(/@RequestMapping\s*\(([^)]*)\)/);
      if (rm && /method\s*=\s*RequestMethod/.test(rm[1])) {
        const v = rm[1].match(/(?:value\s*=\s*)?"(\/[^"]*)"/);
        const mm = rm[1].match(/method\s*=\s*RequestMethod\.(\w+)/);
        if (v && mm) {
          mappings.set(`${mm[1].toUpperCase()} ${normalizePath(basePath + v[1])}`, rel);
        }
      }
    }
  }
  return mappings;
}

// ---------------------------------------------------------------- 前端调用

function collectFrontendCalls() {
  // 同样的跨平台修复：不再调用 `find`。
  const files = walkFiles(FRONTEND_SRC).filter(
    p => /\.(js|vue|ts)$/.test(p)
  );

  const calls = [];
  // 匹配 request(`...`) request('...') request("...") 的第一参数路径（修复 2026-09-22：
  // 之前用 m.index+300 字符窗口扫描 method:，在密集 API 文件中会跨函数边界，
  // 把相邻函数的 method: "POST" 误判为本函数的 method。
  // 改用括号匹配：找到 request( 位置，向后找匹配的 )，只在两者之间扫描 method:。
  const callRe = /request\s*\(\s*([`'"])([^`'""]*)\1/g;

  for (const abs of files) {
    const rel = path.relative(ROOT, abs);
    if (!existsSync(abs)) continue;
    const src = readFileSync(abs, 'utf8');
    let m;
    while ((m = callRe.exec(src)) !== null) {
      const raw = m[2]; // captured path inside the quotes
      if (!raw.startsWith('/')) continue;
      // 找 request( 的位置（m[0] 匹配 "request('...')" 或 "request("...`")"
      const parenOpen = m.index + m[0].indexOf('(');
      // 找匹配的闭括号，处理嵌套的括号/字符串/注释
      let depth = 0, endPos = -1;
      outer:
      for (let i = parenOpen; i < src.length; i++) {
        const ch = src[i];
        if (ch === '"' || ch === "'" || ch === '`') {
          const q = ch;
          for (i++; i < src.length && src[i] !== q && src[i] !== '\n'; i++) {
            if (src[i] === '\\') i++;
          }
          continue;
        }
        if (ch === '/' && src[i + 1] === '/') { for (i++; i < src.length && src[i] !== '\n'; i++) {} continue; }
        if (ch === '/' && src[i + 1] === '*') { for (i += 2; i < src.length && !(src[i] === '*' && src[i + 1] === '/'); i++) {} i++; continue; }
        if (ch === '(' || ch === '{' || ch === '[') depth++;
        else if (ch === ')' || ch === '}' || ch === ']') { depth--; if (depth === 0) { endPos = i; break outer; } }
      }
      const optionsStr = endPos > parenOpen ? src.slice(parenOpen, endPos + 1) : '';
      const methodM = optionsStr.match(/method\s*:\s*["'`](\w+)["'`]/);
      const method = methodM ? methodM[1].toUpperCase() : 'GET';
      let path = raw.split('?')[0];
      // 路径段变量：/${id} -> /{x}（前面紧跟斜杠，是路径的一部分）
      path = path.replace(/\/\$\{[^}]*\}/g, '/{x}');
      // 其余 ${...} 是查询串变量（前端普遍写成 `${qs}`，${qs} 自带 ? 前缀），
      // 静态无法求值，直接截断；用 [\s\S]*$ 避免 ${qs({...})} 这类嵌套括号残留。
      path = path.replace(/\$\{[\s\S]*$/, '');
      const normalized = normalizePath('/api' + path);
      calls.push({ method, path: normalized, file: rel, snippet: raw });
    }
  }
  return calls;
}

// ---------------------------------------------------------------- 解析自检

// 括号匹配器是手写解析器，R5 P2-4 建议补 3~5 个内联 fixture 断言覆盖
// 常见边界（对象字面量里再嵌模板串、箭头函数体中的 `)`、换行/多参数等）。
// 选 --self-test 运行：既不影响 CI 默认流程，也能在改动解析逻辑时立刻复检。
if (process.argv.includes('--self-test')) {
  const { runBracketMatcherSelfTest } = await import('./_bracket_matcher_fixtures.mjs');
  const ok = runBracketMatcherSelfTest();
  process.exit(ok ? 0 : 1);
}

const backend = collectBackendMappings();
const backendPaths = new Set([...backend.keys()].map(k => k.split(' ')[1]));

// 已知一定存在的映射（解析器回归测试：这些若解析不出来，说明注解解析写错了，
// 不能拿结果当结论 —— 2026-09-21 曾因解析 bug 把 /api/hazards 误报成缺失）
const SELF_CHECK = [
  ['GET', '/api/hazards'],
  ['POST', '/api/hazards'],
  ['GET', '/api/warnings'],
  ['POST', '/api/warnings'],
  ['GET', '/api/warnings/{x}'],
];

const selfCheckFailures = SELF_CHECK.filter(
  ([method, path]) => !backend.has(`${method} ${path}`)
);

if (selfCheckFailures.length > 0) {
  console.error('✗ 后端注解解析自检失败，以下已知存在的映射未被解析出来：');
  selfCheckFailures.forEach(([m, p]) => console.error(`    ${m} ${p}`));
  console.error('  结果不可信，请先修正 check-frontend-endpoints.mjs 的解析逻辑。');
  process.exit(2);
}

// ---------------------------------------------------------------- 比对

/**
 * 已知缺口基线：已确认存在、但尚未排期修复的调用。
 *
 * 这里只登记「已人工核实为真缺口」的条目（附发现日期与影响面），
 * 它们不会让门禁失败，避免存量问题长期卡住 CI；
 * 但**任何新出现的缺口都会让本脚本以退出码 1 失败**。
 * 修复后请从此清单移除，让门禁重新覆盖该路径。
 *
 * 格式：[HTTP方法, 规范化路径, 发现日期, 说明]
 */
const KNOWN_GAPS = [
  ['GET', '/api/ai/drift', '2026-09-21',
    'ModelManagePage.vue 引用的漂移监控接口，Spring Boot 端 /api/ai 下未实现（无通配转发）'],
  ['GET', '/api/ai/multimodal', '2026-09-21',
    'AiInsightPage.vue 引用的多模态分析接口，Spring Boot 端 /api/ai 下未实现'],
];

const knownGapKeys = new Set(KNOWN_GAPS.map(([m, p]) => `${m} ${p}`));

const calls = collectFrontendCalls();
const seen = new Set();
const missing = [];
const methodMismatch = [];
const pendingKnown = [];

for (const c of calls) {
  const key = `${c.method} ${c.path}`;
  if (seen.has(key)) continue;
  seen.add(key);

  if (backend.has(key)) continue;

  if (knownGapKeys.has(key)) {
    pendingKnown.push(c);
    continue;
  }

  if (backendPaths.has(c.path)) {
    const available = [...backend.keys()]
      .filter(k => k.split(' ')[1] === c.path)
      .map(k => k.split(' ')[0])
      .sort();
    methodMismatch.push({ ...c, available });
  } else {
    missing.push(c);
  }
}

console.log(`后端映射: ${backend.size}`);
console.log(`前端请求(去重后): ${seen.size}`);
console.log(`解析自检: 通过 (${SELF_CHECK.length} 条已知映射)`);

if (methodMismatch.length > 0) {
  console.log(`\n⚠ 路径存在但 HTTP 方法不匹配 (${methodMismatch.length})：`);
  methodMismatch.sort((a, b) => a.path.localeCompare(b.path))
    .forEach(c => console.log(`    ${c.method} ${c.path}   后端仅有: ${c.available.join('/')}   <- ${c.file}`));
}

if (pendingKnown.length > 0) {
  console.log(`\n○ 已知缺口（已登记基线，不阻断 CI，共 ${pendingKnown.length}）：`);
  pendingKnown.sort((a, b) => a.path.localeCompare(b.path)).forEach(c => {
    const note = KNOWN_GAPS.find(([m, p]) => `${m} ${p}` === `${c.method} ${c.path}`);
    console.log(`    ${c.method} ${c.path}  [${note[2]}] ${note[3]}`);
  });
  console.log('    修复后请从脚本顶部 KNOWN_GAPS 中移除，让门禁重新覆盖。');
}

if (missing.length > 0) {
  console.log(`\n✗ 后端不存在且未登记基线的请求 (${missing.length})：`);
  missing.sort((a, b) => a.path.localeCompare(b.path))
    .forEach(c => console.log(`    ${c.method} ${c.path}   <- ${c.file}`));
  console.log('\n以上请求会在运行时 404/405：请补齐后端接口、修正前端调用，');
  console.log('或确认为存量缺口后在 KNOWN_GAPS 中登记。');
  process.exit(1);
}

console.log('\n✓ 未发现新的「前端调了、后端没有」的请求。');
process.exit(0);
