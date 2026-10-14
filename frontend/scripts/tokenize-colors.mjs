#!/usr/bin/env node
/**
 * tokenize-colors.mjs —— 「硬编码色值 → 语义设计令牌」批量迁移工具
 *
 * 背景：
 *   全站 110 个 .vue 文件里有 4758 处硬编码色值、只有 408 处 var(--token)。
 *   直接后果是暗色模式失效（.theme-dark 只覆盖 token，覆盖不到写死的 #fff），
 *   以及同一语义出现多个相近色（品牌绿就有 #6f9e7f / #87b096 / #9dc0aa / #8a9c90 …）。
 *
 * 做法：
 *   只改 <style> 块内的「颜色声明」，按 CSS 属性上下文决定映射到哪个令牌：
 *     background / border  → 中性面与分隔线令牌
 *     color                → 文本层级 / 品牌前景令牌
 *   这样 #fff 出现在 background 时映射 --c-surface，
 *   出现在深色底上的 color 时映射 --c-on-brand，语义不会串。
 *
 *   未收录的色值一律不动，并打印出来，避免误伤预警等级色、图表序列色等语义色。
 *
 * 用法：
 *   node scripts/tokenize-colors.mjs            # 预演，只报告不写文件
 *   node scripts/tokenize-colors.mjs --write    # 实际写入
 *   node scripts/tokenize-colors.mjs --write --only src/views/system
 */

import { readFileSync, writeFileSync, readdirSync } from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(process.cwd())
const args = process.argv.slice(2)
const WRITE = args.includes('--write')
const onlyIdx = args.indexOf('--only')
const ONLY = onlyIdx >= 0 ? args[onlyIdx + 1] : null
const VERBOSE = args.includes('--verbose')

/* ══════════════════════════════════════════════════════════
   映射表：颜色 → 令牌
   key 为小写、统一 6 位或 3 位写法（保留原始写法做替换）
   value 为 { any, bg, fg } 三种上下文，缺省回落到 any
   ══════════════════════════════════════════════════════════ */
const BRAND = {
  '#6f9e7f': '--c-brand', // 主绿（出现 498 次）
  '#87b096': '--c-brand-hover', // 亮一档绿（142）
  '#5f8e70': '--c-brand-hover',
  '#638fc2': '--sys-blue', // 蓝色强调：收敛到系统蓝
  '#4a7a5e': '--c-brand-deep', // 深绿（205）
  '#385f49': '--c-brand-deeper', // 最深绿（80）
  '#3f5348': '--c-brand-deeper',
  '#276749': '--c-brand-ink',
  '#eaf1ec': '--c-brand-soft', // 浅绿底（148）
  '#e6efe9': '--c-brand-soft',
  '#dde8e0': '--c-brand-soft',
  '#f1f6f2': '--c-brand-mist', // 更浅绿底（72）
  '#eef3ef': '--c-brand-mist',
  '#f5faf7': '--c-brand-mist',
  '#ecf2ef': '--c-brand-mist',
  '#eef5f0': '--c-brand-mist',
  '#eef5f3': '--c-brand-mist',
  '#f0f5f3': '--c-brand-mist',
  '#f0f5f4': '--c-brand-mist',
  '#f1f5f3': '--c-brand-mist',
  '#f1f5f4': '--c-brand-mist',
  '#f4f7f6': '--c-brand-mist',
  '#f8fbfb': '--c-brand-mist',
  '#f0f8f6': '--c-brand-mist',
  '#ecf8f2': '--c-brand-mist',
  '#eef5f0': '--c-brand-mist',
  '#9dc0aa': '--c-brand-tint',
  '#b8d2c1': '--c-brand-tint',
  '#c8d8ce': '--c-brand-tint',
  '#d0dcd8': '--c-brand-tint',
  '#9bb5ac': '--c-text-3',
  '#54806a': '--c-brand-deep',
  '#3c6249': '--c-brand-deeper',
  '#41694f': '--nav-bg-flat',
  /* 蓝 / 青信息族 → 系统色 */
  '#1e3a5f': '--sys-indigo-deep',
  '#1e40af': '--sys-blue-deep',
  '#38bdf8': '--sys-cyan',
  '#4f7972': '--c-ink-2',
  '#2c5357': '--c-ink-1',
  '#4b5563': '--c-text-2',
  '#8f7a55': '--c-warning-ink',
  '#c05621': '--c-warning-ink',
  '#d18f2f': '--c-warning-strong',
  '#166534': '--c-success-strong',
  '#991b1b': '--c-danger-strong',
  '#e1e9e6': '--c-separator',
  '#dfe7e1': '--c-separator',
  '#dbe7e2': '--c-separator',
  '#d1d5db': '--c-border',
  '#fafbfc': '--c-surface-3',
  '#edf3f0': '--c-brand-mist',
  '#e8f5f3': '--c-brand-mist',
  '#fdf6e9': '--c-warning-bg',
  '#fff8ec': '--c-warning-bg',
  '#fffaf0': '--c-warning-bg',
  '#fef2f2': '--c-danger-bg',
  '#eff6ff': '--sys-blue-soft',
  '#e0ecff': '--sys-blue-soft',
  '#dbeafe': '--sys-blue-soft',
  '#e6fffa': '--sys-mint-soft',
}

