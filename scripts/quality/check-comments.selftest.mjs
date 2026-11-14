#!/usr/bin/env node
/**
 * check-comments.mjs 的自检回归（覆盖异常场景，防止「自检本身失效」）。
 *
 * 场景：
 *  A. allowlist 文件缺失 → --selfcheck 退出 1，并报「allowlist 失效」
 *  B. 片段重复命中     → --selfcheck 退出 1，并报「重复命中」
 *  C. 干净 fixture（9 条各命中一次）→ --warn --selfcheck 退出 0，输出 9/9
 *  D. 真实仓库：--warn --selfcheck 退出 0；--selfcheck 退出 1（仅因后端 6 处裸 TODO）
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
    '# TODO: 从数据库加载历史漂移记录\n# TODO: 从数据库加载历史漂移记录\n',
  )
  const rb = run(['--selfcheck'], B)
  check('B 重复命中 → 退出 1', rb.status === 1, `status=${rb.status}`)
  check('B 重复命中 → 提示「重复命中」', /重复命中/.test(out(rb)))

  // C. 干净 fixture（9 条各命中一次）
  const C = join(base, 'c')
  mkdirSync(join(C, 'ai-service/app/routers'), { recursive: true })
  writeFileSync(
    join(C, 'ai-service/app/routers/drift.py'),
    [
      '# TODO: 从数据库加载历史漂移记录',
      '# TODO: 返回特征的详细分布信息',
      '# TODO: 实际触发重训任务',
      '# TODO: 从任务队列获取状态',
    ].join('\n') + '\n',
  )
  writeFileSync(
    join(C, 'ai-service/app/routers/multimodal.py'),
    [
      'TODO: 按 event_id 从融合结果库加载真实记录',
      '"""x TODO: 从融合结果库加载 y"""',
      '"""x TODO: 从配置库加载 y"""',
    ].join('\n') + '\n',
  )
  writeFileSync(
    join(C, 'ai-service/app/routers/sentinel.py'),
    ['x TODO 标记在 _cluster_hotspots() 中', 'TODO 真实场景下应替换为连通域分析'].join('\n') + '\n',
  )
  const rc = run(['--warn', '--selfcheck'], C)
  check('C 干净 fixture → 退出 0', rc.status === 0, `status=${rc.status}`)
  check('C 干净 fixture → 9/9 命中', /9\/9 命中/.test(rc.stdout))

  // D. 真实仓库
  const rd1 = run(['--warn', '--selfcheck'], REAL_ROOT)
  check('D 真实仓库 --warn --selfcheck → 0', rd1.status === 0, `status=${rd1.status}`)
  const rd2 = run(['--selfcheck'], REAL_ROOT)
  check('D 真实仓库 --selfcheck → 1（后端 6 裸）', rd2.status === 1, `status=${rd2.status}`)
} finally {
  rmSync(base, { recursive: true, force: true })
}

console.log(`\n[selftest] ${pass} passed, ${fail} failed`)
process.exit(fail ? 1 : 0)
