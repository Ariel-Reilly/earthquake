/**
 * 临时扫描器：解析 dashboardApi 对象，逐个方法判定「返回形态」。
 * 输出 Markdown 表格素材，供 docs/api/API_INVENTORY.md 使用。
 * 用法：node scripts/quality/_scan_api_shapes.mjs
 */
import { readFileSync } from 'node:fs'

const SRC = 'frontend/src/services/dashboardApi.js'
const text = readFileSync(SRC, 'utf8')
const lines = text.split(/\r?\n/)

// 定位 dashboardApi 对象范围
const startIdx = lines.findIndex((l) => /^export const dashboardApi = \{/.test(l))
if (startIdx < 0) {
  console.error('未找到 dashboardApi 定义')
  process.exit(1)
}
let endIdx = lines.length - 1
for (let i = startIdx + 1; i < lines.length; i++) {
  if (/^\}/.test(lines[i])) {
    endIdx = i
    break
  }
}

// 以「两空格缩进 + name: async / name: (」切分方法块
const methods = []
let cur = null
for (let i = startIdx + 1; i < endIdx; i++) {
  const line = lines[i]
  const m = /^ {2}([A-Za-z0-9_]+)\s*:\s*(async\s*)?(\(|function)/.exec(line)
  if (m) {
    if (cur) methods.push(cur)
    cur = { name: m[1], start: i + 1, body: [] }
  } else if (cur) {
    cur.body.push(line)
  }
}
if (cur) methods.push(cur)

const LIST_HINT =
  /(list|all|search|query|history|records|ranking|trend|feed|options|queue|jobs|assets|sessions|logs|notices|models|devices|templates|applications|positions|tasks)/i

function classify(mt) {
  const body = mt.body.join('\n')
  const reqs = [...body.matchAll(/request\(\s*[`'"]([^`'"]*)/g)].map((x) => x[1])
  const path = reqs[0] || ''
  const isWriteReq = /method:\s*['"](POST|PUT|PATCH|DELETE)['"]/.test(body)
  const tags = []

  if (/normalizePaged\(/.test(body)) tags.push('normalizePaged→{records,total,page,size,pages}')
  if (/normalizeList\(|normalizePage\(/.test(body)) tags.push('normalizeList/Page')
  if (/return\s*\{[^}]*\blist\s*:/.test(body)) tags.push('{list,...} 包装')
  if (/\bdata\.list\s*\?\?|\bdata\?\.list\s*\?\?/.test(body)) tags.push('读 data.list')
  if (/\bdata\.records\s*\?\?|\bdata\?\.records\s*\?\?/.test(body)) tags.push('读 data.records')
  if (/\bdata\.items\s*\?\?|\bdata\?\.items\s*\?\?/.test(body)) tags.push('读 data.items')
  if (/Array\.isArray\(data\)/.test(body)) tags.push('Array.isArray(data) 猜形态')
  if (/return\s+(await\s+)?request\(/.test(body) && tags.length === 0) tags.push('裸透传 request')

  // 形态归类
  let shape = 'unknown'
  if (isWriteReq) shape = 'write'
  else if (tags.some((t) => t.startsWith('normalizePaged'))) shape = 'paged(records)'
  else if (tags.some((t) => t.startsWith('{list'))) shape = 'wrapped-list'
  else if (tags.some((t) => t.startsWith('normalizeList'))) shape = 'list'
  else if (tags.some((t) => /猜形态|读 data\./.test(t))) shape = 'guess'
  else if (tags.includes('裸透传 request'))
    shape = /^\/(hazards|warnings|devices|events)/.test(path) ? 'raw-unknown' : 'raw-pass'
  else if (/return\s+\w+\.map\(|return\s+Promise\.all|\breturn\s+rows\b/.test(body)) shape = 'list'

  return { shape, path, tags, isWrite: isWriteReq }
}

const rows = methods.map((mt) => ({ name: mt.name, line: mt.start, ...classify(mt) }))

// 汇总
const byShape = {}
for (const r of rows) byShape[r.shape] = (byShape[r.shape] || 0) + 1

console.log(`解析方法总数：${rows.length}`)
console.log('=== 形态分布 ===')
for (const [k, v] of Object.entries(byShape).sort((a, b) => b[1] - a[1]))
  console.log(`  ${k.padEnd(16)} ${v}`)

console.log('\n=== 疑似「列表但裸透传」候选（需人工确认后端形态）===')
const cand = rows.filter(
  (r) => r.shape === 'raw-unknown' || (r.shape === 'raw-pass' && LIST_HINT.test(r.name)),
)
for (const r of cand) console.log(`  L${String(r.line).padStart(4)} ${r.name.padEnd(34)} ${r.path}`)

console.log('\n=== guess 形态（页面在猜）===')
for (const r of rows.filter((x) => x.shape === 'guess'))
  console.log(`  L${String(r.line).padStart(4)} ${r.name.padEnd(34)} ${r.tags.join(' | ')}`)

console.log('\n=== wrapped-list ===')
for (const r of rows.filter((x) => x.shape === 'wrapped-list'))
  console.log(`  L${String(r.line).padStart(4)} ${r.name.padEnd(34)} ${r.path}`)

console.log('\n=== 输出全量 TSV 供文档使用 ===')
for (const r of rows) {
  console.log(`${r.name}\t${r.shape}\t${r.path}\t${r.tags.join('; ')}`)
}
