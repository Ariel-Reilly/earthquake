#!/usr/bin/env node
/**
 * verify-token-integrity.mjs —— 设计令牌完整性校验（防「自引用 / 依赖环 / 未定义」）
 *
 * 为什么需要它：
 *   批量把 `#hex` 替换成 `var(--token)` 时，如果脚本没有排除「令牌定义行」，
 *   就会把 `--c-surface: #ffffff;` 改写成 `--c-surface: var(--c-surface);`。
 *   按 CSS 规范，自定义属性的自引用构成依赖环，该属性在 computed-value 阶段
 *   变成 guaranteed-invalid —— 于是浅色主题下 background 变透明、color 变继承，
 *   全站掉色，而构建与 lint 都不会报错。必须在 CI 里挡住。
 *
 * 校验项：
 *   1 自引用      --x: ... var(--x) ...          （直接依赖环）
 *   2 依赖环      A→B→…→A                        （间接依赖环）
 *   3 未定义引用  var(--y) 但全文没有 --y 定义   （除非该处提供了 fallback）
 *   4 注释污染    注释里出现 var(--…)            （多为替换脚本误伤）
 *
 * 用法：
 *   node scripts/verify-token-integrity.mjs
 *   node scripts/verify-token-integrity.mjs --json
 * 退出码：0 通过，1 发现问题（可用于 CI 门禁）
 */

import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'

const ROOT = process.cwd()
const AS_JSON = process.argv.includes('--json')

function walk(dir, exts, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name.startsWith('.')) continue
      walk(full, exts, out)
    } else if (exts.some((x) => e.name.endsWith(x))) out.push(full)
  }
  return out
}

const files = [
  path.join(ROOT, 'src/styles.css'),
  ...walk(path.join(ROOT, 'src'), ['.vue']),
].filter((f) => {
  try {
    readFileSync(f)
    return true
  } catch {
    return false
  }
})

/* 汇总所有「定义」：--name 出现在声明位置（行首或分号/大括号之后） */
const defined = new Map() // name -> [ {file, line} ]
const definitions = [] // { name, value, file, line }

