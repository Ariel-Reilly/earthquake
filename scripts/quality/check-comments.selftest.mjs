#!/usr/bin/env node
/**
 * check-comments.mjs 的自检回归（覆盖异常场景，防止「自检本身失效」）。
 *
 * 场景：
 *  A. allowlist 文件缺失 → --selfcheck 退出 1，并报「allowlist 失效」
 *  B. 片段重复命中     → --selfcheck 退出 1，并报「重复命中」
 *  C. 干净 fixture（7 条各命中一次）→ --warn --selfcheck 退出 0，输出 7/7；严格模式亦退出 0
 *  D. 真实仓库：--warn --selfcheck 退出 0；严格模式退出码与「裸标记数/自检」一致（状态无关）
 *  E. 过期 TODO：昨天 → 默认仅提醒(退出 0) / --strict-stale 退出 1；未来日期不报过期
 *
 * 用法：node scripts/quality/check-comments.selftest.mjs
 * 退出码：0 = 全部通过，1 = 有失败。
 */
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const SCRIPT = join(HERE, 'check-comments.mjs')
const REAL_ROOT = join(HERE, '..', '..')

function run(args, root) {
  return spawnSync(process.execPath, [SCRIPT, ...args], {
    encoding: 'utf8',
    env: { ...process.env, QC_ROOT: root },
  })
}

let pass = 0
let fail = 0
function check(name, cond, detail = '') {
  if (cond) {
    console.log(`PASS  ${name}`)
    pass++
  } else {
    console.log(`FAIL  ${name}${detail ? ` — ${detail}` : ''}`)
    fail++
  }
}
const out = (r) => `${r.stdout || ''}${r.stderr || ''}`

