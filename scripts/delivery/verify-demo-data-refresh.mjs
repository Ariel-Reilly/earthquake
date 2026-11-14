/**
 * 演示数据刷新 + 即时验证（2026-11-14 复测 P1-2；第三轮复测修「假绿」）。
 *
 * 默认只**验证**当前状态，不修改数据；显式 `APPLY=1` 才会执行刷新 SQL。
 *   node scripts/delivery/verify-demo-data-refresh.mjs            # 只看状态
 *   APPLY=1 node scripts/delivery/verify-demo-data-refresh.mjs    # 刷新并验证
 *
 * ⚠ 执行前请先备份目标库（参考 docs/delivery/RETEST_FIX_REPORT.md）。
 * ⚠ 刷新结果约 5 分钟后会被 DeviceOnlineChecker 心跳打回「离线」，
 *    应在演示开始前 5 分钟内执行；详见 refresh-demo-data.sql 头部说明。
 *
 * 历史缺陷（2026-11-14 第三轮复测）：本脚本只查 max(created_at)，
 * 而预警列表 /api/warnings 的 `time`、WarningDto.triggeredAt、24h 时间对比
 * 用的都是 **triggered_at**。刷新脚本又用 COALESCE 保留了旧 triggered_at，
 * 于是「脚本 PASS、页面仍显示 11-06」。现改为：DB 同时校验 created_at /
 * triggered_at，并新增真实接口层校验页面真正绑定的字段。
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

function check(name, ok, detail = '', level = 'check') {
  checks.push({ name, ok, detail, level })
  const tag = level === 'warn' ? 'WARN' : (ok ? 'PASS' : 'FAIL')
  console.log(`  ${tag}  ${name}${detail ? ` — ${detail}` : ''}`)
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
        || ' created=' || coalesce((select max(created_at)::date::text from warning_event),'-')
        || ' triggered=' || coalesce((select max(triggered_at)::date::text from warning_event),'-')
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
  const dateMatch = /created=(\S+) triggered=(\S+) hazard=(\S+) today=(\S+)/.exec(db)
  const trajMatch = /traj24h=(\d+)/.exec(db)
  const online = Number(onlineMatch?.[1])
  const total = Number(onlineMatch?.[2])
  const createdDay = dateMatch?.[1]
  const triggeredDay = dateMatch?.[2]
  const hazardDay = dateMatch?.[3]
  const today = dateMatch?.[4]

  // 在线数：APPLY 刚刷新必须 6/16；只读时 5 分钟心跳回落属已知语义，
  // 标 WARN 提示重跑，不产生假失败（也不伪装成「已在线」）。
  if (APPLY) {
    check('刷新后 6/16 台设备在线（APPLY 即时验证）',
      online === 6 && total === 16, `online=${online} total=${total}`)
  } else if (online === 6 && total === 16) {
    check('当前 6/16 台设备在线（仍在 5 分钟心跳窗口内）', true,
      `online=${online} total=${total}`)
  } else {
    check('在线设备已回落（DeviceOnlineChecker 心跳，非刷新失败）', true,
      `online=${online} total=${total}；演示开始前 5 分钟内重跑 APPLY=1`, 'warn')
  }
  check('设备地图接口与库状态一致',
    stats?.total === 16 && stats?.online === online,
    `api.online=${stats?.online} db.online=${online}`)

  // 两列都必须为今天；triggered_at 是页面/DTO 实际字段（假绿根因）。
  check('预警 created_at 为今天', createdDay === today,
    `created=${createdDay} today=${today}`)
  check('预警 triggered_at 为今天（页面/DTO 实际字段，杜绝假绿）',
    triggeredDay === today, `triggered=${triggeredDay} today=${today}`)
  check('created_at 与 triggered_at 日期一致',
    createdDay === triggeredDay, `created=${createdDay} triggered=${triggeredDay}`)
  check('灾害点 discovered_at 为今天', hazardDay === today,
    `hazard=${hazardDay} today=${today}`)
  check('近 24h 存在轨迹采样点（无需伪造）', Number(trajMatch?.[1] ?? 0) > 0,
    `traj24h=${trajMatch?.[1]}`)

  // 接口层：直接验证页面真正绑定的字段，而不只是 DB 里的列。
  const wl = await api('/warnings?page=1&size=200', { token })
  const raw = wl.json?.data
  const rows = Array.isArray(raw) ? raw : (raw?.records ?? raw?.list ?? [])
  const active = rows.filter((w) => !['已解除', '已关闭'].includes(w.status))
  const stale = active.filter((w) => {
    const d = new Date(w.triggeredAt)
    return !w.triggeredAt || Number.isNaN(+d) || d.toLocaleDateString('sv-SE') !== today
  })
  console.log(`  API /warnings: records=${rows.length} active=${active.length} sampleTime=${active[0]?.time ?? '-'}`)
  check('预警列表 API：活跃预警 triggeredAt 为今天（页面真实数据源）',
    active.length > 0 && stale.length === 0,
    `active=${active.length} stale=${stale.length} triggeredAt=${active[0]?.triggeredAt ?? '-'}`)
  check('预警列表 API：time 文本不再是 11-06 旧日期',
    active.length > 0 && active.every((w) => !/11-06/.test(String(w.time ?? ''))),
    active.map((w) => w.time).slice(0, 3).join(' / '))

  const summary = {
    checkedAt: new Date().toISOString(), applied: APPLY, app: APP,
    db, deviceMapStats: stats, rowsCount: rows.length,
    activeWarnings: active.slice(0, 5).map((w) => ({ id: w.id, time: w.time, triggeredAt: w.triggeredAt, status: w.status })),
    checks,
    note: 'DeviceOnlineChecker 每 60s 把 5 分钟无上报的在线设备置离线；演示前 5 分钟内重跑刷新脚本。DB 与接口同时校验 created_at / triggered_at，避免只查 created_at 的假绿。',
  }
  fs.writeFileSync(path.join(OUT, 'demo-data-refresh.json'), JSON.stringify(summary, null, 2))
  const failed = checks.filter((c) => c.level !== 'warn' && !c.ok).length
  const warned = checks.filter((c) => c.level === 'warn').length
  const passed = checks.filter((c) => c.level !== 'warn' && c.ok).length
  console.log(`=== ${passed}/${passed + failed} PASS${warned ? ` / ${warned} WARN` : ''}；明细 output/delivery-verify/demo-data-refresh.json ===`)
  if (failed > 0) process.exitCode = 1
}

main().catch((e) => { console.error('验证失败：', e); process.exitCode = 1 })
