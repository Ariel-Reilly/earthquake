#!/usr/bin/env node
/**
 * check-design-consistency.mjs —— 设计一致性体检（美化工作的验收标尺）
 *
 * 用法：
 *   node scripts/check-design-consistency.mjs            # 全站汇总
 *   node scripts/check-design-consistency.mjs --top 25   # 列出最差的 25 个文件
 *   node scripts/check-design-consistency.mjs --json     # 机器可读，便于 CI
 *
 * 判定口径：
 *   hex        <style> 块内写死的 #hex 颜色（应为 var(--token)）
 *   rgba       <style> 块内写死的 rgb()/rgba()/hsl()
 *   inline     <template> 里带颜色的 style= / :style=（其余如宽度属合理用法）
 *   radius     <style> 内写死的 border-radius: Npx（1~3px 发丝值豁免）
 *   shadow     <style> 内写死的 box-shadow（var() 与 none 豁免）
 *   noToken    有 <style> 却没有任何 var(--…)
 */

import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'

const ROOT = process.cwd()
const args = process.argv.slice(2)
const TOP = (() => {
  const i = args.indexOf('--top')
  return i >= 0 ? Number(args[i + 1]) : 0
})()
const AS_JSON = args.includes('--json')

function listVue() {
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
      } else if (e.name.endsWith('.vue')) {
        out.push(full)
      }
    }
  }
  walk(path.join(ROOT, 'src'))
  return out
}

/** 切成 style 段与 template 段 */
function regions(src) {
  const styles = []
  const re = /<style[^>]*>([\s\S]*?)<\/style>/gi
  let m
  while ((m = re.exec(src))) styles.push(m[1])
  const tmpl = []
  const rt = /<template[^>]*>([\s\S]*)<\/template>/i
  const mt = rt.exec(src)
  if (mt) tmpl.push(mt[1])
  return { styles: styles.join('\n'), template: tmpl.join('\n') }
}

/** 去掉 CSS 注释后再统计，否则「原值对照」注释里的 #hex 与 rgba() 会被误计入，
 *  导致指标虚高（本次迁移刻意保留了「等号 + 原 rgba 值」这类说明注释）。 */
function stripComments(css) {
  return css.replace(/\/\*[\s\S]*?\*\//g, '')
}

const rows = []
for (const file of listVue()) {
  const src = readFileSync(file, 'utf8')
  const { styles, template } = regions(src)
  const cleanStyles = stripComments(styles)
  const hex = (cleanStyles.match(/#[0-9a-fA-F]{3,8}\b/g) ?? []).length
  const rgba = (cleanStyles.match(/\b(?:rgb|hsl)a?\(/g) ?? []).length
  const inline = (template.match(/[:@]?style\s*=\s*["'{][^"'}]*\b(?:color|background|border|fill|stroke)/g) ?? []).length
  const radius = (cleanStyles.match(/border-radius:\s*(?!var\()\d+px/g) ?? []).filter((s) => {
    const n = Number(/(\d+)px/.exec(s)[1])
    return n > 3
  }).length
  const shadow = (cleanStyles.match(/box-shadow:\s*(?!var\()(?!none)[^;]+;/g) ?? []).length
  const hasStyle = /<style/.test(src)
  const noToken = hasStyle && !styles.includes('var(--')
  rows.push({ file: path.relative(ROOT, file), hex, rgba, inline, radius, shadow, noToken })
}

const sum = (k) => rows.reduce((a, r) => a + r[k], 0)
const summary = {
  files: rows.length,
  hex: sum('hex'),
  rgba: sum('rgba'),
  inline: sum('inline'),
  radius: sum('radius'),
  shadow: sum('shadow'),
  noToken: rows.filter((r) => r.noToken).length,
}

if (AS_JSON) {
  console.log(JSON.stringify({ summary, worst: rows.sort((a, b) => b.hex + b.rgba - (a.hex + a.rgba)).slice(0, TOP || 20) }, null, 2))
} else {
  console.log('═══ 设计一致性体检 ═══')
  console.log(`  扫描 .vue 文件          ${summary.files}`)
  console.log(`  写死 hex 色值           ${summary.hex}`)
  console.log(`  写死 rgba/hsl           ${summary.rgba}`)
  console.log(`  模板内联颜色样式        ${summary.inline}`)
  console.log(`  写死圆角(>3px)          ${summary.radius}`)
  console.log(`  写死阴影                ${summary.shadow}`)
  console.log(`  有 <style> 但无 token   ${summary.noToken}`)
  if (TOP) {
    console.log(`\n─── 最需处理的 ${TOP} 个文件 ───`)
    const worst = [...rows].sort((a, b) => (b.hex + b.rgba + b.inline * 2) - (a.hex + a.rgba + a.inline * 2))
    for (const r of worst.slice(0, TOP)) {
      console.log(
        `  ${String(r.hex).padStart(4)}hex ${String(r.rgba).padStart(3)}rgba ${String(r.inline).padStart(3)}inline ${String(r.radius).padStart(3)}r ${String(r.shadow).padStart(3)}sh  ${r.file}`,
      )
    }
  }
}