const NEUTRAL = {
  '#f4f7f4': '--c-bg', // 全局底
  '#eaefeb': '--c-bg-deep',
  '#f8faf8': '--c-surface-2',
  '#fcfdfc': '--c-surface-3',
  '#f2f4f1': '--c-morandi-warm',
  '#e8ece8': '--c-morandi-clay',
  '#1c1c1e': '--c-text',
  '#76847a': '--c-text-2', // 灰绿文本（280）
  '#6b7f7c': '--c-text-3',
  '#8a9c90': '--c-text-3',
  '#9aab9f': '--c-text-4',
  '#6f807d': '--c-text-3',
  '#6f8482': '--c-text-3',
  '#c8d5d5': '--c-border',
  '#cbd5e0': '--c-border',
  '#8a968d': '--c-text-3',
  '#9aa79f': '--c-text-4',
  '#5c6b61': '--c-ink-2',
  '#5e7675': '--c-ink-2',
  '#5b7769': '--c-ink-2',
  '#e8efe9': '--c-separator',
  '#edf2f0': '--c-separator',
  '#f1f6f4': '--c-separator',
  '#dbe6e2': '--c-separator',
  '#d8e2df': '--c-separator',
  '#d9e8e2': '--c-separator',
  '#d8e0ec': '--c-separator',
  '#d3e2dc': '--c-separator',
  '#f8fbfa': '--c-surface-3',
  '#eef3f1': '--c-brand-mist',
  '#fdf1ee': '--c-danger-bg',
  '#eef4ff': '--sys-blue-soft',
  '#e6f5ee': '--c-success-bg',
  '#5d7f9e': '--c-text-3',
  '#0f172a': '--c-text',
  '#047857': '--c-success-strong',
  '#c07663': '--c-danger-strong',
  '#ecc4c4': '--c-separator',
  '#d88561': '--c-warning-strong',
  '#c4a86e': '--c-warning-bg',
  '#2c3d33': '--c-brand-deeper',
  '#ff453a': '--c-danger-strong',
  '#ff9f0a': '--c-warning-strong',
  '#ff9500': '--c-warning',
  '#ffd60a': '--c-warning',
  /* ── 长尾补充：按「近白底 / 深墨 / 同色系深浅配对」三类收敛 ── */
  '#f7faf9': '--c-surface-2',
  '#f5faf8': '--c-surface-2',
  '#f4f7f5': '--c-surface-2',
  '#f5f7f6': '--c-surface-2',
  '#f8fcfb': '--c-surface-2',
  '#f9fafb': '--c-surface-2',
  '#fbfefd': '--c-surface-2',
  '#f6fbf8': '--c-brand-mist',
  '#e8f2ee': '--c-brand-mist',
  '#e9f2ee': '--c-brand-mist',
  '#eef5f1': '--c-brand-mist',
  '#e6f4ee': '--c-brand-mist',
  '#edf3f0': '--c-brand-mist',
  '#f0f5f3': '--c-brand-mist',
  '#e4efe8': '--c-brand-mist',
  '#8ab79b': '--c-brand-hover',
  '#2b6c4d': '--c-brand-ink',
  '#5b9b76': '--c-success',
  '#3e8a6a': '--c-success-strong',
  '#4a5e5b': '--c-ink-2',
  '#355c5e': '--c-ink-1',
  '#35575b': '--c-ink-1',
  '#36585b': '--c-ink-1',
  '#315c5e': '--c-ink-1',
  '#21464a': '--c-ink-1',
  '#6a7d7a': '--c-ink-2',
  '#688886': '--c-ink-2',
  '#7a9e9f': '--c-ink-2',
  '#91a3a1': '--c-text-3',
  '#9aacab': '--c-text-4',
  '#97aead': '--c-text-3',
  '#cfd8d8': '--c-border',
  '#d9e6e1': '--c-separator',
  '#e5ece9': '--c-separator',
  '#e5e7eb': '--c-separator',
  '#e4ece8': '--c-separator',
  /* 状态系：底与字成对，一起跟随主题 */
  '#fbf4e3': '--c-warning-bg',
  '#fdf0e9': '--c-warning-bg',
  '#fef3ec': '--c-warning-bg',
  '#fdf0ec': '--c-warning-bg',
  '#fbe9e4': '--c-warning-bg',
  '#e7d3a3': '--c-warning-strong',
  '#cd927e': '--c-warning-strong',
  '#e87d5b': '--c-warning-strong',
  '#bda06a': '--c-warning-ink',
  '#b04a3a': '--c-danger-strong',
  '#9b4a2a': '--c-danger-strong',
  '#f4d2d2': '--c-danger-bg',
  '#f0d2c8': '--c-danger-bg',
  '#feb2b2': '--c-danger-soft',
  /* 蓝/靛信息系 */
  '#1a4a8a': '--sys-blue-deep',
  '#1c5b9a': '--sys-blue-deep',
  '#1e568f': '--sys-blue-deep',
  '#3d3f8a': '--sys-indigo-deep',
  '#eaf2f5': '--sys-blue-soft',
  '#eef1f6': '--sys-blue-soft',
  '#8e8e93': '--sys-gray',
  '#c2410c': '--c-warning-ink',
}

