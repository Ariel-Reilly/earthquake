/**
 * B1-5 真实浏览器页面验证（Edge/Chrome CDP，无 Playwright/Puppeteer）。
 *
 * 前置：
 *   1) 前端 dev server 5173、后端 8080 已启动；
 *   2) msedge --headless=new --remote-debugging-port=9222 --user-data-dir=<profile>
 * 用法：node scripts/delivery/verify-satellite-page.mjs
 *
 * 覆盖：登录态注入 → /dashboard/satellite-compare 页面渲染（标题/表单/空态/时区提示）
 *      → 灾害点下拉 → 点击「开始对比」后的错误/降级态 → 控制台异常 → 截图。
 * 说明：若后端尚未部署本轮 Java 网关（旧 jar），点击后应显示可读的错误态而不是白屏，
 *      这也属于本用例的断言之一。截图输出到 output/delivery-verify/。
 */
import fs from 'node:fs'
import path from 'node:path'

const APP = process.env.APP_BASE || 'http://127.0.0.1:5173'
const API = process.env.API_BASE || 'http://127.0.0.1:8080/api'
const CDP = process.env.CDP_BASE || 'http://127.0.0.1:9222'
const OUT = path.resolve('output/delivery-verify')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

class CdpClient {
  constructor(ws) {
    this.ws = ws
    this.nextId = 1
    this.pending = new Map()
    this.consoleErrors = []
    this.exceptions = []
    ws.addEventListener('message', (event) => {
      const msg = JSON.parse(event.data)
      if (msg.id && this.pending.has(msg.id)) {
        const task = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error ? task.reject(new Error(msg.error.message)) : task.resolve(msg.result)
      } else if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
        this.consoleErrors.push(msg.params.args?.map((a) => a.value ?? a.description).join(' ') || '')
      } else if (msg.method === 'Runtime.exceptionThrown') {
        this.exceptions.push(msg.params.exceptionDetails?.text || 'unknown')
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

  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = this.nextId++
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id)
          reject(new Error(`CDP 超时: ${method}`))
        }
      }, 20000)
    })
  }

  async eval(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) {
      throw new Error(`页面脚本异常：${r.exceptionDetails.text}`)
    }
    return r.result?.value
  }

  async screenshot(file) {
    const r = await this.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
    fs.writeFileSync(path.join(OUT, file), Buffer.from(r.data, 'base64'))
  }
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  console.log('=== B1-5 真实浏览器页面验证 ===')

  // ① 登录态（使用现有 dev 后端，token 仅用于前端鉴权头）
  const login = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  }).then((r) => r.json())
  if (!login?.data?.token) {throw new Error(`登录失败：${JSON.stringify(login).slice(0, 200)}`)}
  console.log(`  登录成功（admin），token ${login.data.token.length} 字符`)

  const { client, version, targetId } = await CdpClient.create()
  console.log(`  CDP: ${version.Browser}`)
  await client.send('Page.enable')
  await client.send('Runtime.enable')

  // ② 先落在同源页面写 localStorage，再进目标路由
  await client.send('Page.navigate', { url: APP + '/' })
  await sleep(1800)
  await client.eval(`(() => {
    localStorage.setItem('disasterguard_token', ${JSON.stringify(login.data.token)});
    localStorage.setItem('disasterguard_user', ${JSON.stringify(JSON.stringify(login.data.user))});
    return true;
  })()`)

  await client.send('Page.navigate', { url: APP + '/dashboard/satellite-compare' })
  await sleep(2600)

  const state = await client.eval(`(() => ({
    url: location.pathname,
    title: document.title,
    text: document.body.innerText.slice(0, 4000),
    hazards: document.querySelectorAll('select')[0]?.options?.length ?? 0,
    startBtn: [...document.querySelectorAll('button')].find(b => b.innerText.includes('开始对比'))?.disabled ?? null,
    dateInputs: document.querySelectorAll('input[type=date]').length,
  }))()`)

  const checks = []
  const check = (name, ok, detail = '') => {
    checks.push({ name, ok, detail })
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
  }
  check('路由停在 /dashboard/satellite-compare', state.url === '/dashboard/satellite-compare', state.url)
  check('页面标题渲染', state.text.includes('时相对比（灾前 / 灾后）'))
  check('表单：灾害点下拉有数据', state.hazards > 1, `${state.hazards} 个选项`)
  check('表单：两个时间窗口共 4 个日期控件', state.dateInputs === 4, `${state.dateInputs} 个`)
  check('空态提示可见', state.text.includes('尚未开始对比'))
  check('诚实降级说明可见', state.text.includes('不生成或展示演示差异图') || state.text.includes('不展示'))
  check('浏览器时区口径可见', state.text.includes('浏览器本地时区'))
  await client.screenshot('satellite-compare-initial.png')

  // ③ 点击「开始对比」：旧后端（无网关）应给出可读错误态，而不是白屏/死循环
  if (state.startBtn === false) {
    await client.eval(`[...document.querySelectorAll('button')].find(b => b.innerText.includes('开始对比')).click()`)
    await sleep(3000)
    const after = await client.eval(`document.body.innerText.slice(0, 4000)`)
    check('点击后进入错误/降级态（无白屏）',
      after.includes('时相对比未完成') || after.includes('未取得真实卫星影像') || after.includes('处理中'),
      (after.match(/时相对比未完成[^\n]*/) || [''])[0].slice(0, 120))
    await client.screenshot('satellite-compare-after-submit.png')
  } else {
    console.log('  SKIP  点击交互（按钮 disabled，未取得有效 AOI）')
  }

  check('无未捕获页面异常', client.exceptions.length === 0, client.exceptions.slice(0, 2).join(' | '))
  const consoleErrors = client.consoleErrors.filter((e) => !/favicon|404 \(Not Found\)/i.test(e))
  check('控制台无业务异常', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '))

  await fetch(`${CDP}/json/close/${targetId}`, { method: 'GET' }).catch(() => {})
  const failed = checks.filter((c) => !c.ok)
  console.log(`=== ${failed.length ? 'FAIL' : 'PASS'}：${checks.length - failed.length}/${checks.length} 项通过，截图在 ${OUT} ===`)
  if (failed.length) {process.exit(1)}
}

main().catch((e) => {
  console.error(`=== FAIL: ${e.message} ===`)
  process.exit(1)
})
