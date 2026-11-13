/**
 * B2-2 多人会签真实验证（开发环境，真实 API + 双用户确认）。
 *
 * 目的：验证「≥2 人会签」不是只有建行，而是两个不同账号各自确认自己的 signerId、
 * 两条 event_sign 都变为 CONFIRMED，且事件状态按状态机推进。
 *
 * 前置：后端 8080、PostgreSQL 容器 disasterguard-postgres 可 docker exec。
 * 用法：node scripts/delivery/verify-multi-signer.mjs
 * 说明：会在开发库创建一条临时会签，结束后删除 event_sign 行并还原事件状态。
 */
import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

const API = process.env.DG_API || 'http://127.0.0.1:8080/api'
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const EVENTS = ['EVT-HZ-001', 'EVT-HZ-002', 'EVT-SBOX-01']
const OUT = path.resolve('output/delivery-verify')
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function sql(query) {
  return execFileSync('docker', ['exec', 'disasterguard-postgres', 'psql',
    '-U', 'disasterguard', '-d', 'disasterguard', '-t', '-A', '-c', query],
    { encoding: 'utf8' }).trim()
}

async function login(username) {
  const res = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: PASSWORD }),
  })
  const body = await res.json().catch(() => null)
  if (res.status !== 200 || body?.code !== 0) {
    throw new Error(`登录失败 ${username}: HTTP ${res.status} ${body?.message || ''}`)
  }
  return { token: body.data.token, user: body.data.user }
}

async function api(token, pathname, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(`${API}${pathname}`, { ...options, headers })
  const body = await res.json().catch(() => null)
  return { status: res.status, body }
}

function parseRows(raw) {
  return raw.split('\n').filter(Boolean).map((line) => line.split('|'))
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  const evidence = { time: new Date().toISOString(), event: null, users: null, create: null, confirms: [], rows: [], db: null, cleanup: null, pass: false }
  try {
    // 选一个可会签事件并记录原始状态
    const eventRows = parseRows(sql(
      `select event_code||'|'||state from unified_event where state in ('DETECTED','CONFIRMING') and event_code in (${EVENTS.map((e) => `'${e}'`).join(',')}) order by event_code limit 1;`))
    if (!eventRows.length) throw new Error('没有可会签事件（DETECTED/CONFIRMING）')
    const [eventId, originalState] = eventRows[0]
    evidence.event = { eventId, originalState }

    // 两名可用账号：expert(id=5) 与 emergency_commander(id=3)，均有 sign:create/confirm
    const users = parseRows(sql(
      `select id||'|'||username||'|'||role_code from sys_user where username in ('expert','commander') order by id;`))
      .map(([id, username, role]) => ({ id, username, role }))
    evidence.users = users
    if (users.length < 2) throw new Error('缺少两名可会签用户')

    const admin = await login('admin')
    const expert = await login('expert')
    const commander = await login('commander')

    // 1) admin 发起并行会签（2 人）
    const create = await api(admin.token, `/events/${eventId}/sign`, {
      method: 'POST',
      body: JSON.stringify({
        signers: users.map((u) => ({ id: u.id, name: u.username, specialty: u.role })),
        mode: 'PARALLEL',
        timeout: 24,
        timeoutAction: 'ESCALATE',
        comment: 'B2-2 双人真实验证',
      }),
    })
    const signId = create.body?.data?.signId ?? null
    evidence.create = { status: create.status, code: create.body?.code, signId }
    if (create.status !== 200 || create.body?.code !== 0 || !signId) {
      throw new Error(`发起会签失败: HTTP ${create.status} ${JSON.stringify(create.body)}`)
    }

    // 2) 两个账号分别确认自己的 signerId
    for (const [loginInfo, user] of [[expert, users.find((u) => u.username === 'expert')],
      [commander, users.find((u) => u.username === 'commander')]]) {
      const confirm = await api(loginInfo.token, `/events/${eventId}/sign/${signId}/confirm`, {
        method: 'PUT',
        body: JSON.stringify({ signerId: user.id, opinion: `${user.username} 本人确认` }),
      })
      evidence.confirms.push({ user: user.username, status: confirm.status, code: confirm.body?.code, message: confirm.body?.message })
      await sleep(500)
    }

    // 3) 核心断言：两条 event_sign 均为 CONFIRMED
    const rows = parseRows(sql(
      `select signer_id||'|'||signer_status||'|'||coalesce(opinion,'') from event_sign where sign_id='${signId}' order by signer_id;`))
    evidence.rows = rows
    const confirmed = rows.filter((r) => r[1] === 'CONFIRMED')
    const dbState = sql(`select state from unified_event where event_code='${eventId}';`)
    evidence.db = { signRows: rows.length, confirmed: confirmed.length, eventStateAfter: dbState }

    evidence.pass = confirmed.length === 2
      && evidence.confirms.every((c) => c.status === 200 && c.code === 0)

    // 4) 清理：删测试会签行、还原事件状态
    sql(`delete from event_sign where sign_id='${signId}';`)
    sql(`update unified_event set state='${originalState}' where event_code='${eventId}';`)
    evidence.cleanup = { signRowsLeft: sql(`select count(*) from event_sign where sign_id='${signId}';`), eventState: sql(`select state from unified_event where event_code='${eventId}';`) }
  } catch (err) {
    evidence.error = String(err?.message || err)
  }

  const file = path.join(OUT, 'multi-signer-20261113.json')
  fs.writeFileSync(file, JSON.stringify(evidence, null, 2))
  console.log(JSON.stringify(evidence, null, 2))
  console.log(evidence.pass ? '\nB2-2 PASS：两个账号各自确认，event_sign 2/2 CONFIRMED' : '\nB2-2 FAIL')
  process.exit(evidence.pass ? 0 : 1)
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