/* 暗色偏灰绿：都是「深色底上的正文/标题」，属墨水层级 */
const INK = {
  '#3a4f4a': '--c-ink-1',
  '#2f5550': '--c-ink-1',
  '#40564f': '--c-ink-1',
  '#3d5a55': '--c-ink-1',
  '#3f5a52': '--c-ink-1',
  '#4d6862': '--c-ink-2',
  '#33443f': '--c-ink-1',
  '#4f6663': '--c-ink-2',
  '#4f6769': '--c-ink-2',
  '#2a3f3c': '--c-ink-1',
  '#4c8b87': '--c-ink-2',
}

/* 白色：按上下文分派 */
const WHITE = {
  bg: '--c-surface',
  border: '--c-surface',
  fg: '--c-on-brand',
  any: '--c-surface',
}
/* 深色覆盖层（overlay / mask）：与上下文无关 */
const SCRIM = {
  '#1f3b3e': '--c-scrim',
  '#20343a': '--c-scrim',
  '#0b1410': '--c-scrim',
}

/* 语义状态色：同一含义收敛到一个令牌，暗色模式可整体提亮 */
const STATUS = {
  '#e06060': '--c-danger', // 自定义红（93）
  '#c7553b': '--c-danger-strong',
  '#c53030': '--c-danger-strong',
  '#d9534f': '--c-danger',
  '#cbaa76': '--c-warning', // 自定义琥珀（68）
  '#d9c040': '--c-warning-strong',
  '#9b761e': '--c-warning-ink',
  '#92400e': '--c-warning-ink',
  '#f59e0b': '--c-warning',
  '#a86058': '--c-danger-strong',
  /* Tailwind 状态色族（与品牌莫兰迪色并存的第二套语义色，统一到令牌） */
  '#ef4444': '--c-danger',
  '#dc2626': '--c-danger-strong',
  '#b91c1c': '--c-danger-strong',
  '#e53e3e': '--c-danger',
  '#f56565': '--c-danger',
  '#22c55e': '--c-success',
  '#16a34a': '--c-success',
  '#15803d': '--c-success-strong',
  '#38a169': '--c-success',
  '#10b981': '--c-success',
  '#f97316': '--c-warning-strong',
  '#f6ad55': '--c-warning',
  /* 状态底色 */
  '#fff5f5': '--c-danger-bg',
  '#fee2e2': '--c-danger-bg',
  '#fdeeed': '--c-danger-bg',
  '#fbecec': '--c-danger-bg',
  '#fef3c7': '--c-warning-bg',
  '#fffbeb': '--c-warning-bg',
}

