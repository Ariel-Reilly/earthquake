/**
 * 临时扫描器：解析后端 Controller 的 (path, method) → 返回类型，供前端列表方法对账。
 * 用法：node scripts/quality/_scan_backend_returns.mjs
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

function walk(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (e.name.endsWith('Controller.java')) out.push(p)
  }
  return out
}

const files = walk('backend/src/main/java')
const rows = []

for (const f of files) {
  const text = readFileSync(f, 'utf8')
  const lines = text.split(/\r?\n/)
  // 类级 RequestMapping
  let base = ''
  for (const l of lines) {
    const m = /@RequestMapping\(\s*"([^"]+)"/.exec(l)
    if (m) {
      base = m[1]
      break
    }
  }
  // 逐个 @GetMapping / @PostMapping 块，向后找方法签名的返回类型
  for (let i = 0; i < lines.length; i++) {
    const gm = /@(Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*)?(?:"([^"]*)")?/.exec(lines[i])
    if (!gm) continue
    const verb = gm[1].toUpperCase()
    const sub = gm[2] || ''
    // 向下最多 12 行找方法签名
    let ret = null
    for (let j = i + 1; j < Math.min(i + 13, lines.length); j++) {
      const sig = /(?:public|private|protected)\s+(?:static\s+)?([\w<>,\s\.\[\]?]+?)\s+(\w+)\s*\(/.exec(lines[j])
      if (sig) {
        ret = sig[1].trim().replace(/\s+/g, '')
        break
      }
    }
    const full = (base + sub).replace(/\/+$/, '') || '/'
    rows.push({ file: f.split(/[\\/]/).slice(-1)[0], verb, path: full, ret: ret || '?' })
  }
}

// 只看 GET + 列表/分页相关
const gets = rows.filter((r) => r.verb === 'GET')
console.log(`GET 端点总数：${gets.length}`)

const norm = (s) =>
  s
    .replace(/^\?.*$/, '')
    .replace(/\$\{[^}]*\}/g, '{x}')
    .replace(/\/+$/, '') || '/'

const want = [
  '/todo/confirmations',
  '/todo/approvals',
  '/todo/warnings',
  '/todo/tasks',
  '/approvals',
  '/sync/tasks',
  '/sync/history',
  '/ai/models',
  '/ai/models/training-jobs',
  '/ota/firmwares',
  '/ota/tasks',
  '/notice-templates',
  '/plans',
  '/plans/launches',
  '/backup/records',
  '/backup/strategies',
  '/backup/restores',
  '/account/login-history',
  '/account/sessions',
  '/account/audit',
  '/reports/templates',
  '/reports/history',
  '/catalog/assets',
  '/catalog/usage',
  '/events/{x}/sign',
  '/confirmation/queue',
  '/events',
  '/ai/device-health/all',
]

console.log('\n=== 前端候选 → 后端返回类型 ===')
for (const w of want) {
  const hits = gets.filter((r) => norm(r.path) === w || norm(r.path).endsWith(w))
  if (hits.length === 0) {
    console.log(`  ${w.padEnd(30)} [未匹配]`)
  } else {
    const types = [...new Set(hits.map((h) => h.ret))].join(' | ')
    console.log(`  ${w.padEnd(30)} ${types}   (${hits[0].file})`)
  }
}

console.log('\n=== 所有 GET 返回 List 形态的端点（裸数组）===')
for (const r of gets.filter((x) => /List</.test(x.ret))) {
  console.log(`  ${r.path.padEnd(40)} ${r.ret}  (${r.file})`)
}

console.log('\n=== 所有 GET 返回 PageResponse 的端点 ===')
for (const r of gets.filter((x) => /PageResponse/.test(x.ret))) {
  console.log(`  ${r.path.padEnd(40)} ${r.ret}  (${r.file})`)
}
