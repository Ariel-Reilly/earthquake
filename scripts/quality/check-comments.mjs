#!/usr/bin/env node
/**
 * 注释门禁（跨平台，纯 Node，不依赖 grep）。
 *
 * 检查三类问题：
 *  1) 裸 TODO / FIXME：大小写敏感 \bTODO\b / \bFIXME\b，且**只在注释/docstring 文本里匹配**
 *     （代码标识符与字符串常量不算，例：测试 fixture `const TODO = {...}`）。
 *     - 允许带主格式：TODO(owner, 2026-11-20, #123)
 *     - AI 服务 9 处为 KNOWN_LIMITATIONS L-01/L-04 的已知待实现点，走显式 allowlist，
 *       报告中单独列为「豁免」，不计入裸标记；阶段 2 补 owner 后应删除 allowlist。
 *  2) 注释掉的旧代码：`// public/function/const/...`（仅 .js/.vue/.java，降噪不误伤）。
 *     默认只报告；`--strict-legacy` 时 >0 即失败。
 *  3) allowlist 自检：每条 allowlist 必须命中 1 行；失效/重复命中即告警，`--selfcheck` 时失败。
 *     （防止 AI TODO 文案微调后「豁免悄悄失效」。）
 *  4) 过期 TODO：带 owner 的 TODO 若 date < 今天，报告为「过期」；`--strict-stale` 时失败。
 *
 * 用法：
 *  node scripts/quality/check-comments.mjs                 # 阻断模式（裸标记 > 0 失败）
 *  node scripts/quality/check-comments.mjs --warn          # 仅告警裸标记，退出 0
 *  node scripts/quality/check-comments.mjs --strict-legacy # 旧代码注释也阻断
 *  node scripts/quality/check-comments.mjs --selfcheck     # allowlist 失效即失败（供 CI）
 *  node scripts/quality/check-comments.mjs --strict-stale  # 过期 TODO（date < 今天）也阻断
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
const STRICT_STALE = process.argv.includes('--strict-stale')

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

/**
 * 生成「整行 → 该行的注释文本」提取器（按文件独立维护块注释/docstring 状态）。
 *
 * 为什么必须做这一步：只对整行做 `\bTODO\b` 匹配，会把**代码标识符和字符串**当待办。
 * 真实踩过：组件测试里的 fixture 常量 `const TODO = {...}` 被判成 5 处裸标记，直接把 CI 打红。
 *
 * 覆盖范围（够用且无解析依赖）：
 *   - .java/.js/.vue：`//` 行注释、块注释（javadoc 的 `*` 续行因此也算）；
 *   - .py：`#` 行注释、`"""..."""` / `'''...'''` docstring（含单行与跨行）。
 *
 * 已知取舍：不做完整词法分析，字符串里出现 `//`、`#`、`"""` 时可能把其后内容当注释。
 * 这种情况最多造成「多报」，不会漏报真实注释里的 TODO。
 */
function makeCommentExtractor(ext) {
  if (ext === '.py') {
    let inDoc = false
    let delim = ''
    return (line) => {
      let out = ''
      let rest = line
      while (rest.length) {
        if (inDoc) {
          const end = rest.indexOf(delim)
          if (end === -1) {
            out += rest
            break
          }
          out += rest.slice(0, end)
          rest = rest.slice(end + 3)
          inDoc = false
          delim = ''
          continue
        }
        const hash = rest.indexOf('#')
        const dq = rest.indexOf('"""')
        const sq = rest.indexOf("'''")
        const triples = [dq, sq].filter((i) => i !== -1)
        const tri = triples.length ? Math.min(...triples) : -1
        if (hash !== -1 && (tri === -1 || hash < tri)) {
          out += rest.slice(hash + 1)
          break
        }
        if (tri === -1) break
        const d = rest.startsWith('"""', tri) ? '"""' : "'''"
        rest = rest.slice(tri + 3)
        const end = rest.indexOf(d)
        if (end === -1) {
          out += rest
          inDoc = true
          delim = d
          break
        }
        out += rest.slice(0, end)
        rest = rest.slice(end + 3)
      }
      return out
    }
  }
  let inBlock = false
  return (line) => {
    let out = ''
    let rest = line
    if (inBlock) {
      const end = rest.indexOf('*/')
      if (end === -1) return rest
      out += rest.slice(0, end)
      rest = rest.slice(end + 2)
      inBlock = false
    }
    const block = rest.indexOf('/*')
    const lineComment = rest.indexOf('//')
    if (block !== -1 && (lineComment === -1 || block < lineComment)) {
      const after = rest.slice(block + 2)
      const end = after.indexOf('*/')
      if (end === -1) {
        inBlock = true
        return out + after
      }
      return out + after.slice(0, end)
    }
    if (lineComment !== -1) return out + rest.slice(lineComment + 2)
    return out
  }
}

const bare = []
const allowlisted = []
const owned = []
const legacy = []
const overdue = []
let total = 0

const now = new Date()
const TODAY = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`

for (const t of TARGETS) {
  for (const abs of walk(join(ROOT, t))) {
    let text
    try {
      text = readFileSync(abs, 'utf8')
    } catch {
      continue
    }
    const rel = relative(ROOT, abs).replace(/\\/g, '/')
    const commentOf = makeCommentExtractor(extname(abs))
    text.split(/\r?\n/).forEach((line, i) => {
      // 只在注释/docstring 文本里找 TODO，避免代码标识符与字符串误报。
      const commentText = commentOf(line)
      TODO_RE.lastIndex = 0
      let m
      while ((m = TODO_RE.exec(commentText)) !== null) {
        total++
        const rec = { file: rel, line: i + 1, marker: m[1], text: line.trim() }
        const hit = ALLOWLIST.find((a) => a.file === rel && line.includes(a.snippet))
        if (hit) {
          hit.matched++
          allowlisted.push(rec)
        } else if (OWNED_RE.test(commentText)) {
          owned.push(rec)
          const dm = /(?:TODO|FIXME)\(\s*[^,()]+,\s*(\d{4})-(\d{2})-(\d{2})\s*,/.exec(commentText)
          if (dm) {
            const d = `${dm[1]}-${dm[2]}-${dm[3]}`
            if (d < TODAY) overdue.push({ file: rel, line: i + 1, date: d, text: line.trim() })
          }
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

console.log(
  `[check-comments] 过期 TODO（date < ${TODAY}）：${overdue.length} 处${STRICT_STALE ? '（本模式阻断）' : '（仅提醒）'}`,
)
for (const v of overdue.slice(0, 20)) console.log(`  ${v.file}:${v.line}  ${v.date}  ${v.text}`)
if (overdue.length > 20) console.log(`  ... 其余 ${overdue.length - 20} 处省略`)

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
if (overdue.length && STRICT_STALE) {
  console.error('[check-comments] 失败：存在过期 TODO（date < 今天，--strict-stale）。请更新日期或关闭。')
  failed = true
}

if (failed) process.exit(1)
console.log(WARN_MODE ? '[check-comments] OK（warn 模式，未阻断）' : '[check-comments] OK')
