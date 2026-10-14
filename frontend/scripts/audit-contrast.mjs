#!/usr/bin/env node
/**
 * audit-contrast.mjs —— 暗色/浅色主题下的可读性审计（无需浏览器）
 *
 * 动机：本次美化把 4000+ 处写死颜色改成令牌，目标是「切到深色主题后不再白底白字」。
 * 没有浏览器截图的情况下，可以用静态手段把风险量化：
 *   在每条 CSS 规则内取 (color, background) 配对 → 解析令牌为具体色值 →
 *   按 WCAG 相对亮度算对比度 → 低于阈值即报警。
 *
 * 覆盖范围与已知局限（务必如实理解）：
 *   · 只看「同一条规则内同时声明 color 与 background」的配对，跨规则继承的
 *     背景无法静态判定（因此是「下界」而非穷尽）。
 *   · 渐变背景按其中最浅的一档近似处理。
 *   · 计算结果用于发现「结构性错误」（如深色下白字白底），不替代人工目视。
 *
 * 用法：
 *   node scripts/audit-contrast.mjs                 # 汇总
 *   node scripts/audit-contrast.mjs --theme dark    # 只审暗色
 *   node scripts/audit-contrast.mjs --top 20        # 列出最差
 */

import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'

const ROOT = process.cwd()
const args = process.argv.slice(2)
const ONLY_THEME = (() => {
  const i = args.indexOf('--theme')
  return i >= 0 ? args[i + 1] : null
})()
const TOP = (() => {
  const i = args.indexOf('--top')
  return i >= 0 ? Number(args[i + 1]) : 12
})()

/* ══════════════ 1. 抽取令牌 ══════════════ */
const css = readFileSync(path.join(ROOT, 'src/styles.css'), 'utf8')

/** 从某个块内解析 `--name: value;` */
function parseBlock(text) {
  const out = new Map()
  for (const m of text.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/gi)) {
    out.set(m[1], m[2].trim())
  }
  return out
}

/** 取 :root 中「浅色」的定义（跳过暗色块） */
function rootLightBlock() {
  // :root 第一段：从第一个 :root { 到最后一次出现暗色选择器之前
  const darkIdx = css.search(/:root\.theme-dark|:root\[data-theme="dark"\]/)
  const firstRoot = css.indexOf(':root {')
  return parseBlock(css.slice(firstRoot, darkIdx > 0 ? darkIdx : css.length))
}

/** 取暗色块（两个暗色块合并，后写的覆盖先写的） */
function darkBlock() {
  const merged = new Map()
  const parts = []
  const mediaDark = css.indexOf('@media (prefers-color-scheme: dark)')
  if (mediaDark >= 0) {
    // 该媒体查询里第一个 { ... } 平衡块
    const open = css.indexOf('{', mediaDark)
    let depth = 0
    for (let i = open; i < css.length; i++) {
      if (css[i] === '{') depth++
      else if (css[i] === '}') {
        depth--
        if (depth === 0) {
          parts.push(css.slice(open + 1, i))
          break
        }
      }
    }
  }
  for (const idx of [css.indexOf(':root.theme-dark'), css.indexOf(':root[data-theme="dark"]')]) {
    if (idx < 0) continue
    const open = css.indexOf('{', idx)
    let depth = 0
    for (let i = open; i < css.length; i++) {
      if (css[i] === '{') depth++
      else if (css[i] === '}') {
        depth--
        if (depth === 0) {
          parts.push(css.slice(open + 1, i))
          break
        }
      }
    }
  }
  for (const p of parts) for (const [k, v] of parseBlock(p)) merged.set(k, v)
  return merged
}

const lightTokens = rootLightBlock()
const darkTokens = darkBlock()

/** 解析令牌，返回 [r,g,b,a]；无法解析返回 null */
function resolve(value, tokens, depth = 0) {
  if (depth > 8 || !value) return null
  let v = value.trim()

  const varMatch = /^var\(\s*(--[a-z0-9-]+)\s*(?:,\s*([^)]+))?\)$/i.exec(v)
  if (varMatch) {
    const target = tokens.get(varMatch[1])
    if (target) return resolve(target, tokens, depth + 1)
    if (varMatch[2]) return resolve(varMatch[2], tokens, depth + 1)
    return null
  }

  let m = /^#([0-9a-f]{3})$/i.exec(v)
  if (m) return [...m[1]].map((c) => parseInt(c + c, 16)).concat(1)
  m = /^#([0-9a-f]{6})$/i.exec(v)
  if (m) return [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16)).concat(1)
  m = /^#([0-9a-f]{8})$/i.exec(v)
  if (m) return [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16)).concat(parseInt(m[1].slice(6, 8), 16) / 255)
  m = /^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s/]+([\d.]+))?\s*\)$/i.exec(v)
  if (m) return [Number(m[1]), Number(m[2]), Number(m[3]), m[4] === undefined ? 1 : Number(m[4])]
  if (v === 'white') return [255, 255, 255, 1]
  if (v === 'black') return [0, 0, 0, 1]
  if (v === 'transparent') return [0, 0, 0, 0]
  // 渐变：取第一个出现的颜色
  const gm = /(#[0-9a-f]{3,8}|rgba?\([^)]+\))/i.exec(v)
  if (gm) return resolve(gm[1], tokens, depth + 1)
  return null
}

