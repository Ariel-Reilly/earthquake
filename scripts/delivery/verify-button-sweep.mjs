/**
 * A1-4 页面按钮 / 路由全量点检预检（真实浏览器 CDP）。
 *
 * 前置：nginx(dist) 25173 → 新网关后端；CDP 9222 已启动。
 * 用法：node scripts/delivery/verify-button-sweep.mjs
 *
 * 覆盖：逐个加载关键业务路由，统计可点击按钮/输入控件/弹窗，记录 5xx、未捕获异常、
 *      控制台错误；对「刷新 / 查询」这类无副作用按钮做一次真实点击。
 * 边界：关闭预警、确认会签、发起派发、导出下载等**有副作用/需真实数据与真机**的
 *      交互不做自动点击，留在 ONSITE_ACCEPTANCE_CHECKLIST.md 现场人工清单。
 */
import fs from 'node:fs'
import path from 'node:path'

const APP = (process.env.E2E_APP_BASE || 'http://127.0.0.1:25173').replace(/\/$/, '')
const CDP = process.env.CDP_BASE || 'http://127.0.0.1:9222'
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const OUT = path.resolve('output/delivery-verify')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const ROUTES = [
  ['/dashboard', '工作台/首页'],
  ['/dashboard/warnings', '预警与处置'],
  ['/dashboard/confirmation-workbench', '确认工作台'],
  ['/dashboard/confirmation-sign', '会签'],
  ['/dashboard/ai-insight', 'AI 洞察/发布向导'],
  ['/dashboard/notification-channels', '通知渠道'],
  ['/dashboard/notice-templates', '通知模板'],
  ['/dashboard/analytics', '分析报表'],
  ['/dashboard/audit-log', '审计日志'],
  ['/dashboard/devices', '设备管理'],
  ['/dashboard/hazards', '灾害点'],
  ['/dashboard/events', '事件'],
  ['/dashboard/emergency', '应急响应'],
  ['/dashboard/satellite-compare', '时相对比'],
  ['/dashboard/settings', '系统设置'],
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
    if (r.exceptionDetails) throw new Error(`页面脚本异常：${r.exceptionDetails.text}`)
    return r.result?.value
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

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  console.log(`=== A1-4 页面按钮/路由点检预检 (${APP}) ===`)

  const login = await fetch(`${APP}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: PASSWORD }),
  }).then((r) => r.json())
  const token = login?.data?.token
  if (!token) throw new Error('登录失败')

  const { client, targetId } = await Cdp.create()
  await client.send('Page.enable'); await client.send('Runtime.enable'); await client.send('Network.enable')
  await client.send('Page.navigate', { url: APP + '/' }); await sleep(1800)
  await client.eval(`(() => {
    localStorage.setItem('disasterguard_token', ${JSON.stringify(token)});
    localStorage.setItem('disasterguard_user', ${JSON.stringify(JSON.stringify(login.data.user))});
    return true;
  })()`)

  const rows = []
  for (const [route, label] of ROUTES) {
    const from = client.events.length
    await client.send('Page.navigate', { url: APP + route })
    await sleep(1400)
    const state = await client.eval(`(() => ({
      path: location.pathname,
      textLen: document.body.innerText.length,
      buttons: [...document.querySelectorAll('button')].filter(b => !b.disabled).length,
      inputs: document.querySelectorAll('input,select,textarea').length,
      dialogs: document.querySelectorAll('[role=dialog],.el-dialog,.modal').length,
    }))()`)

    // 只点无副作用的刷新/查询类按钮
    const clicked = await client.eval(`(() => {
      const b = [...document.querySelectorAll('button')].find(x => !x.disabled && /刷新|查询/.test(x.innerText));
      if (!b) return null;
      const label = b.innerText.trim().slice(0, 20);
      b.click();
      return label;
    })()`)
    if (clicked) await sleep(900)

    const issues = client.take(from)
    rows.push({ route, label, ...state, clicked, ...issues })
    const bad = issues.serverErrors.length + issues.exceptions.length + issues.consoleErrors.length
    console.log(`  ${bad ? 'WARN' : 'OK  '}  ${route.padEnd(38)} 按钮=${String(state.buttons).padStart(3)} 控件=${String(state.inputs).padStart(3)} 文案=${String(state.textLen).padStart(5)}${clicked ? ` 点击=${clicked}` : ''}${bad ? ` 问题=${bad}` : ''}`)
  }

  const summary = {
    checkedAt: new Date().toISOString(),
    routes: rows.length,
    totalButtons: rows.reduce((s, r) => s + r.buttons, 0),
    redirectedToLogin: rows.filter((r) => r.path.includes('/login')).map((r) => r.route),
    blankPages: rows.filter((r) => r.textLen < 100).map((r) => r.route),
    serverErrors: rows.filter((r) => r.serverErrors.length).map((r) => ({ route: r.route, errors: r.serverErrors.slice(0, 3) })),
    exceptions: rows.filter((r) => r.exceptions.length).map((r) => ({ route: r.route, errors: r.exceptions.slice(0, 2) })),
    consoleErrors: rows.filter((r) => r.consoleErrors.length).map((r) => ({ route: r.route, errors: r.consoleErrors.slice(0, 2) })),
    rows,
  }
  fs.writeFileSync(path.join(OUT, 'button-sweep.json'), JSON.stringify(summary, null, 2))
  await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})

  console.log(`=== 汇总：${rows.length} 个路由 / ${summary.totalButtons} 个可点击按钮 ===`)
  console.log(`  跳登录=${summary.redirectedToLogin.length} 空白页=${summary.blankPages.length} 5xx=${summary.serverErrors.length} 异常=${summary.exceptions.length} 控制台错误=${summary.consoleErrors.length}`)
  console.log(`  明细：output/delivery-verify/button-sweep.json`)
  const failed = summary.redirectedToLogin.length + summary.blankPages.length + summary.serverErrors.length + summary.exceptions.length
  if (failed) process.exit(1)
}

main().catch((e) => { console.error(`=== FAIL: ${e.message} ===`); process.exit(1) })
