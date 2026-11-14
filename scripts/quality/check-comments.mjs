#!/usr/bin/env node
/**
 * 注释门禁：扫描裸 TODO / FIXME（大小写敏感 \bTODO\b / \bFIXME\b）。
 *
 * 规则：
 *  - 允许带主格式：TODO(owner, 2026-11-20, #123): ...
 *  - 裸 TODO / FIXME 视为违规（缺 owner/date/issue 任一）
 *  - 默认：有裸标记即非零退出（供 CI 阻断）；--warn 只告警不阻断（阶段 1 过渡用）
 *
 * 用法：
 *  node scripts/quality/check-comments.mjs          # 阻断模式
 *  node scripts/quality/check-comments.mjs --warn   # 仅告警
 *
 * 跨平台：纯 Node 实现，不依赖 grep（Windows 可用）。
 */
import { readdirSync, readFileSync } from 'node:fs'
import { join, extname, relative, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const TARGETS = ['backend/src', 'frontend/src', 'ai-service/app']
const EXTS = new Set(['.java', '.js', '.vue', '.py'])
const SKIP_DIRS = new Set(['node_modules', '.venv', 'dist', 'target', '__pycache__', '.git'])
const WARN_MODE = process.argv.includes('--warn')

const TODO_RE = /\b(TODO|FIXME)\b/g
// 允许的带主格式：TODO(owner, 2026-11-20, #123)
const OWNED_RE = /\b(?:TODO|FIXME)\(\s*[^,()]+,\s*\d{4}-\d{2}-\d{2}\s*,\s*#?[^,()]+\s*\)/

function walk(dir, out = []) {
  let entries
  try {
    entries = readdirSync(dir, { withFileTypes: true })
  } catch {
    return out
  }
  for (const e of entries) {
    if (e.name.startsWith('.') || SKIP_DIRS.has(e.name)) continue
    const p = join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (EXTS.has(extname(e.name))) out.push(p)
  }
  return out
}

const violations = []
let total = 0

for (const t of TARGETS) {
  for (const file of walk(join(ROOT, t))) {
    let text
    try {
      text = readFileSync(file, 'utf8')
    } catch {
      continue
    }
    text.split(/\r?\n/).forEach((line, i) => {
      TODO_RE.lastIndex = 0
      let m
      while ((m = TODO_RE.exec(line)) !== null) {
        total++
        if (!OWNED_RE.test(line)) {
          violations.push({
            file: relative(ROOT, file).replace(/\\/g, '/'),
            line: i + 1,
            marker: m[1],
            text: line.trim(),
          })
        }
      }
    })
  }
}

console.log(`[check-comments] 扫描：${TARGETS.join(', ')}`)
console.log(`[check-comments] TODO/FIXME 命中 ${total} 处；裸标记 ${violations.length} 处`)

if (violations.length) {
  for (const v of violations) {
    console.log(`  ${v.file}:${v.line}  ${v.marker}  ${v.text}`)
  }
}

if (violations.length && !WARN_MODE) {
  console.error('[check-comments] 失败：存在裸 TODO/FIXME（缺 owner/date/issue）。阶段 1 可先用 --warn。')
  process.exit(1)
}

console.log('[check-comments] OK')
