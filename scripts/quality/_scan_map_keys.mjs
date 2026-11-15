/**
 * 临时扫描器：提取后端 ApiResponse<Map> 的 GET 端点里 result.put("key", ...) 的键名。
 * 用于判定前端应读哪个字段（items / list / records / rows / sessions ...）。
 * 用法：node scripts/quality/_scan_map_keys.mjs
 */
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

function walk(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (e.name.endsWith('Controller.java')) out.push(p)
  }
  return out
}

const rows = []
for (const f of walk('backend/src/main/java')) {
  const text = readFileSync(f, 'utf8')
  const lines = text.split(/\r?\n/)
  let base = ''
  for (const l of lines) {
    const m = /@RequestMapping\(\s*"([^"]+)"/.exec(l)
    if (m) {
      base = m[1]
      break
    }
  }
  for (let i = 0; i < lines.length; i++) {
    const gm = /@GetMapping\(\s*(?:value\s*=\s*)?(?:"([^"]*)")?/.exec(lines[i])
    if (!gm) continue
    const sub = gm[1] || ''
    // 找方法体范围：签名后到下一个同级 @ 或方法结束
    let sigLine = -1
    let ret = ''
    for (let j = i + 1; j < Math.min(i + 14, lines.length); j++) {
      const sig = /(?:public|private|protected)\s+(?:static\s+)?([\w<>,\s\.\[\]?]+?)\s+(\w+)\s*\(/.exec(lines[j])
      if (sig) {
        ret = sig[1].trim().replace(/\s+/g, '')
        sigLine = j
        break
      }
    }
    if (sigLine < 0 || !/Map<String,\s*Object>/.test(ret)) continue

    // 收集方法体内的 put 键（粗略：直到下一个 @GetMapping/@PostMapping 或缩进回到 2 空格）
    const keys = []
    for (let j = sigLine; j < Math.min(sigLine + 90, lines.length); j++) {
      const pk = /\.put\(\s*"([^"]+)"/.exec(lines[j])
      if (pk) keys.push(pk[1])
      if (j > sigLine + 3 && /^ {2}@/.test(lines[j])) break
    }
    rows.push({
      file: f.split(/[\\/]/).slice(-1)[0],
      path: (base + sub).replace(/\/+$/, '') || '/',
      keys: [...new Set(keys)],
    })
  }
}

console.log(`ApiResponse<Map> 的 GET 端点：${rows.length}\n`)
const onlyListKey = rows.filter((r) => r.keys.length > 0)
for (const r of onlyListKey) {
  // 挑出疑似「列表承载键」
  const listKeys = r.keys.filter((k) => /items|list|records|rows|data|sessions|history|audit|templates|logs|entries/i.test(k))
  console.log(`${r.path.padEnd(38)} keys=[${r.keys.join(', ')}]`)
  if (listKeys.length) console.log(`${' '.repeat(38)} ↑ 列表键: ${listKeys.join(' / ')}`)
}

// 统计列表键分布
const counter = {}
for (const r of rows) {
  for (const k of r.keys) {
    if (/items|list|records|rows/i.test(k)) counter[k] = (counter[k] || 0) + 1
  }
}
console.log('\n=== 列表承载键分布 ===')
for (const [k, v] of Object.entries(counter).sort((a, b) => b[1] - a[1])) console.log(`  ${k}: ${v}`)
