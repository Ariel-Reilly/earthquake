/**
 * A1-5 演示流程自动化预演（2026-11-13）。
 *
 * 按 docs/delivery/DEMO_SCRIPT.md 第二节的页面顺序自动走一遍，记录每一步的
 * 加载耗时、关键元素、5xx / 未捕获异常 / 控制台错误，提前暴露「彩排会卡住」的页面问题。
 *
 * 诚实边界：脚本测的是**纯页面加载与交互耗时**，不含人工讲解、话术与停顿，
 * 因此不能替代现场「完整走 2 遍、计时 ≤20 分钟」的彩排；
 * 现场仍需按 ONSITE_ACCEPTANCE_CHECKLIST.md 第 8 节人工计时与确认话术。
 *
 * 前置：nginx(dist) 25173 → 隔离后端 28083；CDP 9222 已启动。
 * 用法：node scripts/delivery/verify-demo-walkthrough.mjs
 */
import fs from 'node:fs'
import path from 'node:path'

const APP = (process.env.E2E_APP_BASE || 'http://127.0.0.1:25173').replace(/\/$/, '')
const CDP = process.env.CDP_BASE || 'http://127.0.0.1:9222'
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
// 演练后端可能以 AI_ENABLED=false 启动（只为验证前端降级，不加载模型）。
// 此时 /api/ai/** 503 属**预期降级**，单独归类；默认（现场/真实环境）仍严格按 5xx 判失败。
const EXPECT_AI_DISABLED = process.env.EXPECT_AI_DISABLED === '1'
const OUT = path.resolve('output/delivery-verify')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

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
      throw new Error(`页面脚本异常：${d.text} | ${d.exception?.description || ''}`)
    }
    return r.result?.value
  }
  issues(from) {
    const slice = this.events.slice(from)
    const noisy = /favicon|404 \(Not Found\)|net::ERR_/i
    const all5xx = slice.filter((e) => e.method === 'Network.responseReceived'
      && (e.params?.response?.status ?? 0) >= 500)
    const aiDegraded = EXPECT_AI_DISABLED
      ? all5xx.filter((e) => e.params.response.status === 503 && /\/api\/ai\//.test(e.params.response.url))
      : []
    const real = all5xx.filter((e) => !aiDegraded.includes(e))
    return {
      serverErrors: real.map((e) => `${e.params.response.status} ${e.params.response.url}`),
      aiDegradedRequests: aiDegraded.map((e) => `${e.params.response.status} ${e.params.response.url}`),
      exceptions: slice.filter((e) => e.method === 'Runtime.exceptionThrown')
        .map((e) => e.params.exceptionDetails?.exception?.description || e.params.exceptionDetails?.text || 'unknown'),
      consoleErrors: slice.filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
        .map((e) => e.params.args?.map((a) => a.value ?? a.description).join(' ') || '')
        .filter((s) => !noisy.test(s)),
    }
  }
}

async function waitReady(client, tries = 12) {
  for (let i = 0; i < tries; i++) {
    try { if (await client.eval(`document.readyState === 'complete' && !!document.body`)) return }
    catch (_) { /* 导航中 */ }
    await sleep(350)
  }
  throw new Error('页面就绪超时')
}

const DISMISS_ALARM = `(() => {
  const b = [...document.querySelectorAll('.alarm-btn')].find(x => x.innerText.includes('确认收到'))
  if (b) { b.click(); return true }
  return false
})()`

