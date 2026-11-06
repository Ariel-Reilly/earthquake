#!/usr/bin/env node
// 检查所有 setInterval 调用是否都有对应的 clearInterval 清理
// 用途：发现 M5 定时器泄漏问题
//
// 判定逻辑：
//   1. 收集每个文件 setInterval 赋值的目标变量
//      - `const t = setInterval(...)` → t
//      - `t = setInterval(...)`        → t
//      - `pollTimer.value = setInterval(...)` → pollTimer （去掉 .value）
//   2. 收集同文件 clearInterval 调用中传入的变量名（同样去掉 .value）
//   3. 满足以下任一条件即视为已清理：
//      a. clearInterval(var) 出现在 setInterval 之后（最常见的内联写法）
//      b. clearInterval(var) 位于具名清理函数内，且该函数被
//         onBeforeUnmount / onUnmounted 钩子引用（先定义函数、后启动定时器的写法）
//   4. 条件 b 是 2026-11-06 修的误报：RegisterPage.vue 的 stopCountdown()
//      定义在 startCountdown() 之前，旧版「clear 必须在 set 之后」的单条规则
//      会把这种正确实现判成泄漏。
//
// 退出码：发现未清理时返回 1；--self-test 跑内置断言（不扫源码）。
//
// 兼容性：不用 fs/promises.glob（Node 22.14 前不存在，CI 会 SyntaxError），
// 用 readdirSync 递归；路径以脚本自身位置锚定，不依赖 CWD。

