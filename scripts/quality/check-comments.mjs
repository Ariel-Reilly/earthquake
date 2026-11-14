#!/usr/bin/env node
/**
 * 注释门禁（跨平台，纯 Node，不依赖 grep）。
 *
 * 检查三类问题：
 *  1) 裸 TODO / FIXME：大小写敏感 \bTODO\b / \bFIXME\b。
 *     - 允许带主格式：TODO(owner, 2026-11-20, #123)
 *     - AI 服务 9 处为 KNOWN_LIMITATIONS L-01/L-04 的已知待实现点，走显式 allowlist，
 *       报告中单独列为「豁免」，不计入裸标记；阶段 2 补 owner 后应删除 allowlist。
 *  2) 注释掉的旧代码：`// public/function/const/...`（仅 .js/.vue/.java，降噪不误伤）。
 *     默认只报告；`--strict-legacy` 时 >0 即失败。
 *  3) allowlist 自检：每条 allowlist 必须命中 1 行；失效/重复命中即告警，`--selfcheck` 时失败。
 *     （防止 AI TODO 文案微调后「豁免悄悄失效」。）
 *
 * 用法：
 *  node scripts/quality/check-comments.mjs                 # 阻断模式（裸标记 > 0 失败）
 *  node scripts/quality/check-comments.mjs --warn          # 仅告警裸标记，退出 0
 *  node scripts/quality/check-comments.mjs --strict-legacy # 旧代码注释也阻断
 *  node scripts/quality/check-comments.mjs --selfcheck     # allowlist 失效即失败（供 CI）
 */
import { readdirSync, readFileSync } from 'node:fs'
import { join, extname, relative, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// QC_ROOT 仅用于自检脚本注入临时 fixture，生产/CI 不设置此变量。
const ROOT = process.env.QC_ROOT
  ? resolve(process.env.QC_ROOT)
  : join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const TARGETS = ['backend/src', 'frontend/src', 'ai-service/app']
const EXTS = new Set(['.java', '.js', '.vue', '.py'])
const SKIP_DIRS = new Set(['node_modules', '.venv', 'dist', 'target', '__pycache__', '.git'])
const WARN_MODE = process.argv.includes('--warn')
const STRICT_LEGACY = process.argv.includes('--strict-legacy')
const SELFCHECK = process.argv.includes('--selfcheck')

const TODO_RE = /\b(TODO|FIXME)\b/g
const OWNED_RE = /\b(?:TODO|FIXME)\(\s*[^,()]+,\s*\d{4}-\d{2}-\d{2}\s*,\s*#?[^,()]+\s*\)/
const LEGACY_RE = /^\s*\/\/\s*(public|private|protected|function|const|let|var|return|if|for|while|switch|import|export|class|new)\b/

// AI 9 处已知待实现点（L-01 drift/multimodal、L-04 sentinel）。阶段 2 补 owner 后删除本表。
// matched 用于 --selfcheck 自检：每条必须命中 1 行。
const ALLOWLIST = [
  { file: 'ai-service/app/routers/drift.py', snippet: 'TODO: 从数据库加载历史漂移记录' },
  { file: 'ai-service/app/routers/drift.py', snippet: 'TODO: 返回特征的详细分布信息' },
  { file: 'ai-service/app/routers/drift.py', snippet: 'TODO: 实际触发重训任务' },
  { file: 'ai-service/app/routers/drift.py', snippet: 'TODO: 从任务队列获取状态' },
  { file: 'ai-service/app/routers/multimodal.py', snippet: 'TODO: 按 event_id 从融合结果库加载真实记录' },
  { file: 'ai-service/app/routers/multimodal.py', snippet: 'TODO: 从融合结果库加载' },
  { file: 'ai-service/app/routers/multimodal.py', snippet: 'TODO: 从配置库加载' },
  { file: 'ai-service/app/routers/sentinel.py', snippet: 'TODO 标记在 _cluster_hotspots() 中' },
  { file: 'ai-service/app/routers/sentinel.py', snippet: 'TODO 真实场景下应替换为连通域分析' },
].map((e) => ({ ...e, matched: 0 }))

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

const bare = []
const allowlisted = []
const owned = []
const legacy = []
let total = 0

for (const t of TARGETS) {
  for (const abs of walk(join(ROOT, t))) {
    let text
    try {
      text = readFileSync(abs, 'utf8')
    } catch {
      continue
    }
    const rel = relative(ROOT, abs).replace(/\\/g, '/')
    text.split(/\r?\n/).forEach((line, i) => {
      TODO_RE.lastIndex = 0
      let m
      while ((m = TODO_RE.exec(line)) !== null) {
        total++
        const rec = { file: rel, line: i + 1, marker: m[1], text: line.trim() }
        const hit = ALLOWLIST.find((a) => a.file === rel && line.includes(a.snippet))
        if (hit) {
          hit.matched++
          allowlisted.push(rec)
        } else if (OWNED_RE.test(line)) {
          owned.push(rec)
        } else {
          bare.push(rec)
        }
      }
      if (LEGACY_RE.test(line)) legacy.push({ file: rel, line: i + 1, text: line.trim() })
    })
  }
}

const stale = ALLOWLIST.filter((a) => a.matched === 0)
const multi = ALLOWLIST.filter((a) => a.matched > 1)
const selfOk = stale.length === 0 && multi.length === 0

console.log(`[check-comments] 扫描：${TARGETS.join(', ')}`)
console.log(
  `[check-comments] TODO/FIXME 命中 ${total} 处；已带 owner ${owned.length} 处；allowlist 豁免 ${allowlisted.length} 处；裸标记 ${bare.length} 处`,
)
console.log(
  `[check-comments] allowlist 自检：${ALLOWLIST.length - stale.length}/${ALLOWLIST.length} 命中` +
    (multi.length ? `，${multi.length} 条重复命中` : '') +
    (selfOk ? '' : ' ⚠ 存在失效/重复条目'),
)

if (bare.length) {
  console.log('[check-comments] 裸标记（需补 owner/date/issue）：')
  for (const v of bare) console.log(`  ${v.file}:${v.line}  ${v.marker}  ${v.text}`)
}
for (const a of stale) {
  console.log(`  ⚠ allowlist 失效（未命中，文案可能已改或 TODO 已删）：${a.file} :: ${a.snippet}`)
}
for (const a of multi) {
  console.log(`  ⚠ allowlist 重复命中（片段过宽）：${a.file} :: ${a.snippet} (${a.matched} 次)`)
}

console.log(`[check-comments] 注释掉的旧代码：${legacy.length} 处（人工确认${STRICT_LEGACY ? '，本模式阻断' : '，默认不阻断'}）`)
for (const v of legacy.slice(0, 20)) console.log(`  ${v.file}:${v.line}  ${v.text}`)
if (legacy.length > 20) console.log(`  ... 其余 ${legacy.length - 20} 处省略`)

let failed = false
if (bare.length && !WARN_MODE) {
  console.error('[check-comments] 失败：存在裸 TODO/FIXME（缺 owner/date/issue）。阶段 1 可先用 --warn。')
  failed = true
}
if (legacy.length && STRICT_LEGACY) {
  console.error('[check-comments] 失败：存在注释掉的旧代码（--strict-legacy）。')
  failed = true
}
if (SELFCHECK && !selfOk) {
  console.error('[check-comments] 失败：allowlist 自检不通过（--selfcheck）。请更新失效条目或删除已完成项。')
  failed = true
}

if (failed) process.exit(1)
console.log(WARN_MODE ? '[check-comments] OK（warn 模式，未阻断）' : '[check-comments] OK')
