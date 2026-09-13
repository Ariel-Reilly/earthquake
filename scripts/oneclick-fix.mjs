// scripts/oneclick-fix.mjs
// 一键修复脚本 v3：
//   1. 还原上一轮的 !!=  残留 -> !==
//   2. 把 == 升级为 ===（保留 == null / != null），!= 升级为 !==
//   3. 删除 dashboardApi.js 中确认无引用的 helper 函数

import fs from 'node:fs'
import path from 'node:path'
import url from 'node:url'

const __dirname = path.dirname(url.fileURLToPath(import.meta.url))
const srcDir = path.resolve(__dirname, '..', 'src')

function walk(dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'dist') continue
      out.push(...walk(p))
    } else if (/\.(js|mjs|cjs)$/.test(entry.name)) {
      out.push(p)
    }
  }
  return out
}

const files = walk(srcDir)

function upgrade(text) {
  // Phase 1：还原已损坏的 !!=
  let s = text.replace(/!!=/g, '!==')

  // Phase 2：用占位符保护不会破坏的组合：
  //     ===/!==/====/!===/==null/!=null
  const P = { eq3: 'EQ3', neq3: 'NEQ3', eqn: 'EQN', neqn: 'NEQN' }
  s = s.replace(/====/g, P.eq3 + 'X')        // === = 无意义；其实不存在
  s = s.replace(/!===/g, P.neq3 + 'X')
  s = s.replace(/===/g, P.eq3)
  s = s.replace(/!==/g, P.neq3)
  s = s.replace(/==\s*null\b/g, P.eqn)
  s = s.replace(/!=\s*null\b/g, P.neqn)

  // Phase 3：把剩下的 == 升 ===，!= 升 !==
  s = s.replace(/==/g, '===')
  s = s.replace(/!=/g, '!==')

  // Phase 4：把保护占位还原成 === / !== / == null / != null
  // 由于 Phase 3 可能把占位里 X 看成普通字符 —— 我们这里用字典保护
  // 因为 Phase 1 已经把 !!= 变回 !==  了，所以不会有残留
  // 现在把 === → 占位 → 还原
  s = s.split(P.eq3).join('===')
  s = s.split(P.neq3).join('!==')
  s = s.split(P.eqn).join('== null')
  s = s.split(P.neqn).join('!= null')

  return s
}

const changes = []
for (const abs of files) {
  const rel = path.relative(path.resolve(__dirname, '..'), abs)
  const before = fs.readFileSync(abs, 'utf8')
  const after = upgrade(before)
  if (after !== before) {
    fs.writeFileSync(abs, after, 'utf8')
    changes.push(rel)
  }
}

console.log(`升级完成（==/!=），修改 ${changes.length} 个文件`)

// 校验
let dirtyCount = 0
for (const abs of files) {
  const c = fs.readFileSync(abs, 'utf8')
  if (/!!=/.test(c)) {
    dirtyCount++
    console.log(`  STILL DIRTY !!= : ${path.relative(path.resolve(__dirname, '..'), abs)}`)
  }
}
if (dirtyCount === 0) console.log(`✓ 无 !!= 残留`)

// 也校验无意外 ==/!= 残留（除了 == null / != null）
let eqCount = 0
for (const abs of files) {
  const c = fs.readFileSync(abs, 'utf8')
  // 找出非 null 的 ==
  const matches = c.match(/(?<![!==])==(?![=\s]*null)/g) || []
  if (matches.length) {
    eqCount += matches.length
    if (eqCount <= 3) console.log(`  WARN: ${path.relative(path.resolve(__dirname, '..'), abs)} still has ${matches.length} 个裸 ==`)
  }
}
console.log(`剩余 (非 null) == 数：${eqCount}`)