const base = mkdtempSync(join(tmpdir(), 'qc-selftest-'))
try {
  // A. allowlist 文件缺失
  const A = join(base, 'a')
  for (const d of ['backend/src', 'frontend/src', 'ai-service/app']) {
    mkdirSync(join(A, d), { recursive: true })
  }
  const ra = run(['--selfcheck'], A)
  check('A 缺失文件 → 退出 1', ra.status === 1, `status=${ra.status}`)
  check('A 缺失文件 → 报「allowlist 失效」', /allowlist 失效/.test(out(ra)))

  // B. 片段重复命中
  const B = join(base, 'b')
  mkdirSync(join(B, 'ai-service/app/routers'), { recursive: true })
  writeFileSync(
    join(B, 'ai-service/app/routers/drift.py'),
    '# TODO: 实际触发重训任务\n# TODO: 实际触发重训任务\n',
  )
  const rb = run(['--selfcheck'], B)
  check('B 重复命中 → 退出 1', rb.status === 1, `status=${rb.status}`)
  check('B 重复命中 → 提示「重复命中」', /重复命中/.test(out(rb)))

  // C. 干净 fixture（7 条各命中一次，与当前 allowlist 一致）
  const C = join(base, 'c')
  mkdirSync(join(C, 'ai-service/app/routers'), { recursive: true })
  writeFileSync(
    join(C, 'ai-service/app/routers/drift.py'),
    [
      '# TODO: 实际触发重训任务',
      '# TODO: 从任务队列获取状态',
    ].join('\n') + '\n',
  )
  writeFileSync(
    join(C, 'ai-service/app/routers/multimodal.py'),
    [
      '"""',
      'TODO: 按 event_id 从融合结果库加载真实记录',
      '"""',
      '"""x TODO: 从融合结果库加载 y"""',
      '"""x TODO: 从配置库加载 y"""',
    ].join('\n') + '\n',
  )
  writeFileSync(
    join(C, 'ai-service/app/routers/sentinel.py'),
    [
      '"""x TODO 标记在 _cluster_hotspots() 中 y"""',
      '"""x TODO 真实场景下应替换为连通域分析 y"""',
    ].join('\n') + '\n',
  )
  const rc = run(['--warn', '--selfcheck'], C)
  check('C 干净 fixture → 退出 0', rc.status === 0, `status=${rc.status}`)
  check('C 干净 fixture → 7/7 命中', /7\/7 命中/.test(rc.stdout))
  const rcStrict = run(['--selfcheck'], C)
  check('C 干净 fixture 严格模式 → 0（模拟阶段1清零后）', rcStrict.status === 0, `status=${rcStrict.status}`)

  // D. 真实仓库（状态无关：不硬编码「必须失败」）
  const rd1 = run(['--warn', '--selfcheck'], REAL_ROOT)
  check('D 真实仓库 --warn --selfcheck → 0', rd1.status === 0, `status=${rd1.status}`)
  const rd2 = run(['--selfcheck'], REAL_ROOT)
  const bareMatch = /裸标记 (\d+) 处/.exec(rd2.stdout || '')
  const bareCount = Number(bareMatch ? bareMatch[1] : 0)
  const selfOk = !/存在失效\/重复条目/.test(out(rd2))
  const expectFail = bareCount > 0 || !selfOk
  check(
    'D 真实仓库严格模式退出码与「裸标记/自检」一致',
    rd2.status === (expectFail ? 1 : 0),
    `status=${rd2.status} bare=${bareCount} selfOk=${selfOk}`,
  )

  // E. 过期 TODO：昨天应报过期（默认仅提醒 / --strict-stale 阻断），未来日期不报
  const pad = (n) => String(n).padStart(2, '0')
  const ymd = (dt) => `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`
  const yest = new Date()
  yest.setDate(yest.getDate() - 1)
  const fut = new Date()
  fut.setDate(fut.getDate() + 30)

  const E1 = join(base, 'e1')
  mkdirSync(join(E1, 'backend/src'), { recursive: true })
  writeFileSync(
    join(E1, 'backend/src/Expired.java'),
    `// TODO(backend-team, ${ymd(yest)}, #DELIVERY-C9): 过期测试\n`,
  )
  const re1 = run([], E1)
  check('E1 过期 TODO 默认 → 退出 0', re1.status === 0, `status=${re1.status}`)
  check('E1 过期 TODO 默认 → 报「过期 1 处」', /过期 TODO（date < [^）]+）：1 处/.test(out(re1)))
  const re2 = run(['--strict-stale'], E1)
  check('E1 过期 TODO --strict-stale → 退出 1', re2.status === 1, `status=${re2.status}`)

  const E2 = join(base, 'e2')
  mkdirSync(join(E2, 'backend/src'), { recursive: true })
  writeFileSync(
    join(E2, 'backend/src/Future.java'),
    `// TODO(backend-team, ${ymd(fut)}, #DELIVERY-C9): 未来测试\n`,
  )
  const re3 = run([], E2)
  check('E2 未来日期 → 不报过期（0 处）', /过期 TODO（date < [^）]+）：0 处/.test(out(re3)))

  // F. 代码上下文里的 TODO 不算待办（历史上测试 fixture `const TODO = {...}` 曾把 CI 打红）
  const F = join(base, 'f')
  mkdirSync(join(F, 'frontend/src'), { recursive: true })
  writeFileSync(
    join(F, 'frontend/src/fixture.spec.js'),
    [
      "const TODO = { id: 'T-1' }",
      "const label = 'TODO: 只是字符串'",
      '// TODO: 这才是真注释',
    ].join('\n') + '\n',
  )
  const rf = run([], F)
  check(
    'F 代码标识符/字符串中的 TODO 不计入（只算 1 处真注释）',
    /裸标记 1 处/.test(out(rf)),
    out(rf).split('\n')[1] || '',
  )
  check('F 真注释 TODO 仍被检出（fixture.spec.js:3）', /fixture\.spec\.js:3/.test(out(rf)))

  // G. Python：字符串字面量里的 TODO 不算，`#` 注释才算
  const G = join(base, 'g')
  mkdirSync(join(G, 'ai-service/app'), { recursive: true })
  writeFileSync(
    join(G, 'ai-service/app/sample.py'),
    ['x = "TODO: 字符串里的"', '# TODO: python 注释里的'].join('\n') + '\n',
  )
  const rg = run([], G)
  check('G Python 字符串里的 TODO 不计入（只算 1 处 # 注释）', /裸标记 1 处/.test(out(rg)), out(rg).split('\n')[1] || '')

  // H. 块注释跨行：javadoc 续行（以 * 开头）里的 TODO 应被检出
  const H = join(base, 'h')
  mkdirSync(join(H, 'backend/src'), { recursive: true })
  writeFileSync(
    join(H, 'backend/src/Block.java'),
    ['/**', ' * TODO: javadoc 续行里的待办', ' */', 'class Block {}'].join('\n') + '\n',
  )
  const rh = run([], H)
  check('H javadoc 续行中的 TODO 被检出', /Block\.java:2/.test(out(rh)), out(rh).split('\n')[1] || '')

  // I. 反引号包裹的关键字属「引用」而非标记（写规则说明时常见）
  const I = join(base, 'i')
  mkdirSync(join(I, 'frontend/src'), { recursive: true })
  writeFileSync(
    join(I, 'frontend/src/rule.js'),
    ['// 说明：`TODO` 只是被引用的关键字，不是待办', '// TODO: 这才是真标记'].join('\n') + '\n',
  )
  const ri = run([], I)
  check('I 反引号内的关键字不计数（只算 1 处真标记）', /裸标记 1 处/.test(out(ri)), out(ri).split('\n')[1] || '')
  check('I 真标记仍被检出（rule.js:2）', /rule\.js:2/.test(out(ri)))
} finally {
  rmSync(base, { recursive: true, force: true })
}

console.log(`\n[selftest] ${pass} passed, ${fail} failed`)
process.exit(fail ? 1 : 0)