/* ══════════════ 2. WCAG 对比度 ══════════════ */
const lum = ([r, g, b]) => {
  const f = (c) => {
    const x = c / 255
    return x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
}
/** 把带 alpha 的前景色合成到背景上 */
const over = (fg, bg) => {
  const a = fg[3]
  return [0, 1, 2].map((i) => Math.round(fg[i] * a + bg[i] * (1 - a)))
}
function contrast(fg, bg) {
  if (!fg || !bg) return null
  const bgOpaque = bg[3] < 1 ? over(bg, [255, 255, 255]) : bg
  const fgOpaque = fg[3] < 1 ? over(fg, bgOpaque) : fg
  const l1 = lum(fgOpaque)
  const l2 = lum(bgOpaque)
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1]
  return (hi + 0.05) / (lo + 0.05)
}

/* ══════════════ 3. 扫描规则 ══════════════ */
function collectFiles() {
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

const findings = []
for (const file of collectFiles()) {
  const text = readFileSync(file, 'utf8')
  const styleText = file.endsWith('.css')
    ? text
    : [...text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/gi)].map((m) => m[1]).join('\n')

  // 逐条规则解析：选择器 { 声明 }
  for (const m of styleText.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const selector = m[1].trim().replace(/\s+/g, ' ')
    const body = m[2]
    if (selector.startsWith('@') || selector.includes('@media')) continue
    const colorM = /(?:^|;)\s*color\s*:\s*([^;]+)/.exec(body)
    const bgM = /(?:^|;)\s*background(?:-color)?\s*:\s*([^;]+)/.exec(body)
    if (!colorM || !bgM) continue
    const fgRaw = colorM[1].trim()
    const bgRaw = bgM[1].trim()
    if (bgRaw.includes('gradient') && !/#|rgba?\(/.test(bgRaw)) continue
    for (const [theme, tokens] of [
      ['light', lightTokens],
      ['dark', darkTokens],
    ]) {
      if (ONLY_THEME && theme !== ONLY_THEME) continue
      const fg = resolve(fgRaw, tokens)
      const bg = resolve(bgRaw, tokens)
      const ratio = contrast(fg, bg)
      if (ratio === null) continue
      if (ratio < 3) {
        /* 分两类，避免误导：
           confirmed —— 前景与背景都不透明，合成结果唯一，结论确定；
           review    —— 背景（或前景）带 alpha，实际观感取决于父级背景，
                        静态无法判定，必须人工在真实页面上确认。 */
        const alphaInvolved = (bg && bg[3] < 1) || (fg && fg[3] < 1) || /gradient/.test(bgRaw)
        findings.push({
          theme,
          kind: alphaInvolved ? 'review' : 'confirmed',
          ratio: Math.round(ratio * 100) / 100,
          file: path.relative(ROOT, file),
          selector,
          fg: fgRaw,
          bg: bgRaw,
        })
      }
    }
  }
}

/* ══════════════ 4. 报告 ══════════════ */
const byTheme = (t) => findings.filter((f) => f.theme === t)
const confirmed = findings.filter((f) => f.kind === 'confirmed')
const review = findings.filter((f) => f.kind === 'review')
console.log('═══ 主题可读性审计（WCAG 对比度 < 3:1）═══')
console.log(`  确定问题(前景与背景均不透明)  ${confirmed.length}`)
console.log(`  待人工确认(含半透明/渐变)     ${review.length}`)
console.log(`  按主题：浅色 ${byTheme('light').length} 处，暗色 ${byTheme('dark').length} 处`)
for (const t of ['light', 'dark']) {
  if (ONLY_THEME && t !== ONLY_THEME) continue
  const list = byTheme(t)
    .filter((f) => f.kind === 'confirmed')
    .sort((a, b) => a.ratio - b.ratio)
    .slice(0, TOP)
  if (!list.length) continue
  console.log(`\n── ${t} 确定问题 最差 ${list.length} 处 ──`)
  for (const f of list) {
    console.log(`  ${String(f.ratio).padStart(5)}:1  ${f.file}`)
    console.log(`            ${f.selector}  color:${f.fg}  bg:${f.bg}`)
  }
}
console.log(
  '\n说明：只统计「同一条规则内同时声明 color 与 background」的配对。\n' +
    '  · 确定问题：两侧均不透明，合成结果唯一，可直接修。\n' +
    '  · 待人工确认：背景/前景含 alpha 或渐变（多用于深色导航栏上的白字），' +
    '静态无法知道父级背景，需在真实页面确认，勿据此批量改色。',
)
process.exit(0)
