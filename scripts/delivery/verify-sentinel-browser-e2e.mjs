/**
 * SM-19 真实浏览器端到端验收：nginx(dist) → Java 网关 → ai-service。
 *
 * 前置：
 *   1) 生产构建产物 frontend/dist 已由 nginx 反代（默认 http://127.0.0.1:25173）；
 *   2) nginx /api/ 指向部署了本轮 jar 的后端，后端 AI_SERVICE_BASE_URL 指向 ai-service；
 *   3) Edge/Chrome headless CDP 9222 已启动。
 * 用法：node scripts/delivery/verify-sentinel-browser-e2e.mjs
 *
 * 与 verify-satellite-page.mjs 的区别：那个脚本连的是旧 dev 后端（只验证错误态），
 * 本脚本连的是**新网关**，验证真实链路下的诚实降级：dataSource=mock 时页面必须
 * 显示「未取得真实卫星影像」，且不得渲染任何假图 / 提交 diff。
 */
import fs from 'node:fs'
import path from 'node:path'

const APP = (process.env.E2E_APP_BASE || 'http://127.0.0.1:25173').replace(/\/$/, '')
const CDP = process.env.CDP_BASE || 'http://127.0.0.1:9222'
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const OUT = path.resolve('output/delivery-verify')
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
      throw new Error(`页面脚本异常：${r.exceptionDetails.text} / ${r.exceptionDetails.exception?.description || ''}`)
    }
    return r.result?.value
  }

  async screenshot(file) {
    const r = await this.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
    fs.writeFileSync(path.join(OUT, file), Buffer.from(r.data, 'base64'))
  }
}

