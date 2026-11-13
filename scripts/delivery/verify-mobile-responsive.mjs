/**
 * A1-3 移动端响应式预检（真实浏览器 CDP，2026-11-13）。
 *
 * 目的：把「真机移动端」现场清单里能在本机判定的部分提前跑掉，
 * 现场只需确认真实设备上的触摸手感与声音/网络。
 *
 * 覆盖：4 档视口（iPhone 13 / Android / 平板 / 桌面基线）× 5 个关键路由，
 *  检查根元素横向溢出、空白页、JS 异常、控制台错误、5xx；
 *  另外检查侧栏抽屉、表格横向滚动容器、红色警报弹窗在窄屏下的布局与按钮可达。
 * 产出：output/delivery-verify/mobile-responsive.json + mobile-*.png 截图。
 *
 * 前置：nginx(dist) 25173 → 隔离后端 28083；CDP 9222 已启动。
 * 用法：node scripts/delivery/verify-mobile-responsive.mjs
 */
import fs from 'node:fs'
import path from 'node:path'

const APP = (process.env.E2E_APP_BASE || 'http://127.0.0.1:25173').replace(/\/$/, '')
const CDP = process.env.CDP_BASE || 'http://127.0.0.1:9222'
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const OUT = path.resolve('output/delivery-verify')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const VIEWPORTS = [
  { name: 'iphone13', label: 'iPhone 13', width: 390, height: 844, dsf: 3, mobile: true },
  { name: 'android', label: 'Android 常见', width: 360, height: 800, dsf: 2, mobile: true },
  { name: 'tablet', label: '平板', width: 768, height: 1024, dsf: 2, mobile: true },
  { name: 'desktop', label: '桌面基线', width: 1440, height: 900, dsf: 1, mobile: false },
]

const ROUTES = [
  ['/dashboard', '工作台首页'],
  ['/dashboard/warnings', '预警与处置'],
  ['/dashboard/hazards', '灾害点列表'],
  ['/dashboard/analytics', '分析报表'],
  ['/dashboard/satellite-compare', '时相对比'],
]

class Cdp {
  constructor(ws) {
    this.ws = ws; this.nextId = 1; this.pending = new Map(); this.events = []
    ws.addEventListener('message', (ev) => {
      const m = JSON.parse(ev.data)
      if (m.id && this.pending.has(m.id)) {
        const t = this.pending.get(m.id); this.pending.delete(m.id)
        m.error ? t.reject(new Error(m.error.message)) : t.resolve(m.result)
      } else if (m.method) this.events.push({ method: m.method, params: m.params })
    })
  }
  static async create() {
    const target = await (await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })).json()
    const ws = new WebSocket(target.webSocketDebuggerUrl)
    await new Promise((res, rej) => {
      ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', rej, { once: true })
    })
    return { client: new Cdp(ws), targetId: target.id }
  }
  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = this.nextId++; this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (this.pending.has(id)) { this.pending.delete(id); reject(new Error(`CDP 超时: ${method}`)) }
      }, 30000)
    })
  }
  async eval(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) {
      const d = r.exceptionDetails
      throw new Error(`页面脚本异常：${d.text} | ${d.exception?.description || d.exception?.value || ''}`)
    }
    return r.result?.value
  }
  async screenshot(file) {
    const r = await this.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
    fs.writeFileSync(path.join(OUT, file), Buffer.from(r.data, 'base64'))
  }
  take(from) {
    const slice = this.events.slice(from)
    return {
      serverErrors: slice.filter((e) => e.method === 'Network.responseReceived'
        && (e.params?.response?.status ?? 0) >= 500).map((e) => `${e.params.response.status} ${e.params.response.url}`),
      exceptions: slice.filter((e) => e.method === 'Runtime.exceptionThrown')
        .map((e) => e.params.exceptionDetails?.exception?.description || e.params.exceptionDetails?.text || 'unknown'),
      consoleErrors: slice.filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
        .map((e) => e.params.args?.map((a) => a.value ?? a.description).join(' ') || '')
        .filter((s) => !/favicon|404 \(Not Found\)/i.test(s)),
    }
  }
}