const ANCHOR = {
  ...BRAND,
  ...NEUTRAL,
  ...INK,
  ...SCRIM,
  ...STATUS,
}

/* ══════════════════════════════════════════════════════════
   解析
   ══════════════════════════════════════════════════════════ */

/** 递归收集 .vue 文件（不依赖 fs.globSync，兼容较老的 Node） */
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

/** 取出所有 <style> 块范围；纯 .css 文件整篇视为一个 style 块 */
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

/** 判断某个下标是否落在 <style> 块内 */
function inStyle(blocks, idx) {
  return blocks.some((b) => idx >= b.bodyStart && idx < b.end)
}

/** 从声明中提取属性名 */
function propOf(decl) {
  const i = decl.indexOf(':')
  return i < 0 ? '' : decl.slice(0, i).trim().toLowerCase()
}

/** 属性 → 上下文 */
function contextOf(prop) {
  if (/^(background|background-color|background-image|fill|stroke)$/.test(prop)) return 'bg'
  if (/border|outline|box-shadow|divider|separator/.test(prop)) return 'border'
  if (/^(color|-webkit-text-fill-color|caret-color|text-shadow)$/.test(prop)) return 'fg'
  return 'any'
}

const HEX_RE = /#[0-9a-fA-F]{3,8}\b/g

/** 把 #abc 归一为 #aabbcc 以便查表 */
function normalize(hex) {
  let h = hex.toLowerCase()
  if (h.length === 4) h = '#' + h[1] + h[1] + h[2] + h[2] + h[3] + h[3]
  return h
}