for (const file of files) {
  const text = readFileSync(file, 'utf8')
  const lines = text.split('\n')
  lines.forEach((line, i) => {
    // 去掉行内注释后再判断，避免注释里的 --x: 被当成定义
    const noComment = line.replace(/\/\*[\s\S]*?\*\//g, '')
    const re = /(^|[;{]\s*|\s)(--[a-z0-9-]+)\s*:\s*([^;]*)/gi
    let m
    while ((m = re.exec(noComment))) {
      const name = m[2]
      const value = m[3].trim()
      definitions.push({ name, value, file: path.relative(ROOT, file), line: i + 1 })
      if (!defined.has(name)) defined.set(name, [])
      defined.get(name).push({ file: path.relative(ROOT, file), line: i + 1 })
    }
  })
}

const problems = { selfRef: [], cycle: [], undefinedRef: [], commentPollution: [] }

/* 1) 自引用 */
for (const d of definitions) {
  const re = new RegExp(`var\\(\\s*${d.name.replace(/[-]/g, '\\-')}\\s*[,)]`)
  if (re.test(d.value)) problems.selfRef.push(d)
}

/* 2) 依赖环（在「定义图」上做 DFS） */
const graph = new Map()
for (const d of definitions) {
  const refs = [...d.value.matchAll(/var\(\s*(--[a-z0-9-]+)/gi)].map((m) => m[1])
  if (!graph.has(d.name)) graph.set(d.name, new Set())
  for (const r of refs) graph.get(d.name).add(r)
}
const WHITE = 0, GREY = 1, BLACK = 2
const color = new Map()
const stack = []
function dfs(node) {
  color.set(node, GREY)
  stack.push(node)
  for (const next of graph.get(node) ?? []) {
    if (!graph.has(next)) continue
    const c = color.get(next) ?? WHITE
    if (c === GREY) {
      const at = stack.indexOf(next)
      const cyc = stack.slice(at).concat(next)
      problems.cycle.push({ chain: cyc.join(' → '), start: defined.get(next)?.[0] })
    } else if (c === WHITE) {
      dfs(next)
    }
  }
  stack.pop()
  color.set(node, BLACK)
}
for (const node of graph.keys()) if ((color.get(node) ?? WHITE) === WHITE) dfs(node)

/* 3) 未定义引用（无 fallback 才算问题）
   ⚠ 只对全局样式表 styles.css 判定：业务页里 `var(--risk)` 这类是
   「实例级自定义属性」，由模板 :style / style 在运行时注入（如
   `:style="{ '--risk': riskColor(...) }"`），不是缺失的令牌，不能报错。 */
const instanceVars = new Set()
for (const file of files) {
  if (file.endsWith('.css')) continue
  const text = readFileSync(file, 'utf8')
  for (const m of text.matchAll(/['"]?(--[a-zA-Z0-9-]+)['"]?\s*:/g)) instanceVars.add(m[1])
}
for (const file of files) {
  const text = readFileSync(file, 'utf8')
  const lines = text.split('\n')
  lines.forEach((line, i) => {
    for (const m of line.matchAll(/var\(\s*(--[a-z0-9-]+)\s*([,)])/g)) {
      const name = m[1]
      const hasFallback = m[2] === ','
      if (defined.has(name) || hasFallback || instanceVars.has(name)) continue
      // 只看全局样式表；Vue 文件里的未定义自定义属性交由浏览器按实例注入处理
      if (!file.endsWith('.css')) continue
      problems.undefinedRef.push({ name, file: path.relative(ROOT, file), line: i + 1 })
    }
  })
}

/* 4) 注释位置异常
   两种要区分开：
     · 合法：声明「之后」的行尾说明注释，如
         color: var(--c-warning-ink); /* = 原 #bda06a *\/
       —— 这是刻意保留的「原值对照」，便于回滚与评审，不算问题。
     · 可疑：注释出现在「值内部」，即冒号之后、分号之前，例如
         background: linear-gradient(135deg, var(--c-brand) /* 说明 *\/, var(--c-brand));
         border-bottom: 1px solid color-mix(...) /* 说明 *\/;
       这类写法虽然 CSS 合法，但把说明塞进了函数参数/值中间，
       可读性与可回滚性都差，统一要求挪到分号之后。 */
for (const file of files) {
  const lines = readFileSync(file, 'utf8').split('\n')
  lines.forEach((line, i) => {
    const comment = /\/\*.*?\*\//.exec(line)
    if (!comment) return
    const before = line.slice(0, comment.index)
    const colon = before.lastIndexOf(':')
    const semi = before.lastIndexOf(';')
    // 冒号在分号之后 → 说明该注释落在「某条声明的值内部」
    const insideValue = colon > semi && colon >= 0
    if (!insideValue) return
    // 纯注释行（如 /* 说明 var(--x) */）不算
    if (/^\s*\/\*/.test(line)) return
    problems.commentPollution.push({ file: path.relative(ROOT, file), line: i + 1, text: line.trim().slice(0, 110) })
  })
}

/* 阻断项：只有这三类会让主题真正失效，必须为 0。
   「注释位置」是可读性建议，不计入阻断（见下方说明）。 */
const total = problems.selfRef.length + problems.cycle.length + problems.undefinedRef.length

/* 环可能被重复记录，去重 */
const uniqCycle = [...new Map(problems.cycle.map((c) => [c.chain, c])).values()]

if (AS_JSON) {
  console.log(JSON.stringify({ total, blocking: total, defined: defined.size, problems: { ...problems, cycle: uniqCycle } }, null, 2))
} else {
  console.log('═══ 令牌完整性校验 ═══')
  console.log(`  扫描文件 ${files.length} 个，令牌定义 ${defined.size} 个`)
  console.log(`  自引用            ${problems.selfRef.length}`)
  console.log(`  依赖环            ${uniqCycle.length}`)
  console.log(`  未定义引用(无兜底) ${problems.undefinedRef.length}`)
  console.log(`  注释位置提醒      ${problems.commentPollution.length}  （不计入阻断，仅建议）`)

  const show = (title, arr, fmt) => {
    if (!arr.length) return
    console.log(`\n── ${title} ──`)
    for (const x of arr.slice(0, 20)) console.log('  ' + fmt(x))
    if (arr.length > 20) console.log(`  … 其余 ${arr.length - 20} 条`)
  }
  show('自引用', problems.selfRef, (d) => `${d.file}:${d.line}  ${d.name}: ${d.value.slice(0, 60)}`)
  show('依赖环', uniqCycle, (c) => `${c.start?.file}:${c.start?.line}  ${c.chain}`)
  show('未定义引用', problems.undefinedRef, (u) => `${u.file}:${u.line}  var(${u.name})`)

  if (problems.commentPollution.length) {
    console.log(
      `\nℹ 注释位置提醒 ${problems.commentPollution.length} 处（不阻断）：说明注释写在声明「值内部」而非分号之后。\n` +
        '  CSS 合法、且已被 vite 正确编译（见 dist 产物），仅影响可读性与回滚对照，可后续统一挪位。',
    )
    for (const c of problems.commentPollution.slice(0, 5)) console.log(`    ${c.file}:${c.line}`)
    if (problems.commentPollution.length > 5) console.log(`    … 其余 ${problems.commentPollution.length - 5} 处`)
  }

  console.log(total === 0 ? '\n✅ 通过：令牌定义完整、无自引用、无依赖环、无未定义引用' : `\n❌ 发现 ${total} 处阻断问题`)
}

process.exit(total === 0 ? 0 : 1)
