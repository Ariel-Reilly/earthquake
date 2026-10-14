#!/usr/bin/env node
/**
 * tokenize-radii.mjs —— 「硬编码圆角/阴影 → 设计令牌」批量迁移工具
 *
 * 背景：
 *   全站 22 种硬编码 border-radius、1128 处；而 var(--r-*) 在业务页里用了 0 次。
 *   于是同一个「卡片」概念在不同页面是 8 / 10 / 12 / 13 / 14px，
 *   同一个「按钮」是 6 / 7 / 8 / 9 / 10px —— 这是"看着不齐"的主要来源之一。
 *   半径还有第二个好处：令牌走 rem/变量后，「紧凑模式」才能真正生效。
 *
 * 分档策略（就近吸附到已有令牌，避免视觉跳变）：
 *   1~3px          → 保留（发丝线/微标记，属于精细值）
 *   4~9px          → --r-xs / --r-sm
 *   10~14px        → --r-md / --r-lg
 *   16~22px        → --r-xl / --r-2xl
 *   >=24px 或 999* → --r-2xl / --r-pill
 *
 * 用法：
 *   node scripts/tokenize-radii.mjs            # 预演
 *   node scripts/tokenize-radii.mjs --write
 */

import { readFileSync, writeFileSync, readdirSync } from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(process.cwd())
const WRITE = process.argv.includes('--write')
const ONLY = (() => {
  const i = process.argv.indexOf('--only')
  return i >= 0 ? process.argv[i + 1] : null
})()

/* ── 圆角分档表：数值 → 令牌 ── */
const RADIUS = {
  4: '--r-xs',
  5: '--r-sm',
  6: '--r-sm',
  7: '--r-sm',
  8: '--r-sm',
  9: '--r-md',
  10: '--r-md',
  11: '--r-md',
  12: '--r-lg',
  13: '--r-lg',
  14: '--r-lg',
  15: '--r-lg',
  16: '--r-lg',
  17: '--r-xl',
  18: '--r-xl',
  19: '--r-xl',
  20: '--r-xl',
  21: '--r-2xl',
  22: '--r-2xl',
  24: '--r-2xl',
  26: '--r-2xl',
  28: '--r-2xl',
  32: '--r-3xl',
  36: '--r-3xl',
  /* 胶囊：999 / 9999px 两种写法 */
  999: '--r-pill',
  1000: '--r-pill',
  9999: '--r-pill',
}

/* ── 阴影：把重复出现的自有阴影吸附到三档令牌 ── */
const SHADOW = [
  // 卡片级：极轻
  [/^0 1px (2|3|4)px rgba\(0, 0, 0, 0?\.0[3-6]\)$/i, '--shadow-xs'],
  [/^0 1px (2|3|4)px rgba\(15, 23, 42, 0?\.0[3-6]\)$/i, '--shadow-xs'],
  [/^0 2px 4px rgba\(0, 0, 0, 0?\.0[4-6]\)$/i, '--shadow-sm'],
  // 浮层：中等
  [/^0 4px 12px rgba\(0, 0, 0, 0?\.0[4-8]\)$/i, '--shadow-md'],
  [/^0 4px 16px rgba\(0, 0, 0, 0?\.0[4-8]\)$/i, '--shadow-md'],
  // 模态：强
  [/^0 8px 24px rgba\(0, 0, 0, 0?\.0[6-9]\)$/i, '--shadow-lg'],
  [/^0 12px 32px rgba\(0, 0, 0, 0?\.1[0-9]\)$/i, '--shadow-lg'],
]

function listFiles() {
  const start = path.join(ROOT, ONLY ?? 'src')
  const out = []
  const walk = (dir) => {
    let entries
    try {
      entries = readdirSync(dir, { withFileTypes: true })
    } catch {
      return
    }
    for (const e of entries) {
      const full = path.join(dir, e.name)
      if (e.isDirectory()) {
        if (e.name === 'node_modules' || e.name.startsWith('.')) continue
        walk(full)
      } else if (e.name.endsWith('.vue') || e.name.endsWith('.css')) {
        out.push(full)
      }
    }
  }
  walk(start)
  return out
}

function styleBlocks(src, isCss) {
  if (isCss) return [{ start: 0, end: src.length, bodyStart: 0 }]
  const out = []
  const re = /<style[^>]*>([\s\S]*?)<\/style>/gi
  let m
  while ((m = re.exec(src))) {
    out.push({ start: m.index, end: m.index + m[0].length, bodyStart: m.index + m[0].indexOf('>') + 1 })
  }
  return out
}

const inStyle = (blocks, idx) => blocks.some((b) => idx >= b.bodyStart && idx < b.end)

const stats = { files: 0, changed: 0, radii: 0, shadows: 0, byToken: new Map(), shadowHits: new Map() }
const bump = (map, k, n = 1) => map.set(k, (map.get(k) ?? 0) + n)

for (const file of listFiles()) {
  const src = readFileSync(file, 'utf8')
  const isCss = file.endsWith('.css')
  const blocks = styleBlocks(src, isCss)
  if (!blocks.length) continue
  stats.files++

  const edits = [] // { start, end, text }

  /* 1) border-radius: Npx */
  const rr = /border-radius:\s*(\d+(?:\.\d+)?)px/g
  let m
  while ((m = rr.exec(src))) {
    if (!inStyle(blocks, m.index)) continue
    const px = Math.round(Number(m[1]))
    const token = RADIUS[px]
    if (!token) continue
    edits.push({ start: m.index, end: rr.lastIndex, text: `border-radius: var(${token})` })
    bump(stats.byToken, token)
    stats.radii++
  }

  /* 2) box-shadow: <literal>（仅当能匹配到令牌时） */
  const sr = /box-shadow:\s*([^;{}]+);/g
  while ((m = sr.exec(src))) {
    if (!inStyle(blocks, m.index)) continue
    const value = m[1].replace(/\s+/g, ' ').trim()
    if (value.includes('var(') || value === 'none') continue
    for (const [re, token] of SHADOW) {
      if (re.test(value)) {
        edits.push({ start: m.index, end: sr.lastIndex, text: `box-shadow: var(${token});` })
        bump(stats.shadowHits, token)
        stats.shadows++
        break
      }
    }
  }

  if (!edits.length) continue
  edits.sort((a, b) => a.start - b.start)
  let out = ''
  let cursor = 0
  for (const e of edits) {
    if (e.start < cursor) continue
    out += src.slice(cursor, e.start) + e.text
    cursor = e.end
  }
  out += src.slice(cursor)
  stats.changed++
  if (WRITE) {
    try {
      writeFileSync(file, out, 'utf8')
    } catch (err) {
      console.error(`写入失败 ${path.relative(ROOT, file)}: ${err.code}`)
      process.exitCode = 1
    }
  }
}

console.log(`${WRITE ? '【已写入】' : '【预演·未写入】'} 扫描 ${stats.files} 个文件，改动 ${stats.changed} 个`)
console.log(`圆角 ${stats.radii} 处，阴影 ${stats.shadows} 处`)
console.log('\n—— 圆角按令牌 ——')
for (const [t, n] of [...stats.byToken].sort((a, b) => b[1] - a[1])) console.log(`  ${String(n).padStart(5)}  var(${t})`)
console.log('\n—— 阴影按令牌 ——')
for (const [t, n] of [...stats.shadowHits].sort((a, b) => b[1] - a[1])) console.log(`  ${String(n).padStart(5)}  var(${t})`)
