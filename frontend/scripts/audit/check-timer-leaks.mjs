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
//   3. 每个 setInterval 变量必须在文件靠后位置至少被 clearInterval 一次
//
// 退出码：发现未清理时返回 1

import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC_GLOB = 'src/**/*.{js,mjs,ts,vue}';
const SET_RE = /(?<![\w$])(?:window\.)?setInterval\s*\(/g;
const CLEAR_RE = /(?<![\w$])(?:window\.)?clearInterval\s*\(([^)]+)\)/g;
const ASSIGN_RE = /^\s*(?:const|let|var)\s+([\w.$]+)\s*=\s*(?:[\w.]+\.)?setInterval\s*\(/;
const MUTATE_RE = /\b([\w.$]+)\s*=\s*(?:[\w.]+\.)?setInterval\s*\(/;

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

// 递归收集文件（R5 P1-1：不用 fs/promises.glob —— 该 API 在 Node 22.14 前不存在，
// CI 的 node 22 会直接 SyntaxError；readdirSync 全版本可用）
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
  const lines = content.split(/\r?\n/);

  // 收集 setInterval 赋值的目标变量
  const timers = [];
  lines.forEach((line, idx) => {
    if (/^\s*\*/.test(line) || /^\s*\/\//.test(line)) return;
    let m;
    while ((m = SET_RE.exec(line)) !== null) {
      const v = extractVarAt(line);
      if (v) timers.push({ line: idx + 1, var: v, text: line.trim() });
    }
  });

  if (timers.length === 0) continue;

  // 收集 clearInterval 调用
  const clears = [];
  lines.forEach((line, idx) => {
    let m;
    while ((m = CLEAR_RE.exec(line)) !== null) {
      const arg = m[1].trim();
      const v = normalizeVar(arg);
      clears.push({ line: idx + 1, var: v });
    }
  });

  // 简化判定：每个 timer 变量在文件中至少被 clear 一次（在 setInterval 行之后）
  for (const t of timers) {
    const cleared = clears.some(c => c.var === t.var && c.line > t.line);
    if (!cleared) {
      failures.push({
        file,
        line: t.line,
        var: t.var,
        text: t.text,
      });
    }
  }
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
console.log('\n修复方向：在 onBeforeUnmount / onUnmounted 中调用 clearInterval(timerVar)');
process.exit(1);