function lookup(hex, ctx) {
  const key = hex.toLowerCase()
  if (key === '#fff' || key === '#ffffff') return WHITE[ctx] ?? WHITE.any
  if (key === '#000' || key === '#000000') {
    return ctx === 'fg' ? '--c-text' : '--c-overlay-ink'
  }
  // 带 alpha 的 8 位写法：#rrggbbaa —— 保留其透明度，改用 color-mix 表达
  if (/^#[0-9a-f]{8}$/.test(key)) return null
  return ANCHOR[key] ?? null
}

/* ══════════════════════════════════════════════════════════
   主流程
   ══════════════════════════════════════════════════════════ */

const stats = {
  files: 0,
  filesChanged: 0,
  replaced: 0,
  byToken: new Map(),
  unresolved: new Map(),
  unresolvedExamples: new Map(),
}

function bump(map, key, n = 1) {
  map.set(key, (map.get(key) ?? 0) + n)
}

for (const file of listFiles()) {
  const src = readFileSync(file, 'utf8')
  const isCss = file.endsWith('.css')
  const blocks = styleBlocks(src, isCss)
  if (!blocks.length) continue
  stats.files++

  /* ★ 根因防线：先按「逐行」标出所有令牌定义行，整行跳过。
     教训：早先的判断是 `prop.startsWith('--')`，而它是从「本行第一个冒号」
     推断的 —— 一旦某行已被改写成 `--c-brand: var(--c-brand);`，
     函数取到的 prop 是 `var(--c-brand)` 而不是 `--c-brand`，护栏被绕过，
     于是定义被反复自引用。现在用行首匹配直接定位定义行，与已有值无关。 */
  const isDefinitionLine = []
  {
    let lineStart = 0
    for (const line of src.split('\n')) {
      isDefinitionLine.push(/^\s*--[a-z0-9-]+\s*:/i.test(line))
      lineStart += line.length + 1
    }
  }
  const definitionOffsets = new Set()
  {
    let offset = 0
    src.split('\n').forEach((line, i) => {
      if (isDefinitionLine[i]) {
        for (let k = offset; k < offset + line.length; k++) definitionOffsets.add(k)
      }
      offset += line.length + 1
    })
  }

  let out = ''
  let cursor = 0
  let fileReplaced = 0
  let m

  HEX_RE.lastIndex = 0
  while ((m = HEX_RE.exec(src))) {
    const idx = m.index
    if (!inStyle(blocks, idx)) continue
    /* 令牌定义行整行跳过（含其注释里的色值），这是防止依赖环的关键 */
    if (definitionOffsets.has(idx)) continue

    // 向后找声明结束，反推属性名
    const semi = src.indexOf(';', idx)
    const declStart = Math.max(src.lastIndexOf(';', idx), src.lastIndexOf('{', idx)) + 1
    const decl = src.slice(declStart, semi < 0 ? idx + 40 : semi)
    const prop = propOf(decl)
    /* 双保险：声明内容若看起来是自定义属性定义，同样跳过 */
    if (prop.startsWith('--')) continue
    const ctx = contextOf(prop)

    const token = lookup(m[0], ctx)
    if (!token) {
      bump(stats.unresolved, normalize(m[0]))
      if (!stats.unresolvedExamples.has(normalize(m[0]))) {
        stats.unresolvedExamples.set(
          normalize(m[0]),
          `${path.relative(ROOT, file)}:${src.slice(0, idx).split('\n').length} (${prop || '?'})`,
        )
      }
      continue
    }

    out += src.slice(cursor, idx) + `var(${token})`
    cursor = idx + m[0].length
    fileReplaced++
    bump(stats.byToken, token)
  }
  out += src.slice(cursor)

  if (fileReplaced > 0) {
    stats.filesChanged++
    if (WRITE) {
      try {
        writeFileSync(file, out, 'utf8')
      } catch (e) {
        console.error(`写入失败 ${path.relative(ROOT, file)}: ${e.code}`)
        process.exitCode = 1
      }
    }
  }
  if (VERBOSE && fileReplaced) {
    console.log(`  ${path.relative(ROOT, file)}: ${fileReplaced}`)
  }
}

/* ══════════════════════════════════════════════════════════
   报告
   ══════════════════════════════════════════════════════════ */
console.log(`${WRITE ? '【已写入】' : '【预演·未写入】'} 扫描 ${stats.files} 个含 <style> 的文件`)
console.log(`可替换文件 ${stats.filesChanged} 个，颜色声明 ${[...stats.byToken.values()].reduce((a, b) => a + b, 0)} 处`)
console.log('\n—— 按令牌统计 ——')
for (const [tok, n] of [...stats.byToken].sort((a, b) => b[1] - a[1])) {
  console.log(`  ${String(n).padStart(5)}  var(${tok})`)
}
console.log(`\n—— 未收录（保持不动）共 ${stats.unresolved.size} 种 ——`)
const un = [...stats.unresolved].sort((a, b) => b[1] - a[1])
for (const [hex, n] of un.slice(0, 40)) {
  console.log(`  ${String(n).padStart(5)}  ${hex.padEnd(9)} ${stats.unresolvedExamples.get(hex) ?? ''}`)
}
if (un.length > 40) console.log(`  … 其余 ${un.length - 40} 种省略`)