async function login() {
  const resp = await fetch(`${APP}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: PASSWORD }),
  }).then((r) => r.json())
  if (!resp?.data?.token) throw new Error('登录失败')
  return resp
}

async function createRedWarning(token, attempt = 1) {
  const body = {
    title: `A1-3 移动端弹窗预检 #${attempt}`, level: 'red', hazardCode: 'HZ-SBOX-04', hazardName: '移动端预检点',
    hazardType: 'waterlogging', region: '渝中区', lon: 106.53875, lat: 29.553875,
    bufferKm: 1.0, channels: ['APP'], content: '移动端布局预检', operator: 'admin',
  }
  const r = await fetch(`${APP}/api/warnings`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  }).then((r) => r.json())
  return r?.data?.id ?? null
}

async function waitReady(client, tries = 12) {
  for (let i = 0; i < tries; i++) {
    try {
      if (await client.eval(`document.readyState === 'complete' && !!document.body`)) return
    } catch (_) { /* 导航中上下文被销毁，重试 */ }
    await sleep(400)
  }
  throw new Error('页面就绪超时')
}

/** 等页面真正渲染出内容（隔离库数据变多后首屏可能仍是骨架屏），避免误判空白页 */
async function waitContent(client, minLen = 100, tries = 14) {
  for (let i = 0; i < tries; i++) {
    try { if (await client.eval(`document.body.innerText.length >= ${minLen}`)) return true }
    catch (_) { /* 上下文切换 */ }
    await sleep(400)
  }
  return false
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  console.log(`=== A1-3 移动端响应式预检 (${APP}) ===`)
  const auth = await login()
  const { client, targetId } = await Cdp.create()
  await client.send('Page.enable'); await client.send('Runtime.enable'); await client.send('Network.enable')

  await client.send('Page.navigate', { url: APP + '/' })
  await sleep(1200)
  await waitReady(client)
  await client.eval(`(() => {
    localStorage.setItem('disasterguard_token', ${JSON.stringify(auth.data.token)});
    localStorage.setItem('disasterguard_user', ${JSON.stringify(JSON.stringify(auth.data.user))});
    return true;
  })()`)

  const rows = []
  for (const vp of VIEWPORTS) {
    await client.send('Emulation.setDeviceMetricsOverride', {
      width: vp.width, height: vp.height, deviceScaleFactor: vp.dsf, mobile: vp.mobile,
    })
    await client.send('Emulation.setTouchEmulationEnabled', { enabled: vp.mobile, maxTouchPoints: 5 })

    for (const [route, label] of ROUTES) {
      const from = client.events.length
      await client.send('Page.navigate', { url: APP + route })
      await waitReady(client)
      await waitContent(client)
      await sleep(350)
      const state = await client.eval(`(() => {
        const root = document.documentElement
        const tables = [...document.querySelectorAll('table')]
        const scrollHosts = [...document.querySelectorAll('*')].filter(el => {
          const s = getComputedStyle(el)
          return el.scrollWidth > el.clientWidth + 8 && /auto|scroll/.test(s.overflowX)
        }).length
        const sidebar = document.querySelector('[class*="sidebar"],[class*="Sidebar"],aside,[class*="drawer"]')
        const navToggle = [...document.querySelectorAll('button,[role=button]')].find(b =>
          /菜单|menu|导航/.test((b.getAttribute('aria-label') || '') + ' ' + (b.title || '')))
        return {
          path: location.pathname,
          innerWidth: window.innerWidth,
          scrollWidth: root.scrollWidth,
          textLen: document.body.innerText.length,
          horizontalOverflow: root.scrollWidth - window.innerWidth,
          tableCount: tables.length,
          scrollHosts,
          hasSidebar: !!sidebar,
          sidebarVisible: sidebar ? getComputedStyle(sidebar).display !== 'none' : false,
          hasNavToggle: !!navToggle,
          buttons: [...document.querySelectorAll('button')].filter(b => !b.disabled).length,
        }
      })()`)
      const issues = client.take(from)
      const bad = issues.serverErrors.length + issues.exceptions.length + issues.consoleErrors.length
      const overflow = state.horizontalOverflow > 2
      rows.push({ viewport: vp.name, viewportLabel: vp.label, ...vp, route, label, ...state, ...issues,
        overflow, blank: state.textLen < 100 })
      console.log(`  ${overflow || state.textLen < 100 || bad ? 'WARN' : 'OK  '} ${vp.name.padEnd(8)} ${route.padEnd(34)} 溢出=${String(state.horizontalOverflow).padStart(4)} 文案=${String(state.textLen).padStart(5)} 表=${state.tableCount} 滚动容器=${state.scrollHosts}${bad ? ` 问题=${bad}` : ''}`)
    }

    // 每个视口留两张截图：首页 + 预警列表
    for (const [route, tag] of [['/dashboard', 'home'], ['/dashboard/warnings', 'warnings']]) {
      await client.send('Page.navigate', { url: APP + route })
      await waitReady(client); await waitContent(client); await sleep(300)
      await client.screenshot(`mobile-${vp.name}-${tag}.png`)
    }
  }

  // ── 4.5 红色警报弹窗在窄屏下的布局 ──
  // 用全新页面上下文：前面 20 次密集路由导航会让 realtimeService 反复启停，
  // 与「首个列表快照只做基线登记」的时序互相干扰。
  const narrow = VIEWPORTS[1] // Android 360×800，最窄
  const { client: alarmClient, targetId: alarmTargetId } = await Cdp.create()
  await alarmClient.send('Page.enable'); await alarmClient.send('Runtime.enable'); await alarmClient.send('Network.enable')
  await alarmClient.send('Emulation.setDeviceMetricsOverride', {
    width: narrow.width, height: narrow.height, deviceScaleFactor: narrow.dsf, mobile: true,
  })
  await alarmClient.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 })
  await alarmClient.send('Page.navigate', { url: APP + '/' })
  await waitReady(alarmClient)
  await alarmClient.eval(`(() => {
    localStorage.setItem('disasterguard_token', ${JSON.stringify(auth.data.token)});
    localStorage.setItem('disasterguard_user', ${JSON.stringify(JSON.stringify(auth.data.user))});
    return true;
  })()`)
  await alarmClient.send('Page.navigate', { url: APP + '/dashboard' })
  await waitReady(alarmClient)
  await waitContent(alarmClient)
  await sleep(3500) // 等 WS 101 握手 + 实时通道稳定，避免推送落在连接建立前

  // useWarningAlarm 的首个预警列表快照只做基线登记（不弹历史预警），
  // 必须等它到达后再创建预警，否则新预警会被当成基线吞掉。
  const baselineFrom = alarmClient.events.length
  let baselineSeen = false
  for (let i = 0; i < 30; i++) {
    await sleep(500)
    baselineSeen = alarmClient.events.slice(baselineFrom).some((e) =>
      e.method === 'Network.responseReceived' && /\/api\/warnings\?/.test(e.params?.response?.url || ''))
    if (baselineSeen) break
  }
  console.log(`  基线对齐完成（${baselineSeen ? '观察到列表快照' : '未观察到快照，按 WS 即时推送路径继续'}；不参与判定）`)
  await sleep(1500)

  // 预警列表轮询默认 10s（VITE_WARNING_POLL_INTERVAL）；WS 推送命中时会更快
  const ALARM_PROBE = `(() => {
    const overlay = document.querySelector('.alarm-overlay')
    if (!overlay) return { present: false }
    const r = overlay.getBoundingClientRect()
    const btns = [...overlay.querySelectorAll('button')].map(b => {
      const q = b.getBoundingClientRect()
      return { text: b.innerText.trim(), inside: q.left >= -1 && q.right <= innerWidth + 1 && q.top >= -1 && q.bottom <= innerHeight + 1 }
    })
    return {
      present: true,
      overlayInside: r.left >= -1 && r.top >= -1 && r.right <= innerWidth + 1 && r.bottom <= innerHeight + 1,
      buttons: btns,
      levelText: overlay.querySelector('.alarm-level')?.innerText || '',
    }
  })()`

  let warnId = null
  let attempts = 0
  let alarmLayout = { present: false }
  let waitedMs = 0
  // WS 推送窗口较窄；若首次落在连接建立前，重新创建一条即可命中下一轮实时流
  for (let attempt = 1; attempt <= 3 && !alarmLayout.present; attempt++) {
    attempts = attempt
    warnId = await createRedWarning(auth.data.token, attempt)
    for (let i = 0; i < 24; i++) {
      await sleep(500); waitedMs += 500
      try {
        alarmLayout = await alarmClient.eval(ALARM_PROBE)
        if (alarmLayout.present) break
      } catch (_) { /* 上下文切换，继续等 */ }
    }
    if (!alarmLayout.present) console.log(`  第 ${attempt} 次未触发弹窗，重试创建…`)
  }
  console.log(`  红色弹窗：${alarmLayout.present ? `出现（第 ${attempts} 次创建，累计等待 ${waitedMs} ms）` : `未出现（3 次尝试，累计 ${waitedMs} ms）`}`)
  await alarmClient.screenshot('mobile-android-red-alarm.png')
  // 收掉弹窗，避免影响后续脚本
  await alarmClient.eval(`(() => { const b=[...document.querySelectorAll('.alarm-btn')].find(x=>x.innerText.includes('确认收到')); if(b) b.click(); return true })()`)
  await sleep(600)

  const summary = {
    checkedAt: new Date().toISOString(),
    app: APP,
    viewports: VIEWPORTS.length,
    routes: ROUTES.length,
    checks: rows.length,
    overflowPages: rows.filter((r) => r.overflow).map((r) => `${r.viewport}${r.route} +${r.horizontalOverflow}px`),
    blankPages: rows.filter((r) => r.blank).map((r) => `${r.viewport}${r.route}`),
    serverErrors: rows.filter((r) => r.serverErrors.length).map((r) => ({ viewport: r.viewport, route: r.route, errors: r.serverErrors.slice(0, 3) })),
    exceptions: rows.filter((r) => r.exceptions.length).map((r) => ({ viewport: r.viewport, route: r.route, errors: r.exceptions.slice(0, 2) })),
    consoleErrors: rows.filter((r) => r.consoleErrors.length).map((r) => ({ viewport: r.viewport, route: r.route, errors: r.consoleErrors.slice(0, 2) })),
    alarmLayout,
    baselineSnapshotObserved: baselineSeen,
    alarmAttempts: attempts,
    alarmWaitMs: waitedMs,
    createdWarningId: warnId,
    screenshots: VIEWPORTS.flatMap((v) => [`mobile-${v.name}-home.png`, `mobile-${v.name}-warnings.png`]).concat('mobile-android-red-alarm.png'),
    rows,
  }
  fs.writeFileSync(path.join(OUT, 'mobile-responsive.json'), JSON.stringify(summary, null, 2))
  await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})
  await fetch(`${CDP}/json/close/${alarmTargetId}`).catch(() => {})

  const pass = [
    ['横向溢出', summary.overflowPages.length === 0],
    ['空白页', summary.blankPages.length === 0],
    ['5xx', summary.serverErrors.length === 0],
    ['未捕获异常', summary.exceptions.length === 0],
    ['控制台错误', summary.consoleErrors.length === 0],
    ['红色弹窗出现', alarmLayout.present === true],
    ['弹窗不溢出视口', alarmLayout.overlayInside === true],
    ['弹窗按钮全部可点', Array.isArray(alarmLayout.buttons) && alarmLayout.buttons.length > 0 && alarmLayout.buttons.every((b) => b.inside)],
  ]
  console.log('\n=== 判定 ===')
  pass.forEach(([k, ok]) => console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${k}`))
  console.log(`  明细：output/delivery-verify/mobile-responsive.json；截图 ${summary.screenshots.length} 张`)
  console.log(`=== ${pass.filter(([, ok]) => ok).length}/${pass.length} PASS ===`)
  if (pass.some(([, ok]) => !ok)) process.exit(1)
}

main().catch((e) => { console.error(`=== FAIL: ${e.message} ===`); process.exit(1) })
