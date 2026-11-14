/**
 * 演示数据刷新 + 即时验证（2026-11-14 复测 P1-2）。
 *
 * 默认只**验证**当前状态，不修改数据；显式 `APPLY=1` 才会执行刷新 SQL。
 *   node scripts/delivery/verify-demo-data-refresh.mjs            # 只看状态
 *   APPLY=1 node scripts/delivery/verify-demo-data-refresh.mjs    # 刷新并验证
 *
 * ⚠ 执行前请先备份目标库（参考 docs/delivery/REVIEW_FIX_REPORT.md）。
 * ⚠ 刷新结果约 5 分钟后会被 DeviceOnlineChecker 心跳打回「离线」，
 *    应在演示开始前 5 分钟内执行；详见 refresh-demo-data.sql 头部说明。
 */
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const PG = process.env.DG_PG_CONTAINER || 'disasterguard-postgres'
const APP = (process.env.E2E_APP_BASE || 'http://127.0.0.1:8080').replace(/\/$/, '')
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const APPLY = process.env.APPLY === '1'
const OUT = path.resolve('output/delivery-verify')
const checks = []

function check(name, ok, detail = '') {
  checks.push({ name, ok, detail })
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
}

function psql(sql) {
  return execFileSync('docker', ['exec', '-i', PG, 'psql', '-U', 'disasterguard',
    '-d', 'disasterguard', '-t', '-A', '-v', 'ON_ERROR_STOP=1', '-c', sql],
    { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }).trim()
}

async function api(p, { token, method = 'GET', body } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const resp = await fetch(`${APP}/api${p}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  })
  return { status: resp.status, json: await resp.json().catch(() => null) }
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  console.log(`=== 演示数据刷新验证（${APPLY ? 'APPLY=1 执行刷新' : '只读检查'}）===`)

  if (APPLY) {
    const sql = fs.readFileSync(path.join(ROOT, 'scripts/delivery/refresh-demo-data.sql'), 'utf8')
    execFileSync('docker', ['exec', '-i', PG, 'psql', '-U', 'disasterguard',
      '-d', 'disasterguard', '-q', '-v', 'ON_ERROR_STOP=1'],
      { input: sql, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 })
    console.log('  已执行 refresh-demo-data.sql')
  }

  const db = psql(`
    select 'online=' || count(*) filter (where online_status='在线')
        || ' total=' || count(*)
        || ' warning=' || coalesce((select max(created_at)::date::text from warning_event),'-')
        || ' hazard=' || coalesce((select max(discovered_at)::date::text from unified_event where kind::text='HAZARD'),'-')
        || ' today=' || current_date::text
        || ' traj24h=' || (select count(*) from device_trajectory where recorded_at > now() - interval '24 hours')
    from monitoring_device;
  `)
  console.log(`  DB: ${db}`)

  const login = await api('/auth/login', { method: 'POST', body: { username: 'admin', password: PASSWORD } })
  const token = login.json?.data?.token
  if (!token) throw new Error(`登录失败：${JSON.stringify(login.json).slice(0, 160)}`)
  const dm = await api('/device-map', { token })
  const stats = dm.json?.data?.stats
  console.log(`  API /device-map: total=${stats?.total} online=${stats?.online} offline=${stats?.offline}`)

  const onlineMatch = /online=(\d+) total=(\d+)/.exec(db)
  const dateMatch = /warning=(\S+) hazard=(\S+) today=(\S+)/.exec(db)
  const trajMatch = /traj24h=(\d+)/.exec(db)

  check('刷新后 6/16 台设备在线（需在 5 分钟心跳窗口内验证）',
    Number(onlineMatch?.[1]) === 6 && Number(onlineMatch?.[2]) === 16,
    `online=${onlineMatch?.[1]} total=${onlineMatch?.[2]}`)
  check('设备地图接口与库状态一致',
    stats?.total === 16 && stats?.online === Number(onlineMatch?.[1]),
    `api.online=${stats?.online}`)
  check('预警与灾害点最后时间为今天',
    dateMatch?.[1] === dateMatch?.[3] && dateMatch?.[2] === dateMatch?.[3],
    `warning=${dateMatch?.[1]} hazard=${dateMatch?.[2]} today=${dateMatch?.[3]}`)
  check('近 24h 存在轨迹采样点（无需伪造）', Number(trajMatch?.[1] ?? 0) > 0,
    `traj24h=${trajMatch?.[1]}`)

  const summary = {
    checkedAt: new Date().toISOString(), applied: APPLY, app: APP,
    db, deviceMapStats: stats, checks,
    note: 'DeviceOnlineChecker 每 60s 把 5 分钟无上报的在线设备置离线；演示前 5 分钟内重跑刷新脚本。',
  }
  fs.writeFileSync(path.join(OUT, 'demo-data-refresh.json'), JSON.stringify(summary, null, 2))
  const passed = checks.filter((c) => c.ok).length
  console.log(`=== ${passed}/${checks.length} PASS；明细 output/delivery-verify/demo-data-refresh.json ===`)
  if (passed !== checks.length) process.exitCode = 1
}

main().catch((e) => { console.error('验证失败：', e); process.exitCode = 1 })