const PROBE = `(() => ({
  path: location.pathname,
  textLen: document.body.innerText.length,
  headings: document.querySelectorAll('h1,h2,h3').length,
  buttons: [...document.querySelectorAll('button')].filter(b => !b.disabled).length,
  canvases: document.querySelectorAll('canvas').length,
  charts: document.querySelectorAll('svg,[class*="chart"],[class*="Chart"]').length,
  cards: document.querySelectorAll('[class*="card"],[class*="Card"]').length,
  tabHits: [...document.querySelectorAll('[role=tab],button')].filter(b => /AI 分析详情|处置时间轴|通知与送达/.test(b.innerText)).length,
  hasLoadingError: /加载失败|接口异常|服务不可用/.test(document.body.innerText),
}))()`

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  console.log(`=== A1-5 演示流程自动化预演 (${APP}) ===`)
  const auth = await fetch(`${APP}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: PASSWORD }),
  }).then((r) => r.json())
  const token = auth?.data?.token
  if (!token) throw new Error('登录失败')

  const list = await fetch(`${APP}/api/warnings?page=1&size=20`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then((r) => r.json())
  const rows = list?.data?.records ?? (Array.isArray(list?.data) ? list.data : [])
  const firstId = rows[0]?.id
  if (!firstId) throw new Error('预警列表为空，无法预演详情页')

  const { client, targetId } = await Cdp.create()
  await client.send('Page.enable'); await client.send('Runtime.enable'); await client.send('Network.enable')
  await client.send('Page.navigate', { url: APP + '/' })
  await waitReady(client)
  await client.eval(`(() => {
    localStorage.setItem('disasterguard_token', ${JSON.stringify(token)});
    localStorage.setItem('disasterguard_user', ${JSON.stringify(JSON.stringify(auth.data.user))});
    return true;
  })()`)

  const STEPS = [
    ['0. 开场 / 综合态势首页', '/dashboard', null],
    ['1. 预警中心与四档颜色', '/dashboard/warnings', null],
    ['2. 预警详情', `/dashboard/warnings/${firstId}`, null],
    ['3. AI 分析详情 Tab', `/dashboard/warnings/${firstId}`, 'AI 分析详情'],
    ['4. 处置时间轴 Tab', `/dashboard/warnings/${firstId}`, '处置时间轴'],
    ['5. 通知与送达 Tab', `/dashboard/warnings/${firstId}`, '通知与送达'],
    ['6. 实时地图', '/dashboard/real-time-map', null],
    ['7. 设备管理', '/dashboard/devices', null],
    ['8. 审计日志', '/dashboard/audit-log', null],
    ['9. 时相对比', '/dashboard/satellite-compare', null],
    ['10. 会签', '/dashboard/confirmation-sign', null],
    ['11. AI 洞察 / 发布向导', '/dashboard/ai-insight', null],
    ['12. 收尾（回到首页）', '/dashboard', null],
  ]

  const results = []
  let lastRoute = null
  for (const [name, route, clickText] of STEPS) {
    const from = client.events.length
    const t0 = Date.now()
    if (route !== lastRoute) {
      await client.send('Page.navigate', { url: APP + route })
      await waitReady(client)
      await sleep(900)
      lastRoute = route
    }
    await client.eval(DISMISS_ALARM)
    let clicked = null
    if (clickText) {
      clicked = await client.eval(`(() => {
        const b = [...document.querySelectorAll('[role=tab],button')].find(x => !x.disabled && x.innerText.includes(${JSON.stringify(clickText)}))
        if (!b) return false
        b.click(); return true
      })()`)
      await sleep(900)
    }
    const state = await client.eval(PROBE)
    const issues = client.issues(from)
    const elapsed = Date.now() - t0
    const bad = issues.serverErrors.length + issues.exceptions.length + issues.consoleErrors.length
    results.push({ name, route, clickText, clicked, elapsedMs: elapsed, ...state, ...issues })
    console.log(`  ${bad || state.textLen < 100 ? 'WARN' : 'OK  '} ${name.padEnd(24)} ${String(elapsed).padStart(5)}ms 文案=${String(state.textLen).padStart(5)} 卡=${state.cards} 图=${state.charts} 画布=${state.canvases}${clicked === false ? ' [未找到Tab]' : ''}${bad ? ` 问题=${bad}` : ''}${issues.aiDegradedRequests.length ? ` [AI关闭降级=${issues.aiDegradedRequests.length}]` : ''}`)
  }

  const totalMs = results.reduce((s, r) => s + r.elapsedMs, 0)
  const summary = {
    checkedAt: new Date().toISOString(), app: APP, demoScript: 'docs/delivery/DEMO_SCRIPT.md',
    steps: results.length, firstWarningId: firstId,
    expectAiDisabled: EXPECT_AI_DISABLED,
    totalAutomatedMs: totalMs,
    totalAutomatedSec: Number((totalMs / 1000).toFixed(1)),
    blankPages: results.filter((r) => r.textLen < 100).map((r) => r.name),
    missingTabs: results.filter((r) => r.clickText && r.clicked === false).map((r) => `${r.name}: ${r.clickText}`),
    serverErrors: results.filter((r) => r.serverErrors.length).flatMap((r) => r.serverErrors.map((e) => `${r.name} ${e}`)),
    aiDegradedRequests: results.filter((r) => r.aiDegradedRequests.length).flatMap((r) => r.aiDegradedRequests.map((e) => `${r.name} ${e}`)),
    exceptions: results.filter((r) => r.exceptions.length).flatMap((r) => r.exceptions.map((e) => `${r.name} ${e}`)),
    consoleErrors: results.filter((r) => r.consoleErrors.length).flatMap((r) => r.consoleErrors.map((e) => `${r.name} ${e}`)),
    loadingErrorPages: results.filter((r) => r.hasLoadingError).map((r) => r.name),
    results,
  }
  fs.writeFileSync(path.join(OUT, 'demo-walkthrough.json'), JSON.stringify(summary, null, 2))
  await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})

  const pass = [
    ['全部步骤页面不空白', summary.blankPages.length === 0],
    ['AI 分析 / 时间轴 / 送达 Tab 均可找到并点击', summary.missingTabs.length === 0],
    ['无非预期 5xx', summary.serverErrors.length === 0],
    ['无未捕获异常', summary.exceptions.length === 0],
    ['无控制台错误', summary.consoleErrors.length === 0],
  ]
  console.log('\n=== 判定（自动化预演部分）===')
  pass.forEach(([k, ok]) => console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${k}`))
  if (summary.aiDegradedRequests.length) {
    console.log(`  NOTE  演练后端 AI_ENABLED=false：${summary.aiDegradedRequests.length} 个 /api/ai/** 503 属预期降级，页面按 DEMO_SCRIPT 显示「加载失败+重试」而非假数据；现场启动 AI 服务后应消失`)
    console.log(`        ${summary.aiDegradedRequests[0]}`)
  }
  if (summary.loadingErrorPages.length) {
    console.log(`  NOTE  出现「加载失败」文案的页面：${summary.loadingErrorPages.join('、')}（AI 关闭时的设计内降级，非缺陷）`)
  }
  console.log(`  纯页面加载累计 ${summary.totalAutomatedSec}s（不含人工讲解；现场彩排仍需人工计时 ≤20 分钟）`)
  console.log(`=== ${pass.filter(([, ok]) => ok).length}/${pass.length} PASS；明细 output/delivery-verify/demo-walkthrough.json ===`)
  if (pass.some(([, ok]) => !ok)) process.exit(1)
}

main().catch((e) => { console.error(`=== FAIL: ${e.message} ===`); process.exit(1) })
