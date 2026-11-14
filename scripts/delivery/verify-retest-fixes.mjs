/**
 * 2026-11-14 复测遗留项修复 —— 真实浏览器 / 接口回归。
 *
 * 前置：dev 前端 5173（Vite 代理 /api → 8080）、后端 8080 已加载最新代码、
 *       ai-service 8001、Edge/Chrome headless CDP 9222。
 * 用法：node scripts/delivery/verify-retest-fixes.mjs
 *
 * 覆盖 5 项：
 *   1) 设备地图视野 fit 到真实 16 台重庆设备（不再停留成都 demo 中心）；
 *   2) 演示数据刷新后 6/16 在线、预警/灾害点时间为今天；
 *   3) AI 综合研判首屏秒级展示「落库评分」并标注来源，后台再刷新实时研判；
 *   4) AI 模型页首屏不再发 drift 501，只有点开 Tab 才请求，空态说明明确；
 *   5) 预警页真实渲染时间（第四轮复测：UTC→浏览器本地时区闭环）：
 *      用 CDP 切 Asia/Shanghai 与 UTC 两个时区，证明页面时间分别等于各时区
 *      从 triggeredAt 计算的值、两者不同，且不残留 11-06。
 *      注意：不再断言「DOM == 接口 time」——后端 time 是 UTC 快照，本地化后二者本就不等。
 */
import fs from 'node:fs'
import path from 'node:path'

const APP = (process.env.E2E_APP_BASE || 'http://127.0.0.1:5173').replace(/\/$/, '')
const CDP = process.env.CDP_BASE || 'http://127.0.0.1:9222'
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const OUT = path.resolve('output/delivery-verify')
const SHOTS = path.resolve('output/retest-fixes')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const checks = []
function check(name, ok, detail = '') {
  checks.push({ name, ok, detail })
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
}

class CdpClient {
  constructor(ws) {
    this.ws = ws
    this.nextId = 1
    this.pending = new Map()
    this.events = []
    ws.addEventListener('message', (event) => {
      const msg = JSON.parse(event.data)
      if (msg.id && this.pending.has(msg.id)) {
        const task = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error ? task.reject(new Error(msg.error.message)) : task.resolve(msg.result)
      } else if (msg.method) {
        this.events.push(msg)
      }
    })
  }

  static async create() {
    const version = await (await fetch(`${CDP}/json/version`)).json()
    const target = await (await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })).json()
    const ws = new WebSocket(target.webSocketDebuggerUrl)
    await new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve, { once: true })
      ws.addEventListener('error', reject, { once: true })
    })
    return { client: new CdpClient(ws), version, targetId: target.id }
  }

  send(method, params = {}, timeoutMs = 30000) {
    return new Promise((resolve, reject) => {
      const id = this.nextId++
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id)
          reject(new Error(`CDP 超时: ${method}`))
        }
      }, timeoutMs)
    })
  }

  async eval(expression, timeoutMs = 30000) {
    const r = await this.send('Runtime.evaluate',
      { expression, returnByValue: true, awaitPromise: true }, timeoutMs)
    if (r.exceptionDetails) throw new Error(`页面脚本异常：${r.exceptionDetails.text}`)
    return r.result?.value
  }

  async screenshot(file) {
    const r = await this.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
    fs.writeFileSync(path.join(SHOTS, file), Buffer.from(r.data, 'base64'))
  }

  issues(from) {
    const slice = this.events.slice(from)
    const noisy = /net::ERR_ABORTED|ERR_BLOCKED_BY_ORB|favicon/i
    return {
      serverErrors: slice.filter((e) => e.method === 'Network.responseReceived'
        && (e.params?.response?.status ?? 0) >= 500)
        .map((e) => `${e.params.response.status} ${e.params.response.url}`),
      exceptions: slice.filter((e) => e.method === 'Runtime.exceptionThrown')
        .map((e) => e.params?.exceptionDetails?.text || 'unknown'),
      consoleErrors: slice.filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params?.type === 'error')
        .map((e) => e.params.args?.map((a) => a.value ?? a.description).join(' ').slice(0, 160) || '')
        .filter((t) => !noisy.test(t)),
    }
  }
}

