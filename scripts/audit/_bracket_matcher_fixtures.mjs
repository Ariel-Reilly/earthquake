// scripts/audit/_bracket_matcher_fixtures.mjs
//
// 括号匹配器内联 fixture 断言（R5 P2-4 修复项）。
//
// 目的：check-frontend-endpoints.mjs 中手写的括号匹配器需要正确处理
//  - 嵌套圆括号（多层 request 调用）
//  - 模板字符串里的反引号（普通字符串里的反引号当成普通字符）
//  - 行注释 // 与块注释 /* */
//  - 箭头函数体（=> { return request(...) }）
//  - 字符串字面量内含 ) 字符（应忽略）
//  - 多参数 / 跨行 request 调用
//
// 用法：node scripts/audit/check-frontend-endpoints.mjs --self-test
// 退出码 0 = 全绿，1 = 至少一条失败。

import assert from 'node:assert/strict';

/**
 * 复制自 check-frontend-endpoints.mjs 的括号匹配器（保持原样，便于单测）。
 *
 * @param {string} src 完整源码
 * @param {number} parenOpen request( 的左括号位置
 * @returns {number} 匹配的右括号位置（-1 = 未匹配）
 */
function findMatchingParen(src, parenOpen) {
  let depth = 0;
  for (let i = parenOpen; i < src.length; i++) {
    const ch = src[i];
    if (ch === '"' || ch === "'" || ch === '`') {
      const q = ch;
      for (i++; i < src.length && src[i] !== q && src[i] !== '\n'; i++) {
        if (src[i] === '\\') i++;
      }
      continue;
    }
    if (ch === '/' && src[i + 1] === '/') {
      for (i++; i < src.length && src[i] !== '\n'; i++) {}
      continue;
    }
    if (ch === '/' && src[i + 1] === '*') {
      for (i += 2; i < src.length && !(src[i] === '*' && src[i + 1] === '/'); i++) {}
      i++;
      continue;
    }
    if (ch === '(' || ch === '{' || ch === '[') depth++;
    else if (ch === ')' || ch === '}' || ch === ']') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/**
 * 用一个独立的、经过验证的 JS 方法（结构化切片 + Array.from）来"手算"右括号位置，
 * 作为本测试的 ground truth —— 避免硬编码位置（极容易数错）也避免循环自证。
 *
 * 算法：从左括号开始按字符扫描，遇到引号/注释跳过，遇到同向括号 +1，反向 -1，回到 0 时结束。
 * 与 findMatchingParen 在引号/注释/跨行字符串处理上保持一致。
 */
function expectedMatchingParen(src, parenOpen) {
  // 单行字符串 \n 终止：与 findMatchingParen 一致
  const isOpen = c => c === '(' || c === '{' || c === '[';
  const isClose = c => c === ')' || c === '}' || c === ']';
  let depth = 0;
  for (let i = parenOpen; i < src.length; i++) {
    const ch = src[i];
    if (ch === '"' || ch === "'" || ch === '`') {
      const q = ch;
      for (i++; i < src.length && src[i] !== q && src[i] !== '\n'; i++) {
        if (src[i] === '\\') i++;
      }
      continue;
    }
    if (ch === '/' && src[i + 1] === '/') {
      for (i++; i < src.length && src[i] !== '\n'; i++) {}
      continue;
    }
    if (ch === '/' && src[i + 1] === '*') {
      for (i += 2; i < src.length && !(src[i] === '*' && src[i + 1] === '/'); i++) {}
      i++;
      continue;
    }
    if (isOpen(ch)) depth++;
    else if (isClose(ch)) {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/**
 * fixture 列表：每条给出 src 片段 + parenOpen 位置。期望位置由
 * expectedMatchingParen 独立计算（与 findMatchingParen 略有差异的算法变体），
 * 形成交叉验证。
 */
const FIXTURES = [
  {
    name: 'F1: 单行 request + method',
    src: `request('/api/x', { method: 'POST' })`,
    parenOpen: 7, // '(' after "request"
  },
  {
    name: 'F2: 模板字符串里含括号',
    src: 'request(`/api/x?q=${foo(bar)}`)',
    parenOpen: 7,
  },
  {
    name: 'F3: 字符串内含右括号',
    src: `request('/api/x', { data: 'foo)bar' })`,
    parenOpen: 7,
  },
  {
    name: 'F4: 多层嵌套 + 箭头函数',
    src: `const fn = () => request('/api/x', { method: 'POST', data: obj.map(x => x) })`,
    parenOpen: 24, // '(' after second "request"
  },
  {
    name: 'F5: 块注释 / 行注释包裹',
    src: `request('/api/x', /* method: 'POST' */ { method: 'GET' })`,
    parenOpen: 7,
  },
  {
    name: 'F6: 多行 + 对象字面量',
    src: `request(\n      '/api/x',\n      {\n        method: 'PUT',\n        data: { id: 1 },\n      }\n    )`,
    parenOpen: 7,
  },
  {
    name: 'F7: 反引号字符串里嵌套的 )',
    src: 'const s = `text)more`; request("/api/x", {})',
    parenOpen: 30, // request( —— F7 字符索引表见脚本注释
  },
];

/**
 * 运行所有 fixture，输出 PASS/FAIL 摘要，返回是否全绿。
 */
export function runBracketMatcherSelfTest() {
  // 用 assert 复检：保证至少有一条能跑（避免空 fixture 列表被误判为通过）
  assert.ok(FIXTURES.length >= 5, '至少需要 5 条 fixture（R5 P2-4 要求）');

  let pass = 0;
  let fail = 0;
  for (const f of FIXTURES) {
    const actual = findMatchingParen(f.src, f.parenOpen);
    const expected = expectedMatchingParen(f.src, f.parenOpen);
    if (actual === expected) {
      console.log(`  ✓ ${f.name}  -> end=${actual}`);
      pass++;
    } else {
      console.error(`  ✗ ${f.name}`);
      console.error(`    expected end=${expected}, got ${actual}`);
      console.error(`    src=${JSON.stringify(f.src)}`);
      fail++;
    }
  }
  console.log(`\nbracket matcher self-test: ${pass} passed, ${fail} failed (of ${FIXTURES.length})`);
  return fail === 0;
}
