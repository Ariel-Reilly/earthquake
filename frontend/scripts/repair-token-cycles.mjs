#!/usr/bin/env node
/**
 * repair-token-cycles.mjs —— 自动修复「自引用」的令牌定义
 *
 * 背景：批量色值替换曾多次把 `--c-surface: #ffffff;` 改写成
 * `--c-surface: var(--c-surface);`。自引用在 CSS 里构成依赖环，
 * 该属性 computed-value 阶段失效 → 浅色主题全站掉色。
 *
 * 本脚本不依赖行号（行号会随编辑漂移），而是：
 *   1. 扫描所有文件，找出 `--name: … var(--name) …` 的定义；
 *   2. 从 LIGHT_VALUES 取该令牌的正确字面量（这些值在此前会话中已确认）；
 *   3. 就地替换该定义的右侧，保留行内注释与其他格式；
 *   4. 复跑校验，确认自引用归零。
 *
 * 用法：
 *   node scripts/repair-token-cycles.mjs            # 预演
 *   node scripts/repair-token-cycles.mjs --write
 */

import { readFileSync, writeFileSync, readdirSync } from 'node:fs'
import path from 'node:path'

const ROOT = process.cwd()
const WRITE = process.argv.includes('--write')

/** 各令牌浅色主题下的权威取值（与 styles.css 的调色板注释一致） */
const LIGHT_VALUES = {
  // iOS 系统灰
  '--sys-gray': '#8e8e93',
  '--sys-gray-2': '#aeaeb2',
  '--sys-gray-3': '#c7c7cc',
  '--sys-gray-4': '#d1d1d6',
  '--sys-gray-5': '#e5e5ea',
  '--sys-gray-6': '#f2f2f7',
  // 品牌三层绿（莫兰迪）
  '--c-brand': '#6f9e7f',
  '--c-brand-hover': '#5f8e70',
  '--c-brand-press': '#54806a',
  '--c-brand-deep': '#4a7a5e',
  '--c-brand-deeper': '#385f49',
  '--c-brand-soft': '#eaf1ec',
  '--c-brand-mist': '#f1f6f2',
  '--c-brand-tint': '#c8d8ce',
  '--c-brand-ink': '#276749',
  '--c-brand-on': '#ffffff',
  // 文本层级
  '--c-text': '#1c1c1e',
  '--c-text-inv': '#ffffff',
  // 背景与面
  '--c-bg': '#f4f7f4',
  '--c-bg-deep': '#eaefeb',
  '--c-surface': '#ffffff',
  '--c-surface-2': '#f8faf8',
  '--c-surface-3': '#fcfdfc',
  '--c-morandi-warm': '#f2f4f1',
  '--c-morandi-clay': '#e8ece8',
  // 语义补充
  '--c-on-brand': '#ffffff',
  '--c-surface-ink': '#1c1c1e',
  '--c-ink-1': '#3a4f4a',
  '--c-ink-2': '#4d6862',
  '--c-ink-3': '#6b7f7c',
  '--c-overlay-ink': '#000000',
  '--c-warning-strong': '#d9c040',
  '--c-warning-ink': '#9b761e',
  '--c-danger-strong': '#c7553b',
  '--c-success-strong': '#15803d',
}

function listFiles() {
  const out = []
  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, e.name)
      if (e.isDirectory()) {
        if (e.name === 'node_modules' || e.name.startsWith('.')) continue
        walk(full)
      } else if (e.name.endsWith('.vue') || e.name.endsWith('.css')) out.push(full)
    }
  }
  walk(path.join(ROOT, 'src'))
  return out
}

let repaired = 0
const unknown = []

for (const file of listFiles()) {
  const text = readFileSync(file, 'utf8')
  const lines = text.split('\n')
  let touched = false

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    /* 匹配：<缩进>--name: <值含 var(--name)> <可选注释> */
    const def = /^(\s*)(--[a-z0-9-]+)\s*:\s*([^;]*?)(\s*;\s*)(\/\*.*)?$/.exec(line)
    if (!def) continue
    const [, indent, name, value, semi, comment] = def
    const selfRe = new RegExp(`var\\(\\s*${name.replace(/-/g, '\\-')}\\s*[,)]`)
    if (!selfRe.test(value)) continue

    const replacement = LIGHT_VALUES[name]
    if (!replacement) {
      unknown.push({ file: path.relative(ROOT, file), line: i + 1, name })
      continue
    }
    lines[i] = `${indent}${name}: ${replacement};${comment ? ' ' + comment : ''}`
    repaired++
    touched = true
  }

  if (touched && WRITE) writeFileSync(file, lines.join('\n'), 'utf8')
}

console.log(`${WRITE ? '【已写入】' : '【预演·未写入】'} 修复自引用定义 ${repaired} 条`)
if (unknown.length) {
  console.log(`\n⚠ 未收录（需要人工给值）：`)
  for (const u of unknown) console.log(`  ${u.file}:${u.line}  ${u.name}`)
  process.exitCode = 1
}