async function waitReady(client, minLen = 200, tries = 30) {
  for (let i = 0; i < tries; i++) {
    try {
      const n = await client.eval(
        `document.readyState === 'complete' ? document.body.innerText.length : 0`, 8000)
      if (n >= minLen) return n
    } catch (_) { /* 导航中上下文销毁 */ }
    await sleep(400)
  }
  return 0
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
  fs.mkdirSync(SHOTS, { recursive: true })
  console.log(`=== 复测遗留项修复 · 回归 (${APP}) ===`)

  const login = await api('/auth/login', {
    method: 'POST', body: { username: 'admin', password: PASSWORD },
  })
  const token = login.json?.data?.token
  check('登录成功', login.status === 200 && !!token, `HTTP ${login.status}`)
  if (!token) throw new Error(`登录失败：${JSON.stringify(login.json).slice(0, 200)}`)

  // ── 2) 演示数据刷新 ──
  const dm = await api('/device-map', { token })
  const stats = dm.json?.data?.stats
  // 在线数会被 DeviceOnlineChecker（5 分钟心跳）动态改写，不能写死 6；
  // 演示数据刷新是否生效由单独的 demo-data-refresh.json 证据在刷新后立即验证。
  check('设备地图返回真实 16 台且统计自洽',
    stats?.total === 16
      && (stats?.online ?? 0) + (stats?.offline ?? 0) + (stats?.fault ?? 0) === 16,
    `total=${stats?.total} online=${stats?.online} offline=${stats?.offline}`)

  const { client, version, targetId } = await CdpClient.create()
  console.log(`  CDP: ${version.Browser}`)
  await client.send('Page.enable')
  await client.send('Runtime.enable')
  await client.send('Network.enable')
  await client.send('Emulation.setDeviceMetricsOverride',
    { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  await client.send('Page.navigate', { url: APP + '/' })
  await waitReady(client, 100)
  await client.eval(`(() => {
    localStorage.setItem('disasterguard_token', ${JSON.stringify(token)});
    localStorage.setItem('disasterguard_user', ${JSON.stringify(JSON.stringify(login.json.data.user))});
    return true;
  })()`)

  // ── 1) 设备地图 fit ──
  let from = client.events.length
  await client.send('Page.navigate', { url: APP + '/dashboard/device-map' })
  await waitReady(client)
  let mapProbe = null
  for (let i = 0; i < 30; i++) {
    try {
      mapProbe = await client.eval(`(() => {
        const d = window.__dgDeviceMap
        if (!d) return null
        const c = d.view.getCenter()
        return { centerX: c?.[0], centerY: c?.[1], zoom: d.view.getZoom(), features: d.source.getFeatures().length }
      })()`)
      if (mapProbe && mapProbe.features > 0) break
    } catch (_) { /* OL 尚未加载 */ }
    await sleep(500)
  }
  const dmIssues = client.issues(from)
  await client.screenshot('retest-device-map-fit.png')
  // EPSG:3857 下重庆 106.55° ≈ 11,858,000；成都 104.065° ≈ 11,584,000。
  check('地图 marker 已渲染 16 台设备', mapProbe?.features === 16, `features=${mapProbe?.features}`)
  check('视野中心已落在重庆（非成都 demo 坐标）', (mapProbe?.centerX ?? 0) > 11700000,
    `centerX=${Math.round(mapProbe?.centerX ?? 0)}`)
  check('视野已 fit 到设备范围（zoom ≤ 14）', (mapProbe?.zoom ?? 99) <= 14,
    `zoom=${mapProbe?.zoom}`)
  check('设备地图无错误横幅 / 演示数据横幅', await client.eval(`(() => ({
    err: document.querySelectorAll('.dm-notice-error').length,
    demo: document.querySelectorAll('.dm-notice-demo').length,
  }))()`).then((v) => v.err === 0 && v.demo === 0))

  // ── 3) AI 综合研判首屏 ──
  from = client.events.length
  const t0 = Date.now()
  await client.send('Page.navigate', { url: APP + '/dashboard/ai-insight' })
  await waitReady(client)
  let insight = null
  for (let i = 0; i < 20; i++) {
    try {
      insight = await client.eval(`(() => {
        const content = document.querySelector('.comprehensive-content')
        const banner = document.querySelector('.source-banner')
        return {
          hasContent: !!content,
          bannerText: banner?.innerText || '',
          isCached: !!banner?.classList.contains('is-cached'),
          loadingOnly: !content && !!document.querySelector('.loading-state'),
        }
      })()`)
      if (insight?.hasContent) break
    } catch (_) { /* 导航中 */ }
    await sleep(400)
  }
  const insightMs = Date.now() - t0
  const insightIssues = client.issues(from)
  await client.screenshot('retest-ai-insight-cached.png')
  check('AI 研判首屏 6 秒内出现可解释内容（不再等 27s）',
    insight?.hasContent === true && insightMs < 6000, `${insightMs} ms`)
  check('落库评分明确标注来源（非冒充实时推理）',
    /落库评分|实时研判结果/.test(insight?.bannerText || ''), insight?.bannerText?.slice(0, 90))
  check('AI 研判页无意外 5xx / 未捕获异常',
    insightIssues.serverErrors.length === 0 && insightIssues.exceptions.length === 0)

  // ── 4) AI 模型页 drift 懒加载 + 空态 ──
  from = client.events.length
  await client.send('Page.navigate', { url: APP + '/dashboard/ai-models' })
  await waitReady(client)
  await sleep(1600)
  const beforeTab = client.issues(from)
  const preDrift501 = beforeTab.serverErrors.filter((s) => /\/api\/ai\/drift/.test(s))
  const emptyText = await client.eval(`document.body.innerText.slice(0, 1200)`)
  await client.screenshot('retest-ai-models-first-screen.png')
  check('AI 模型页首屏不再发 drift 501 请求', preDrift501.length === 0,
    `${preDrift501.length} 个`)
  check('模型版本库空态写明「演示库尚未初始化」',
    /演示库尚未初始化模型版本表/.test(emptyText || ''))
  check('KPI 全 0 时写明是真实统计为空',
    /模型统计表为空/.test(emptyText || ''))

  // 点开漂移监控 Tab 后才允许出现 501，并且必须显示「能力未接入」
  const clicked = await client.eval(`(() => {
    const b = [...document.querySelectorAll('button')].find(x => x.innerText.includes('漂移监控'))
    if (!b) return false
    b.click()
    return true
  })()`)
  await sleep(1800)
  const afterTab = client.issues(from)
  const postDrift501 = afterTab.serverErrors.filter((s) => /\/api\/ai\/drift/.test(s))
  const driftNotice = await client.eval(`(() => {
    const el = document.querySelector('.drift-unavailable')
    return el ? el.innerText : ''
  })()`)
  await client.screenshot('retest-ai-models-drift-tab.png')
  check('漂移监控 Tab 可点开', clicked === true)
  check('点开 Tab 后才发 drift 请求（501 为显式缺口）', postDrift501.length >= 1,
    `${postDrift501.length} 个`)
  check('漂移页显示「能力未接入」而非 0 值', /能力未接入/.test(driftNotice || ''),
    driftNotice?.slice(0, 80))

  // ── 5) 预警页真实渲染时间 + 双时区语义（第四轮复测「UTC→浏览器本地时区」闭环）──
  // 根因：后端 WarningDto.time 是按 UTC 格式化的 "MM-dd HH:mm" 快照，前端原样渲染
  // 会比本地早 8 小时；而 triggeredAt 是 ISO 绝对时刻，应由前端按浏览器时区格式化。
  // 第三轮曾断言「DOM == 接口 time」，两者同为 UTC 所以发现不了时区问题 —— 本轮改为
  // 用 CDP 切两个时区，证明 DOM 分别等于各自时区从 triggeredAt 计算的值、且互不相同。
  from = client.events.length
  const wl = await api('/warnings?page=1&size=200', { token })
  const wlRaw = wl.json?.data
  const wlRows = Array.isArray(wlRaw) ? wlRaw : (wlRaw?.records ?? wlRaw?.list ?? [])
  const activeWarnings = wlRows.filter((w) => !['已解除', '已关闭'].includes(w.status))
  const apiActive = activeWarnings[0] ?? null

  // 按指定 IANA 时区从 triggeredAt 计算期望的 "MM-dd HH:mm"（与接口 time 无关）
  const expectedInTz = (iso, timeZone) => {
    if (!iso) return null
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return null
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone, hourCycle: 'h23', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    }).formatToParts(d)
    const g = (t) => parts.find((p) => p.type === t)?.value ?? ''
    return `${g('month')}-${g('day')} ${g('hour')}:${g('minute')}`
  }
  const probeTimes = async (timeZone) => {
    await client.send('Emulation.setTimezoneOverride', { timezoneId: timeZone })
    await client.send('Page.navigate', { url: APP + '/dashboard/warnings' })
    await waitReady(client)
    for (let i = 0; i < 24; i++) {
      try {
        const r = await client.eval(`(() => {
          const times = [...document.querySelectorAll('.warning-time')].map(e => e.innerText.trim())
          return { times, tz: Intl.DateTimeFormat().resolvedOptions().timeZone, bodyHasOld: document.body.innerText.includes('11-06 14:37') }
        })()`)
        if (r?.times?.length > 0) return r
      } catch (_) { /* 导航中 */ }
      await sleep(400)
    }
    return { times: [], tz: null, bodyHasOld: null }
  }

  const sh = await probeTimes('Asia/Shanghai')
  await client.screenshot('retest-warnings-time-shanghai.png')
  const utc = await probeTimes('UTC')
  await client.screenshot('retest-warnings-time-utc.png')
  // 恢复默认（跟随系统），避免影响后续
  await client.send('Emulation.setTimezoneOverride', { timezoneId: '' })
  const warnIssues = client.issues(from)

  const expSh = expectedInTz(apiActive?.triggeredAt, 'Asia/Shanghai')
  const expUtc = expectedInTz(apiActive?.triggeredAt, 'UTC')
  check('接口活跃预警 triggeredAt 为今天（triggered_at 已刷新，非 11-06）',
    !!apiActive && !/11-06/.test(String(apiActive.triggeredAt ?? '')) && !!expSh,
    `triggeredAt=${apiActive?.triggeredAt ?? '-'} 接口time=${apiActive?.time ?? '-'}`)
  check('上海时区页面显示 triggeredAt 的本地时间（不再早 8 小时）',
    !!expSh && sh.times.includes(expSh), `期望=${expSh} dom=${sh.times.slice(0, 3).join(' / ')} tz=${sh.tz}`)
  check('UTC 时区页面显示 UTC 时间（跟随浏览器时区，非硬编码 UTC+8）',
    !!expUtc && utc.times.includes(expUtc), `期望=${expUtc} dom=${utc.times.slice(0, 3).join(' / ')} tz=${utc.tz}`)
  check('两个时区渲染不同（真正跟随浏览器时区）',
    !!expSh && !!expUtc && expSh !== expUtc && sh.times[0] !== utc.times[0],
    `上海=${sh.times[0]} UTC=${utc.times[0]}`)
  check('页面时间取自 triggeredAt 而非后端 UTC time 快照',
    !!expSh && !!apiActive && expSh !== apiActive.time && sh.times.includes(expSh),
    `本地=${expSh} 后端快照=${apiActive?.time ?? '-'}`)
  check('页面不再残留旧的 11-06 14:37 活跃预警时间',
    sh.bodyHasOld === false && utc.bodyHasOld === false,
    `sh=${sh.bodyHasOld} utc=${utc.bodyHasOld}`)

  const issueSets = [dmIssues, insightIssues, beforeTab, afterTab, warnIssues]
  const unexpected = issueSets.flatMap((x) => x.serverErrors)
    .filter((s) => !/\/api\/ai\/drift/.test(s) && !/\/api\/weather\//.test(s))
  check('全流程无意外 5xx', unexpected.length === 0, unexpected.slice(0, 2).join(' , '))
  check('全流程无未捕获 JS 异常', issueSets.every((x) => x.exceptions.length === 0))

  const summary = {
    checkedAt: new Date().toISOString(), app: APP, browser: version.Browser,
    deviceMapStats: stats, mapProbe, insightMs, insight,
    preDrift501, postDrift501, driftNotice: driftNotice?.slice(0, 200),
    apiActiveTime: apiActive?.time, apiActiveTriggeredAt: apiActive?.triggeredAt,
    expectedShanghai: expSh, expectedUtc: expUtc,
    warnProbe: { shanghai: sh, utc }, unexpected5xx: unexpected, checks,
  }
  fs.writeFileSync(path.join(OUT, 'retest-fixes.json'), JSON.stringify(summary, null, 2))
  await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})

  const passed = checks.filter((c) => c.ok).length
  console.log(`=== ${passed}/${checks.length} PASS；截图 output/retest-fixes/，明细 output/delivery-verify/retest-fixes.json ===`)
  if (passed !== checks.length) process.exitCode = 1
}

main().catch((e) => { console.error('回归失败：', e); process.exitCode = 1 })
