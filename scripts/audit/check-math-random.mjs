/**
 * Math.random() 门禁：业务逻辑禁止使用非确定性随机。
 *
 * 约定：`Math.random()` 仅允许用于动画与 mock 数据，业务逻辑必须使用确定性随机或真实数据源。
 * 白名单（相对 frontend/src，具体 glob 见下方 ALLOW_PATTERNS）：
 *   - 目标动画目录 components/animation 下的全部文件（新建动画组件统一放这里）
 *   - components 下任意层级的 Animated 前缀 .vue（存量动画组件，迁入 animation 后可删）
 *   - components/common/SkeletonTable.vue：骨架屏占位宽度，纯视觉、不产生业务数据
 *   - services/mock 下的全部文件：演示 / mock 数据
 *
 * 注释行里出现 Math.random() 字样不计入违规（文档说明历史改动时的正常写法）。
 *
 * 用法：
 *   node scripts/audit/check-math-random.mjs               # 全量扫描，违规退出码 1
 *   node scripts/audit/check-math-random.mjs --self-test   # 自检白名单与注释判定
 * 退出码：0 = 通过；1 = 发现违规 / 自检失败。
 */
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '../..')
const SRC = path.join(ROOT, 'frontend', 'src')
const SCAN_EXT = new Set(['.js', '.mjs', '.ts', '.vue'])
const CALL_RE = /Math\s*\.\s*random\s*\(/

const ALLOW_PATTERNS = [
  'components/animation/**',
  'components/**/Animated*.vue',
  'components/common/SkeletonTable.vue',
  'services/mock/**',
]

function toPosix(p) {
  return p.split(path.sep).join('/')
}

/**
 * 极简 glob 转换：双星号后跟斜杠表示「零个或多个路径段」；
 * 末尾双星号表示任意字符（含斜杠）；单个星号只匹配一个路径段内的字符。
 * 够本门禁用，不引第三方依赖。
 */
function globToRegExp(glob) {
  let out = ''
  for (let i = 0; i < glob.length; i++) {
    const ch = glob[i]
    if (ch === '*') {
      if (glob[i + 1] === '*') {
        if (glob[i + 2] === '/') {
          out += '(?:.*/)?'
          i += 2
        } else {
          out += '.*'
          i += 1
        }
      } else {
        out += '[^/]*'
      }
      continue
    }
    out += ch.replace(/[.+?^${}()|[\]\\]/g, '\\$&')
  }
  return new RegExp('^' + out + '$')
}
const ALLOW_RES = ALLOW_PATTERNS.map(globToRegExp)

function isAllowed(rel) {
  return ALLOW_RES.some((re) => re.test(rel))
}

/** 整行是注释时，其中的 Math.random() 只是说明文字，不算调用。 */
function isCommentLine(line) {
  const t = line.trimStart()
  return t.startsWith('//') || t.startsWith('*') || t.startsWith('/*') || t.startsWith('<!--')
}

function lineHasCall(line) {
  return !isCommentLine(line) && CALL_RE.test(line)
}

function walk(dir) {
  const out = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'dist') continue
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) out.push(...walk(full))
    else if (entry.isFile() && SCAN_EXT.has(path.extname(entry.name))) out.push(full)
  }
  return out
}

function selfTest() {
  const cases = [
    ['components/animation/particles.vue', true],
    ['components/animation/nested/sparks.vue', true],
    ['services/mock/ai.js', true],
    ['services/mock/nested/workflow.js', true],
    ['components/AnimatedLoginBackground.vue', true],
    ['components/common/SkeletonTable.vue', true],
    ['views/hazard/FieldInspectionPage.vue', false],
    ['services/_architecture/domain/deviceMapService.js', false],
    ['stores/auth.js', false],
  ]
  const commentCases = [
    ['// 原先的 Math.random() 已移除', false],
    [' * 不再用 Math.random 随机挑', false],
    ['  const x = Math.random() * 10', true],
    ['  const y = Math . random ( )', true],
  ]
  let failed = 0
  for (const [rel, expected] of cases) {
    const actual = isAllowed(rel)
    if (actual !== expected) {
      console.log(`[FAIL] whitelist ${rel}: expected ${expected}, got ${actual}`)
      failed++
    }
  }
  for (const [line, expected] of commentCases) {
    const actual = lineHasCall(line)
    if (actual !== expected) {
      console.log(`[FAIL] line detection "${line.trim()}": expected ${expected}, got ${actual}`)
      failed++
    }
  }
  console.log(`[self-test] ${cases.length + commentCases.length} cases, ${failed} failed`)
  return failed === 0 ? 0 : 1
}

function main() {
  const files = walk(SRC)
  const violations = []
  let allowedFiles = 0
  for (const file of files) {
    const rel = toPosix(path.relative(SRC, file))
    if (isAllowed(rel)) {
      allowedFiles++
      continue
    }
    const lines = readFileSync(file, 'utf8').split(/\r?\n/)
    lines.forEach((line, i) => {
      if (lineHasCall(line)) {
        violations.push({ rel, line: i + 1, text: line.trim().slice(0, 120) })
      }
    })
  }

  console.log('== Math.random() 门禁 ==')
  console.log(`扫描文件 ${files.length} 个；白名单文件 ${allowedFiles} 个；违规 ${violations.length} 处`)
  if (violations.length > 0) {
    for (const v of violations) {
      console.log(`[FAIL] frontend/src/${v.rel}:${v.line}  ${v.text}`)
    }
    console.log('判定：业务逻辑出现非确定性随机。请改用固定 seed 伪随机或真实接口数据；')
    console.log('      确属动画 / mock 的文件请放入白名单目录后再评估，不要直接加白名单绕过。')
    return 1
  }
  console.log('判定：PASS —— 动画 / mock 之外的业务代码无 Math.random() 调用。')
  return 0
}

const code = process.argv.includes('--self-test') ? selfTest() : main()
process.exit(code)
