/**
 * 2026-11-14 UI/接口审查 P0 修复 —— 真实接口回归。
 *
 * 前置：nginx(dist) 25173 → 本轮新 jar 后端 → 隔离库；ai-service 28081 已启动。
 * 用法：node scripts/delivery/verify-review-api-fixes.mjs
 *
 * 覆盖：
 *   P0-1 /device-map 从 500 变 200，且返回真实库 16 台设备而非 12 台 mock；
 *   P0-3 /api/ai/forecast/auto 从 500 变 200 且字段完整；
 *   P0-4 气象接口不再 403；
 *   P0-5 drift 后端代理仍为显式 501（诚实缺口，不是回归失败）。
 */
import fs from 'node:fs'
import path from 'node:path'

const APP = (process.env.E2E_APP_BASE || 'http://127.0.0.1:25173').replace(/\/$/, '')
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const OUT = path.resolve('output/delivery-verify')

const checks = []
function check(name, ok, detail = '') {
  checks.push({ name, ok, detail })
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
}

async function api(p, { token, method = 'GET', body } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const resp = await fetch(`${APP}/api${p}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return { status: resp.status, json: await resp.json().catch(() => null) }
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  console.log(`=== 审查 P0 修复 · 真实接口回归 (${APP}) ===`)

  const login = await api('/auth/login', {
    method: 'POST',
    body: { username: 'admin', password: PASSWORD },
  })
  const token = login.json?.data?.token
  check('登录成功', login.status === 200 && !!token, `HTTP ${login.status}`)
  if (!token) throw new Error(`登录失败：${JSON.stringify(login.json).slice(0, 200)}`)

  // ── P0-1 / P0-2 设备地图 ──
  const dm = await api('/device-map', { token })
  const data = dm.json?.data
  const names = (data?.devices || []).map((d) => `${d.deviceCode} ${d.deviceName}`).join(' | ')
  check('GET /api/device-map 返回 200', dm.status === 200 && dm.json?.code === 0, `HTTP ${dm.status} code=${dm.json?.code}`)
  check('设备数为真实库 16 台', data?.stats?.total === 16, `total=${data?.stats?.total}`)
  check('设备统计内部一致（在线+离线+故障=总数）',
    (data?.stats?.online ?? 0) + (data?.stats?.offline ?? 0) + (data?.stats?.fault ?? 0) === data?.stats?.total,
    `online=${data?.stats?.online} offline=${data?.stats?.offline} fault=${data?.stats?.fault} total=${data?.stats?.total}`)
  check('不含任何成都 mock 设备', !/GPS-001|北斗一号|DRONE-001|VEHICLE-001/.test(names))
  check('lastReportAt 可解析', (data?.devices || []).every((d) => !d.lastReportAt || !Number.isNaN(Date.parse(d.lastReportAt))))

  // ── P0-3 灾害预测 ──
  const fc = await api('/ai/forecast/auto?deviceCode=DV-001&metric=displacement&horizon=6', { token })
  const fd = fc.json?.data
  check('GET /api/ai/forecast/auto 返回 200', fc.status === 200 && fc.json?.code === 0, `HTTP ${fc.status} code=${fc.json?.code}`)
  check('预测结果字段完整（Jackson getter 生效）',
    fd?.deviceCode === 'DV-001' && Array.isArray(fd?.predictions) && typeof fd?.degraded === 'boolean',
    `keys=${Object.keys(fd || {}).join(',')}`)

  const fcPost = await api('/ai/forecast', {
    token, method: 'POST',
    body: { deviceCode: 'DV-001', metric: 'displacement', horizon: 3, history: [1, 2, 3, 4, 5] },
  })
  check('POST /api/ai/forecast 返回 200', fcPost.status === 200 && fcPost.json?.code === 0, `HTTP ${fcPost.status}`)

  // ── P0-4 气象分析权限 ──
  const weather = await api('/weather/products/latest', { token })
  check('气象接口不再返回 403（admin 权限已同步）', weather.status !== 403, `HTTP ${weather.status}`)

  // ── P0-5 drift 显式 501 是刻意缺口 ──
  const drift = await api('/ai/drift/detect', { token, method: 'POST', body: { modelId: 'm1', currentData: [] } })
  check('drift 后端代理保持显式 501（不冒充真实能力）', drift.status === 501, `HTTP ${drift.status}`)
  check('501 响应含 deprecated 标记', drift.json?.deprecated === true, JSON.stringify(drift.json || {}).slice(0, 120))

  const summary = {
    checkedAt: new Date().toISOString(), app: APP,
    deviceMap: { status: dm.status, total: data?.stats?.total, online: data?.stats?.online,
      offline: data?.stats?.offline, deviceCodes: (data?.devices || []).map((d) => d.deviceCode) },
    forecast: { status: fc.status, keys: Object.keys(fd || {}) },
    weather: { status: weather.status },
    drift: { status: drift.status, deprecated: drift.json?.deprecated },
    checks,
  }
  fs.writeFileSync(path.join(OUT, 'review-fixes-api.json'), JSON.stringify(summary, null, 2))
  const passed = checks.filter((c) => c.ok).length
  console.log(`=== ${passed}/${checks.length} PASS；明细 output/delivery-verify/review-fixes-api.json ===`)
  if (passed !== checks.length) process.exitCode = 1
}

main().catch((e) => { console.error('回归失败：', e); process.exitCode = 1 })
