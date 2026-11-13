/**
 * A1-2 WS 断线重连计时（真实浏览器 CDP，无 Playwright/Puppeteer）。
 *
 * 做法：用 Page.addScriptToEvaluateOnNewDocument 包装全局 WebSocket 构造函数，
 * 把实例挂到 window.__wsAll；随后在阻断窗口内周期性 close() 掉所有 socket，
 * 模拟「WS 断开但 HTTP 仍可用」的故障，观察：
 *   · 轮询兜底是否持续（/api 请求不断）；
 *   · 结束阻断后 realtimeService 的 5s 定时重连是否握手成功；
 *   · 重连耗时、页面是否白屏、是否有未捕获异常。
 *
 * 前置：nginx(dist) 25173 → 新网关后端 → ai-service；CDP 9222 已启动。
 * 用法：node scripts/delivery/verify-realtime-reconnect.mjs
 *
 * 说明：全量 offline 模拟（Network.emulateNetworkConditions）不会关闭已建立的 WS，
 * 且会一并阻断 HTTP 轮询，无法测「只断 WS」的场景，故改用本方式。
 */
import fs from 'node:fs'
import path from 'node:path'

const APP = (process.env.E2E_APP_BASE || 'http://127.0.0.1:25173').replace(/\/$/, '')
const CDP = process.env.CDP_BASE || 'http://127.0.0.1:9222'
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const BLOCK_MS = Number(process.env.BLOCK_MS || 32000)
const RECONNECT_TIMEOUT_MS = Number(process.env.RECONNECT_TIMEOUT_MS || 20000)
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
      } else if (m.method) this.events.push({ method: m.method, params: m.params, at: Date.now() })
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
  send(method, params = {}, timeoutMs = 30000) {
    return new Promise((resolve, reject) => {
      const id = this.nextId++; this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (this.pending.has(id)) { this.pending.delete(id); reject(new Error(`CDP 超时: ${method}`)) }
      }, timeoutMs)
    })
  }
  async eval(expression, timeoutMs = 30000) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }, timeoutMs)
    if (r.exceptionDetails) throw new Error(`页面脚本异常：${r.exceptionDetails.text}`)
    return r.result?.value
  }
  count(method, since = 0) {
    return this.events.filter((e) => e.method === method && e.at >= since).length
  }
  apiResponses(since = 0) {
    return this.events.filter((e) => e.method === 'Network.responseReceived'
      && e.at >= since && /\/api\//.test(e.params?.response?.url || '')).length
  }
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  console.log(`=== A1-2 WS 断线重连计时（阻断 WS ${BLOCK_MS / 1000}s）===`)

  const login = await fetch(`${APP}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: PASSWORD }),
  }).then((r) => r.json())
  const token = login?.data?.token
  if (!token) throw new Error('登录失败')

  const { client, targetId } = await Cdp.create()
  await client.send('Page.enable'); await client.send('Runtime.enable'); await client.send('Network.enable')
  await client.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => {
      const Native = window.WebSocket;
      window.__wsAll = [];
      function Wrapped(...args) {
        const s = new Native(...args);
        window.__wsAll.push(s);
        return s;
      }
      Wrapped.prototype = Native.prototype;
      Wrapped.CONNECTING = Native.CONNECTING; Wrapped.OPEN = Native.OPEN;
      Wrapped.CLOSING = Native.CLOSING; Wrapped.CLOSED = Native.CLOSED;
      window.WebSocket = Wrapped;
    })();`,
  })

  await client.send('Page.navigate', { url: APP + '/dashboard' })
  await sleep(2600)
  const baseline = await client.eval(`({ ws: (window.__wsAll || []).length, text: document.body.innerText.length })`)
  console.log(`  基线：捕获 WS 实例 ${baseline.ws} 个，页面文本 ${baseline.text} 字符`)
  if (!baseline.ws) throw new Error('未捕获到 WebSocket 实例，无法模拟断线')

  // 阻断窗口：反复 close 掉新建/存活的 socket，但保持 HTTP 可用
  const blockStart = Date.now()
  let closeRounds = 0
  while (Date.now() - blockStart < BLOCK_MS) {
    closeRounds++
    await client.eval(`(() => {
      let n = 0;
      for (const s of (window.__wsAll || [])) {
        if (s.readyState === 0 || s.readyState === 1) { try { s.close(); n++; } catch (_e) {} }
      }
      return n;
    })()`)
    await sleep(1500)
  }
  const during = client.apiResponses(blockStart)
  const handshakesDuring = client.count('Network.webSocketHandshakeResponseReceived', blockStart)
  const pageOk = await client.eval(`document.body.innerText.length`)
  console.log(`  阻断 ${BLOCK_MS / 1000}s：强制关闭 ${closeRounds} 轮，期间 /api 响应 ${during} 个，WS 握手 ${handshakesDuring} 次`)

  // 结束阻断，等待 5s 定时重连
  const releaseAt = Date.now()
  let reconnectMs = RECONNECT_TIMEOUT_MS
  while (Date.now() - releaseAt < RECONNECT_TIMEOUT_MS) {
    await sleep(400)
    if (client.count('Network.webSocketHandshakeResponseReceived', releaseAt) > 0) {
      reconnectMs = Date.now() - releaseAt
      break
    }
  }
  const afterPoll = client.apiResponses(releaseAt)
  const exceptions = client.events.filter((e) => e.method === 'Runtime.exceptionThrown')
    .map((e) => e.params.exceptionDetails?.exception?.description || e.params.exceptionDetails?.text || 'unknown')

  const checks = []
  const check = (name, ok, detail = '') => {
    checks.push({ name, ok, detail }); console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
  }
  check('阻断期间页面未白屏', pageOk > 100, `${pageOk} 字符`)
  check('阻断期间 HTTP 轮询兜底持续', during > 0, `${during} 个 /api 响应`)
  check('阻断期间应用持续尝试建连', handshakesDuring > 0, `${handshakesDuring} 次握手`)
  check('解除阻断后 WS 重连成功（5s 定时窗口）', reconnectMs < RECONNECT_TIMEOUT_MS, `${reconnectMs} ms`)
  check('重连后数据请求继续', afterPoll > 0, `${afterPoll} 个 /api 响应`)
  check('无未捕获页面异常', exceptions.length === 0, exceptions.slice(0, 1).join('').slice(0, 160))

  const result = {
    blockMs: BLOCK_MS, closeRounds, pollingDuringBlock: during,
    handshakesDuringBlock: handshakesDuring, reconnectMs, pollingAfterRelease: afterPoll,
    checks, checkedAt: new Date().toISOString(),
  }
  fs.writeFileSync(path.join(OUT, 'realtime-reconnect.json'), JSON.stringify(result, null, 2))
  await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})

  const failed = checks.filter((c) => !c.ok)
  console.log(`=== ${failed.length ? 'FAIL' : 'PASS'}：${checks.length - failed.length}/${checks.length} 项通过；结果 output/delivery-verify/realtime-reconnect.json ===`)
  if (failed.length) process.exit(1)
}

main().catch((e) => { console.error(`=== FAIL: ${e.message} ===`); process.exit(1) })
