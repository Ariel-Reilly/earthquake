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
import { join } from 'path';
import { execSync } from 'child_process';

const ROOT = process.cwd();
const BACKEND_SRC = join(ROOT, 'backend/src/main/java');
const FRONTEND_SRC = join(ROOT, 'frontend/src');

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
  let files = [];
  try {
    files = execSync(
      'find backend/src/main/java -name "*.java"',
      { cwd: ROOT, maxBuffer: 1e9 }
    ).toString().trim().split('\n').filter(Boolean);
  } catch {
    return new Map();
  }

  const mappings = new Map(); // "METHOD PATH" -> file

  for (const rel of files) {
    const abs = join(ROOT, rel);
    if (!existsSync(abs)) continue;
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
  let files = [];
  try {
    files = execSync(
      'find frontend/src -name "*.js" -o -name "*.vue" -o -name "*.ts"',
      { cwd: ROOT, maxBuffer: 1e9 }
    ).toString().trim().split('\n').filter(Boolean);
  } catch {
    return [];
  }

  const calls = [];
  // 匹配 request(...) 形式的第一参数（字符串或模板字符串）
  const callRe = /request\(\s*[`'"]([^`'"]*)[`'"]/g;

  for (const rel of files) {
    const abs = join(ROOT, rel);
    if (!existsSync(abs)) continue;
    const src = readFileSync(abs, 'utf8');
    let m;
    while ((m = callRe.exec(src)) !== null) {
      const raw = m[1];
      if (!raw.startsWith('/')) continue;
      const window = src.slice(m.index, m.index + 300);
      const methodM = window.match(/method:\s*["'`](\w+)["'`]/);
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
