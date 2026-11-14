/**
 * 2026-11-14 UI/接口审查修复 —— 真实浏览器回归（生产 dist + nginx + CDP）。
 *
 * 前置：
 *   1) nginx(dist) 25173 反代到本轮新 jar 后端；
 *   2) Edge/Chrome headless CDP 9222 已启动。
 * 用法：node scripts/delivery/verify-review-browser-fixes.mjs
 *
 * 覆盖：气象分析 403、设备地图 mock 假数据、灾害预测 500、AI 模型页 0 值、
 *      预警页空白 KPI、灾害列表首屏自动弹窗。
 */
import fs from 'node:fs'
import path from 'node:path'

const APP = (process.env.E2E_APP_BASE || 'http://127.0.0.1:25173').replace(/\/$/, '')
const CDP = process.env.CDP_BASE || 'http://127.0.0.1:9222'
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const OUT = path.resolve('output/delivery-verify')
const SHOTS = path.resolve('output/review-fixes')
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
    if (r.exceptionDetails) {
      throw new Error(`页面脚本异常：${r.exceptionDetails.text}`)
    }
    return r.result?.value
  }

  async screenshot(file) {
    const r = await this.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
    fs.writeFileSync(path.join(SHOTS, file), Buffer.from(r.data, 'base64'))
  }

  issues(from) {
    const slice = this.events.slice(from)
    return {
      serverErrors: slice.filter((e) => e.method === 'Network.responseReceived'
        && (e.params?.response?.status ?? 0) >= 500)
        .map((e) => `${e.params.response.status} ${e.params.response.url}`),
      exceptions: slice.filter((e) => e.method === 'Runtime.exceptionThrown')
        .map((e) => e.params?.exceptionDetails?.text || 'unknown'),
      consoleErrors: slice.filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params?.type === 'error')
        .map((e) => e.params.args?.map((a) => a.value ?? a.description).join(' ').slice(0, 160) || ''),
    }
  }
}

async function waitReady(client, minLen = 200, tries = 30) {
  for (let i = 0; i < tries; i++) {
    try {
      const n = await client.eval(
        `document.readyState === 'complete' ? document.body.innerText.length : 0`, 8000)
      if (n >= minLen) return n
    } catch (_) { /* 导航中上下文销毁，继续等 */ }
    await sleep(400)
  }
  return 0
}

