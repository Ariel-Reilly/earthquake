/**
 * 无头 Chrome 布局校验脚本（一次性验证用）
 *
 * 用途：浏览器 MCP 不可用时，用 CDP 直接驱动无头 Chrome，
 * 在指定视口宽度下测量元素几何与文本溢出情况。
 *
 * 用法：node scripts/check-layout.mjs <url> <width> <height> <scriptFile>
 *   scriptFile 需默认导出 async (page) => {...}，page 提供 evaluate/screenshot
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const [, , url, width = '1440', height = '1000', scriptFile] = process.argv

if (!url) {
  console.error('用法: node scripts/check-layout.mjs <url> [width] [height] [scriptFile]')
  process.exit(1)
}

const userDataDir = mkdtempSync(join(tmpdir(), 'dg-chrome-'))
const port = 9222 + Math.floor(Math.random() * 500)

const chrome = spawn(
  CHROME,
  [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--hide-scrollbars',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${userDataDir}`,
    `--window-size=${width},${height}`,
    'about:blank',
  ],
  { stdio: 'ignore' },
)

const cleanup = () => {
  try {
    chrome.kill()
  } catch {
    /* 进程可能已退出 */
  }
  try {
    rmSync(userDataDir, { recursive: true, force: true })
  } catch {
    /* 临时目录清理失败不影响结论 */
  }
}

async function waitForDevTools() {
  for (let i = 0; i < 100; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/version`)
      if (res.ok) return (await res.json()).webSocketDebuggerUrl
    } catch {
      /* 端口尚未就绪 */
    }
    await new Promise((r) => setTimeout(r, 200))
  }
  throw new Error('Chrome DevTools 未在预期时间内就绪')
}

class Cdp {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result)
      }
    })
  }

  send(method, params = {}, sessionId) {
    const id = ++this.id
    const payload = { id, method, params }
    if (sessionId) payload.sessionId = sessionId
    this.ws.send(JSON.stringify(payload))
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }))
  }
}

const wsUrl = await waitForDevTools()
const ws = new WebSocket(wsUrl)
await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve, { once: true })
  ws.addEventListener('error', reject, { once: true })
})

const cdp = new Cdp(ws)
const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' })
const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true })

await cdp.send('Page.enable', {}, sessionId)
await cdp.send('Runtime.enable', {}, sessionId)
await cdp.send(
  'Emulation.setDeviceMetricsOverride',
  { width: Number(width), height: Number(height), deviceScaleFactor: 1, mobile: false },
  sessionId,
)

const loaded = new Promise((resolve) => {
  const handler = (ev) => {
    const msg = JSON.parse(ev.data)
    if (msg.method === 'Page.loadEventFired' && msg.sessionId === sessionId) resolve()
  }
  ws.addEventListener('message', handler)
})

async function navigate(target) {
  const waitLoad = new Promise((resolve) => {
    const handler = (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.method === 'Page.loadEventFired' && msg.sessionId === sessionId) resolve()
    }
    ws.addEventListener('message', handler)
  })
  await cdp.send('Page.navigate', { url: target }, sessionId)
  await waitLoad
  await new Promise((r) => setTimeout(r, 1200))
}

await navigate(url)

const page = {
  async evaluate(expression) {
    const res = await cdp.send(
      'Runtime.evaluate',
      { expression, returnByValue: true, awaitPromise: true },
      sessionId,
    )
    return res.result?.value
  },
  navigate,
  async setViewport(w, h = 1000) {
    await cdp.send(
      'Emulation.setDeviceMetricsOverride',
      { width: Number(w), height: Number(h), deviceScaleFactor: 1, mobile: false },
      sessionId,
    )
    await new Promise((r) => setTimeout(r, 400))
  },
  async screenshot(path) {
    const res = await cdp.send('Page.captureScreenshot', { format: 'png' }, sessionId)
    writeFileSync(path, Buffer.from(res.data, 'base64'))
    return path
  },
}

try {
  if (scriptFile) {
    const mod = await import(new URL(scriptFile, `file://${process.cwd().replace(/\\/g, '/')}/`).href)
    const out = await mod.default(page, { width: Number(width), height: Number(height) })
    console.log(typeof out === 'string' ? out : JSON.stringify(out, null, 2))
  } else {
    console.log(JSON.stringify(await page.evaluate('JSON.stringify({title: document.title, w: innerWidth})')))
  }
} catch (err) {
  console.error('检查失败:', err.message)
  process.exitCode = 1
} finally {
  cleanup()
  ws.close()
  setTimeout(() => process.exit(process.exitCode ?? 0), 200)
}