import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SET_RE = /(?<![\w$])(?:window\.)?setInterval\s*\(/g;
const CLEAR_RE = /(?<![\w$])(?:window\.)?clearInterval\s*\(([^)]+)\)/g;
const ASSIGN_RE = /^\s*(?:const|let|var)\s+([\w.$]+)\s*=\s*(?:[\w.]+\.)?setInterval\s*\(/;
const MUTATE_RE = /\b([\w.$]+)\s*=\s*(?:[\w.]+\.)?setInterval\s*\(/;
// 具名函数声明 / 箭头函数常量：用于定位 clearInterval 所在的清理函数
const FN_DECL_RE = /^\s*function\s+([\w$]+)\s*\(/;
const FN_ARROW_RE = /^\s*(?:const|let|var)\s+([\w$]+)\s*=\s*(?:\([^)]*\)|[\w$]+)\s*=>/;
const FN_ASSIGN_RE = /^\s*(?:const|let|var)\s+([\w$]+)\s*=\s*function\s*\(/;

function normalizeVar(v) {
  if (!v) return null;
  return v.replace(/\.value$/, '').trim();
}

function extractVarAt(line) {
  const a = line.match(ASSIGN_RE);
  if (a) return normalizeVar(a[1]);
  const b = line.match(MUTATE_RE);
  if (b) return normalizeVar(b[1]);
  return null;
}

/** 向上找 clearInterval 所在的最近具名函数名（找不到返回 null）。 */
function enclosingFunctionName(lines, lineIdx) {
  for (let i = lineIdx; i >= 0; i--) {
    const line = lines[i];
    // 遇到另一个顶层块/空行不截断，保持简单：只认最后一次函数声明
    const m = line.match(FN_DECL_RE) || line.match(FN_ARROW_RE) || line.match(FN_ASSIGN_RE);
    if (m) return m[1];
  }
  return null;
}

/** 生命周期钩子是否引用了清理函数：直接传入，或在钩子回调体内被调用。 */
function hookCallsFunction(content, fnName) {
  if (!fnName) return false;
  const escaped = fnName.replace(/\$/g, '\\$');
  // ① onBeforeUnmount(stopTimer)
  if (new RegExp(`on(?:BeforeUnmount|Unmounted)\\s*\\(\\s*${escaped}\\s*\\)`).test(content)) {
    return true;
  }
  // ② onBeforeUnmount(() => { stopTimer() }) / onUnmounted(() => stopTimer())
  const hookRe = /on(?:BeforeUnmount|Unmounted)\s*\(/g;
  let m;
  while ((m = hookRe.exec(content)) !== null) {
    const window = content.slice(m.index, m.index + 300);
    if (new RegExp(`\\b${escaped}\\s*\\(`).test(window)) return true;
  }
  return false;
}

/** 核心判定：可单独用于自测，返回未清理的定时器列表。 */
export function checkContent(content) {
  const lines = content.split(/\r?\n/);

  const timers = [];
  lines.forEach((line, idx) => {
    if (/^\s*\*/.test(line) || /^\s*\/\//.test(line)) return;
    let m;
    while ((m = SET_RE.exec(line)) !== null) {
      const v = extractVarAt(line);
      if (v) timers.push({ line: idx + 1, var: v, text: line.trim() });
    }
  });
  SET_RE.lastIndex = 0;
  if (timers.length === 0) return [];

  const clears = [];
  lines.forEach((line, idx) => {
    let m;
    while ((m = CLEAR_RE.exec(line)) !== null) {
      clears.push({
        line: idx + 1,
        var: normalizeVar(m[1].trim()),
        fn: enclosingFunctionName(lines, idx),
      });
    }
  });
  CLEAR_RE.lastIndex = 0;

  const failures = [];
  for (const t of timers) {
    const clearedInline = clears.some(c => c.var === t.var && c.line > t.line);
    const clearedViaHook = clears.some(
      c => c.var === t.var && hookCallsFunction(content, c.fn)
    );
    if (!clearedInline && !clearedViaHook) failures.push(t);
  }
  return failures;
}

// ── 自测：内置 fixture，避免「改了脚本却把正确实现又判成泄漏」静默回归 ──
const SELF_TESTS = [
  {
    name: '内联清理（clear 在 set 之后）',
    content: `let t;\nfunction start(){ t = setInterval(()=>{}, 1000); }\nfunction stop(){ clearInterval(t); }\n`,
    want: 0,
  },
  {
    name: '清理函数定义在前 + onBeforeUnmount 直接引用（RegisterPage 写法）',
    content: `let countdownTimer = null;\nfunction stopCountdown(){ window.clearInterval(countdownTimer); countdownTimer = null; }\nfunction start(){ countdownTimer = window.setInterval(()=>{}, 1000); }\nonBeforeUnmount(stopCountdown)\n`,
    want: 0,
  },
  {
    name: '钩子回调体内调用清理函数',
    content: `let t;\nfunction stop(){ clearInterval(t); }\nfunction start(){ t = setInterval(()=>{}, 1000); }\nonUnmounted(() => { stop() })\n`,
    want: 0,
  },
  {
    name: '完全没有清理（真泄漏）',
    content: `let leakTimer;\nfunction start(){ leakTimer = setInterval(()=>{}, 1000); }\n`,
    want: 1,
  },
  {
    name: '有 clearInterval 但从未在卸载钩子调用',
    content: `let t;\nfunction stop(){ clearInterval(t); }\nfunction start(){ t = setInterval(()=>{}, 1000); }\nfunction neverCalled(){ stop(); }\n`,
    want: 1,
  },
  {
    name: '.value 归一化 + 注释行不参与',
    content: `const pollTimer = ref(null);\n// pollTimer.value = setInterval(()=>{}, 1000);\nfunction start(){ pollTimer.value = setInterval(()=>{}, 1000); }\nonBeforeUnmount(() => { clearInterval(pollTimer.value) })\n`,
    want: 0,
  },
];

function runSelfTest() {
  let failed = 0;
  for (const t of SELF_TESTS) {
    const got = checkContent(t.content).length;
    const ok = got === t.want;
    console.log(`${ok ? '✓' : '✗'} ${t.name}  (期望 ${t.want}，实际 ${got})`);
    if (!ok) failed++;
  }
  console.log(`自测：${SELF_TESTS.length - failed}/${SELF_TESTS.length} 通过`);
  process.exit(failed === 0 ? 0 : 1);
}

if (process.argv.includes('--self-test')) runSelfTest();

const SCRIPT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SRC_DIR = path.join(SCRIPT_ROOT, 'src');
const EXTS = new Set(['.js', '.mjs', '.ts', '.vue']);

function* walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(full);
    else if (EXTS.has(path.extname(entry.name))) yield full;
  }
}

const failures = [];
for (const file of walk(SRC_DIR)) {
  const content = readFileSync(file, 'utf8');
  for (const t of checkContent(content)) failures.push({ file, ...t });
}

if (failures.length === 0) {
  console.log('✓ 所有 setInterval 都有对应的 clearInterval 清理');
  process.exit(0);
}

console.log(`✗ 发现 ${failures.length} 处 setInterval 未清理：\n`);
for (const f of failures) {
  console.log(`  ${f.file}:${f.line}  var=${f.var}`);
  console.log(`      ${f.text}`);
}
console.log('\n修复方向：在 onBeforeUnmount / onUnmounted 中调用 clearInterval(timerVar)，或把清理函数直接交给钩子');
process.exit(1);