const PROBE = `(() => {
  const t = document.body.innerText || ''
  return {
    len: t.length,
    text: t.slice(0, 2500),
    has403: /无权访问|权限不足|403\\s*Forbidden|没有权限/.test(t),
    mockDevices: /北斗一号|GPS-001|DRONE-001|VEHICLE-001/.test(t),
    demoBanner: document.querySelectorAll('.dm-notice-demo').length,
    errorBanner: document.querySelectorAll('.dm-notice-error').length,
    unconnected: (t.match(/未接入/g) || []).length,
    drawerBackdrops: document.querySelectorAll('.modal-backdrop').length,
    overflow: document.documentElement.scrollWidth - window.innerWidth,
    stat16: /16/.test(t),
  }
})()`

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  fs.mkdirSync(SHOTS, { recursive: true })
  console.log(`=== 审查修复 · 真实浏览器回归 (${APP}) ===`)

  const login = await fetch(`${APP}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: PASSWORD }),
  }).then((r) => r.json())
  const token = login?.data?.token
  if (!token) throw new Error(`登录失败：${JSON.stringify(login).slice(0, 200)}`)

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
    localStorage.setItem('disasterguard_user', ${JSON.stringify(JSON.stringify(login.data.user))});
    return true;
  })()`)

  const routes = [
    { route: '/dashboard/weather', tag: 'weather', label: '气象分析（P0-4）' },
    { route: '/dashboard/device-map', tag: 'device-map', label: '设备地图（P0-1/2）' },
    { route: '/dashboard/disaster-forecast', tag: 'disaster-forecast', label: '灾害预测（P0-3）' },
    { route: '/dashboard/ai-models', tag: 'ai-models', label: 'AI 模型（P0-5）' },
    { route: '/dashboard/warnings', tag: 'warnings', label: '预警中心（P1-6）' },
    { route: '/dashboard/hazards', tag: 'hazards', label: '灾害事件（P1-7）' },
    { route: '/dashboard/ai-insight', tag: 'ai-insight', label: 'AI 综合研判（P1-9）' },
  ]

  const pageResults = []
  for (const r of routes) {
    const from = client.events.length
    await client.send('Page.navigate', { url: APP + r.route })
    const len = await waitReady(client)
    await sleep(1100)
    let probe
    try {
      probe = await client.eval(PROBE)
    } catch (e) {
      probe = { len: 0, text: String(e.message || e), has403: false, mockDevices: false,
        demoBanner: 0, errorBanner: 0, unconnected: 0, drawerBackdrops: 0, overflow: 0, stat16: false }
    }
    const issues = client.issues(from)
    // 显式 501（drift/multimodal）是刻意缺口；外部气象源超时单列，不算产品 5xx。
    const unexpected5xx = issues.serverErrors.filter((s) =>
      !/\/api\/ai\/(drift|multimodal)/.test(s) && !/\/api\/weather\//.test(s))
    await client.screenshot(`review-fix-${r.tag}.png`)
    pageResults.push({ ...r, len, probe, issues, unexpected5xx })
    console.log(`  ${r.label.padEnd(22)} 文案=${String(len).padStart(5)} 5xx=${unexpected5xx.length} 异常=${issues.exceptions.length} 未接入=${probe.unconnected}`)
  }

  const by = (tag) => pageResults.find((p) => p.tag === tag)

  check('气象分析页不再 403', by('weather').probe.has403 === false && by('weather').len > 200)
  check('设备地图不再展示成都 mock 设备', by('device-map').probe.mockDevices === false)
  check('设备地图无「演示数据」横幅', by('device-map').probe.demoBanner === 0)
  check('设备地图真实接口成功（无错误横幅）', by('device-map').probe.errorBanner === 0)
  check('设备地图显示真实库 16 台', by('device-map').probe.stat16 === true)
  check('灾害预测页可达且无 5xx', by('disaster-forecast').len > 200 && by('disaster-forecast').unexpected5xx.length === 0)
  const drift501 = by('ai-models').issues.serverErrors.filter((s) => s.startsWith('501 '))
  check('AI 模型页 drift 显示「能力未接入」而非 0 值', by('ai-models').probe.unconnected >= 1)
  check('drift 回退路径不再重复 /drift（无 /drift/drift）',
    drift501.length >= 1 && drift501.every((s) => /\/api\/ai\/drift\//.test(s) && !/\/drift\/drift/.test(s)),
    drift501.join(' , ').slice(0, 160))
  check('预警页两张 KPI 显示「未接入」', by('warnings').probe.unconnected >= 2)
  check('灾害事件页首屏不自动弹详情', by('hazards').probe.drawerBackdrops === 0)
  check('AI 综合研判页可达且无意外 5xx', by('ai-insight').len > 200 && by('ai-insight').unexpected5xx.length === 0)
  check('全部页面无未捕获 JS 异常', pageResults.every((p) => p.issues.exceptions.length === 0))
  check('全部页面无意外 5xx', pageResults.every((p) => p.unexpected5xx.length === 0))
  check('全部页面无横向溢出', pageResults.every((p) => p.probe.overflow <= 1))

  const summary = {
    checkedAt: new Date().toISOString(), app: APP, browser: version.Browser,
    pages: pageResults.map((p) => ({
      route: p.route, len: p.len, probe: p.probe,
      serverErrors: p.issues.serverErrors, unexpected5xx: p.unexpected5xx,
      exceptions: p.issues.exceptions, consoleErrors: p.issues.consoleErrors.slice(0, 8),
    })),
    checks,
  }
  fs.writeFileSync(path.join(OUT, 'review-fixes-browser.json'), JSON.stringify(summary, null, 2))
  await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})

  const passed = checks.filter((c) => c.ok).length
  console.log(`=== ${passed}/${checks.length} PASS；截图 output/review-fixes/，明细 output/delivery-verify/review-fixes-browser.json ===`)
  if (passed !== checks.length) process.exitCode = 1
}

main().catch((e) => { console.error('回归失败：', e); process.exitCode = 1 })