async function api(path, opts = {}) {
  const { token, method = 'GET', body } = opts
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const resp = await fetch(`${APP}/api${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return { status: resp.status, json: await resp.json().catch(() => null) }
}

async function waitJob(path, id, token, timeoutMs = 150000) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    const r = await api(`${path}/${id}`, { token })
    if (['succeeded', 'failed', 'cancelled'].includes(r.json?.status)) {
      if (r.json.status !== 'succeeded') throw new Error(`任务未成功：${r.json.status}`)
      return r.json
    }
    await sleep(700)
  }
  throw new Error('任务轮询超时')
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  console.log(`=== SM-19 真实浏览器端到端验收 (${APP}) ===`)

  const login = await api('/auth/login', {
    method: 'POST',
    body: { username: 'admin', password: PASSWORD },
  })
  const token = login.json?.data?.token
  if (!token) throw new Error(`登录失败：HTTP ${login.status} ${JSON.stringify(login.json).slice(0, 160)}`)
  console.log(`  nginx → 新网关登录成功，token ${token.length} 字符`)

  // 先在 Node 侧拿一张真实代理图片 URL，供浏览器侧验证「带 JWT 的 fetch → blob」路径
  const body = (start, end) => ({
    bbox: [103.30, 30.80, 103.80, 31.20],
    start, end, max_cloud: 50, provider: 'mock', sar_overlay: false,
  })
  const before = await api('/ai/sentinel/compose', { token, method: 'POST', body: body('2025-09-01', '2025-09-30') })
  const job = await waitJob('/ai/sentinel/jobs', before.json.jobId, token)
  const imageUrl = job.result?.s2Url
  check('网关返回同源图片地址', typeof imageUrl === 'string' && imageUrl.startsWith('/api/ai/sentinel/image/'), imageUrl)

  const { client, version, targetId } = await CdpClient.create()
  console.log(`  CDP: ${version.Browser}`)
  await client.send('Page.enable')
  await client.send('Runtime.enable')

  // 同源注入登录态后进入目标路由
  await client.send('Page.navigate', { url: APP + '/' })
  await sleep(2000)
  await client.eval(`(() => {
    localStorage.setItem('disasterguard_token', ${JSON.stringify(token)});
    localStorage.setItem('disasterguard_user', ${JSON.stringify(JSON.stringify(login.json.data.user))});
    return true;
  })()`)

  await client.send('Page.navigate', { url: APP + '/dashboard/satellite-compare' })
  await sleep(2800)

  const initial = await client.eval(`(() => ({
    url: location.pathname,
    text: document.body.innerText.slice(0, 5000),
    hazards: document.querySelectorAll('select')[0]?.options?.length ?? 0,
    dates: document.querySelectorAll('input[type=date]').length,
    startDisabled: [...document.querySelectorAll('button')].find(b => b.innerText.includes('开始对比'))?.disabled ?? null,
  }))()`)
  check('路由停在 /dashboard/satellite-compare', initial.url === '/dashboard/satellite-compare', initial.url)
  check('页面标题渲染', initial.text.includes('时相对比'))
  check('灾害点下拉已加载真实数据', initial.hazards > 1, `${initial.hazards} 项`)
  check('两期时间窗共 4 个日期控件', initial.dates === 4, `${initial.dates} 个`)
  check('空态提示可见', initial.text.includes('尚未开始对比'))
  check('浏览器时区口径可见', initial.text.includes('浏览器本地时区'))
  check('诚实降级说明可见', initial.text.includes('不生成或展示演示差异图') || initial.text.includes('不展示'))
  await client.screenshot('sentinel-e2e-initial.png')

  // 点击「开始对比」→ 走 nginx / 新网关 / ai-service；无真实影像时应进入降级态
  if (initial.startDisabled === false) {
    await client.eval(`[...document.querySelectorAll('button')].find(b => b.innerText.includes('开始对比')).click()`)
    const started = Date.now()
    let finalText = ''
    let degraded = false
    while (Date.now() - started < 120000) {
      await sleep(1500)
      finalText = await client.eval(`document.body.innerText.slice(0, 6000)`)
      if (finalText.includes('未取得真实卫星影像')) { degraded = true; break }
      if (finalText.includes('时相对比未完成')) break
    }
    check('真实链路跑通并进入诚实降级态', degraded,
      (finalText.match(/未取得真实卫星影像[^\n]*/) || finalText.match(/时相对比未完成[^\n]*/) || [''])[0].slice(0, 140))
    check('降级时给出数据源与配置指引',
      finalText.includes('dataSource') || finalText.includes('数据源') || finalText.includes('CDSE'),
      (finalText.match(/当前没有可用的真实卫星影像[^\n]*/) || [''])[0].slice(0, 140))

    const rendered = await client.eval(`(() => ({
      blobImgs: [...document.querySelectorAll('img')].filter(i => (i.getAttribute('src') || '').startsWith('blob:')).length,
      fakeDiff: document.body.innerText.includes('差异热力图') && document.body.innerText.includes('变化率'),
      leaked: /:28081|:8001|file:\\/\\//.test(document.body.innerText),
    }))()`)
    check('降级态未渲染任何 blob 假图', rendered.blobImgs === 0, `${rendered.blobImgs} 张`)
    check('页面未泄漏上游端口 / file:// 地址', !rendered.leaked)
    await client.screenshot('sentinel-e2e-degraded.png')
  } else {
    check('「开始对比」按钮可点击', false, `disabled=${initial.startDisabled}`)
  }

  // 浏览器侧用带 JWT 的 fetch 取图片 blob（验证 CSP img-src blob: 与同源代理）
  const blob = await client.eval(`(async () => {
    const t = localStorage.getItem('disasterguard_token');
    const r = await fetch(${JSON.stringify(imageUrl)}, { headers: { Authorization: 'Bearer ' + t } });
    const b = await r.arrayBuffer();
    const u = new Uint8Array(b.slice(0, 8));
    return { status: r.status, bytes: b.byteLength, png: u.join(',') === '137,80,78,71,13,10,26,10' };
  })()`, 60000)
  check('浏览器经网关取图成功（带 JWT / PNG）',
    blob?.status === 200 && blob.bytes > 1000 && blob.png,
    `HTTP ${blob?.status}, ${blob?.bytes} B`)

  check('无未捕获页面异常', client.exceptions.length === 0, client.exceptions.slice(0, 2).join(' | '))
  const consoleErrors = client.consoleErrors.filter((e) => !/favicon|404 \(Not Found\)/i.test(e))
  check('控制台无业务异常', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '))

  await fetch(`${CDP}/json/close/${targetId}`, { method: 'GET' }).catch(() => {})
  const failed = checks.filter((c) => !c.ok)
  console.log(`=== ${failed.length ? 'FAIL' : 'PASS'}：${checks.length - failed.length}/${checks.length} 项通过，截图在 ${OUT} ===`)
  if (failed.length) process.exit(1)
}

main().catch((e) => {
  console.error(`=== FAIL: ${e.message} ===`)
  process.exit(1)
})
