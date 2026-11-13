/**
 * A1-1 红色警报音频链路浏览器预检（2026-11-13）。
 *
 * headless 无法「听到」声音，但可以在真实浏览器里验证声音链路的调用事实：
 *   触发红色预警 → AudioContext 创建振荡器并 start → 点「确认收到」→ stop + close。
 * 这覆盖现场清单 2.2（彻底静音）与 2.4（控制台无异常）的可自动化部分；
 * 2.1 真实外放、2.3 自动播放被拦截后的开启按钮仍须现场人耳确认
 * （逻辑路径已由 tests/useAlarmSound.spec.js 6 例覆盖）。
 *
 * 前置：nginx(dist) 25173 → 隔离后端 28083；CDP 9222 已启动。
 * 用法：node scripts/delivery/verify-alarm-audio.mjs
 */
import fs from 'node:fs'
import path from 'node:path'

const APP = (process.env.E2E_APP_BASE || 'http://127.0.0.1:25173').replace(/\/$/, '')
const CDP = process.env.CDP_BASE || 'http://127.0.0.1:9222'
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const OUT = path.resolve('output/delivery-verify')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// 在页面脚本执行前包裹原生 AudioContext，记录振荡器生命周期
const AUDIO_PROBE = `(() => {
  const p = { created: 0, started: 0, stopped: 0, closed: 0, unsupported: false, resumeRejected: false }
  window.__audioProbe = p
  const Native = window.AudioContext || window.webkitAudioContext
  if (!Native) { p.unsupported = true; return }
  class ProbeAC extends Native {
    constructor(...args) {
      super(...args)
      const createOrig = this.createOscillator.bind(this)
      this.createOscillator = (...a) => {
        const osc = createOrig(...a)
        p.created++
        const startOrig = osc.start.bind(osc)
        const stopOrig = osc.stop.bind(osc)
        osc.start = (...b) => { p.started++; return startOrig(...b) }
        osc.stop = (...b) => { p.stopped++; return stopOrig(...b) }
        return osc
      }
      const closeOrig = this.close.bind(this)
      this.close = (...b) => { p.closed++; return closeOrig(...b) }
    }
  }
  window.AudioContext = ProbeAC
  if (window.webkitAudioContext) window.webkitAudioContext = ProbeAC
})()`

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

async function waitReady(client, tries = 12) {
  for (let i = 0; i < tries; i++) {
    try { if (await client.eval(`document.readyState === 'complete' && !!document.body`)) return }
    catch (_) { /* 导航中 */ }
    await sleep(400)
  }
  throw new Error('页面就绪超时')
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  console.log(`=== A1-1 警报音频链路预检 (${APP}) ===`)
  const auth = await fetch(`${APP}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: PASSWORD }),
  }).then((r) => r.json())
  const token = auth?.data?.token
  if (!token) throw new Error('登录失败')

  const { client, targetId } = await Cdp.create()
  await client.send('Page.enable'); await client.send('Runtime.enable'); await client.send('Network.enable')
  await client.send('Page.addScriptToEvaluateOnNewDocument', { source: AUDIO_PROBE })
  await client.send('Page.navigate', { url: APP + '/' })
  await waitReady(client)
  await client.eval(`(() => {
    localStorage.setItem('disasterguard_token', ${JSON.stringify(token)});
    localStorage.setItem('disasterguard_user', ${JSON.stringify(JSON.stringify(auth.data.user))});
    return true;
  })()`)
  await client.send('Page.navigate', { url: APP + '/dashboard' })
  await waitReady(client)

  // 等首个预警列表快照完成基线登记，避免新预警被吞
  const baselineFrom = client.events.length
  for (let i = 0; i < 24; i++) {
    await sleep(500)
    const seen = client.events.slice(baselineFrom).some((e) =>
      e.method === 'Network.responseReceived' && /\/api\/warnings/.test(e.params?.response?.url || ''))
    if (seen) break
  }
  await sleep(1200)

  const from = client.events.length
  const created = await fetch(`${APP}/api/warnings`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      title: 'A1-1 音频链路预检', level: 'red', hazardCode: 'HZ-SBOX-04', hazardName: '音频链路预检点',
      hazardType: 'waterlogging', region: '渝中区', lon: 106.53875, lat: 29.553875,
      bufferKm: 1.0, channels: ['APP'], content: '音频链路预检', operator: 'admin',
    }),
  }).then((r) => r.json())
  const warningId = created?.data?.id ?? null

  // 等红色弹窗出现
  let present = false, waitMs = 0
  for (let i = 0; i < 30; i++) {
    await sleep(500); waitMs += 500
    try {
      present = await client.eval(`!!document.querySelector('.alarm-overlay')`)
      if (present) break
    } catch (_) { /* 上下文切换 */ }
  }
  const onAlarm = await client.eval(`({ ...window.__audioProbe, level: document.querySelector('.alarm-level')?.innerText || '', hasEnableBtn: !!document.querySelector('.alarm-sound-enable') })`)

  // 2.3：被自动播放策略拦截时，「开启警报声」按钮应出现；用 CDP 真实输入事件
  // （isTrusted=true，区别于 JS .click()）模拟用户手势，观察声音链路能否恢复。
  const enable = { attempted: false, clicked: false, createdDelta: 0, startedDelta: 0, stillBlocked: null }
  if (onAlarm.hasEnableBtn) {
    enable.attempted = true
    const rect = await client.eval(`(() => {
      const b = document.querySelector('.alarm-sound-enable')
      if (!b) return null
      const r = b.getBoundingClientRect()
      return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
    })()`)
    if (rect) {
      const common = { x: rect.x, y: rect.y, button: 'left', clickCount: 1 }
      await client.send('Input.dispatchMouseEvent', { type: 'mousePressed', ...common })
      await client.send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...common })
      enable.clicked = true
      await sleep(1500)
      const after = await client.eval(`({ ...window.__audioProbe, hasEnableBtn: !!document.querySelector('.alarm-sound-enable') })`)
      enable.createdDelta = after.created - onAlarm.created
      enable.startedDelta = after.started - onAlarm.started
      enable.stillBlocked = after.hasEnableBtn
      enable.after = after
    }
  }

  // 点「确认收到」→ 应彻底静音
  const clicked = await client.eval(`(() => {
    const b = [...document.querySelectorAll('.alarm-btn')].find(x => x.innerText.includes('确认收到'))
    if (!b) return false
    b.click(); return true
  })()`)
  await sleep(900)
  const afterAck = await client.eval(`({ ...window.__audioProbe, overlayStillVisible: !!document.querySelector('.alarm-overlay') })`)
  const issues = client.issues(from)

  const summary = {
    checkedAt: new Date().toISOString(), app: APP, warningId,
    alarmAppeared: present, alarmWaitMs: waitMs, levelBadge: onAlarm.level,
    audioBackendUnsupported: onAlarm.unsupported === true,
    autoplayBlockedInHeadless: onAlarm.hasEnableBtn === true,
    onAlarm, enable, afterAck, clicked,
    stoppedAfterAck: (afterAck.stopped - onAlarm.stopped) > 0,
    closedAfterAck: (afterAck.closed - onAlarm.closed) > 0,
    ...issues,
  }
  fs.writeFileSync(path.join(OUT, 'alarm-audio.json'), JSON.stringify(summary, null, 2))
  await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})

  console.log(`  预警 id=${warningId}，弹窗 ${present ? `出现（${waitMs} ms）` : '未出现'}，徽标「${onAlarm.level}」`)
  console.log(`  振荡器：创建 ${onAlarm.created} / start ${onAlarm.started} / stop ${onAlarm.stopped} / close ${onAlarm.closed}`)
  console.log(`  点确认后：stop +${afterAck.stopped - onAlarm.stopped}，close +${afterAck.closed - onAlarm.closed}，弹窗残留=${afterAck.overlayStillVisible}`)
  console.log(`  拦截路径：开启按钮=${onAlarm.hasEnableBtn}，真实手势点击=${enable.clicked}，点击后振荡器 +${enable.createdDelta}/start +${enable.startedDelta}`)
  console.log(`  控制台错误=${issues.consoleErrors.length} 未捕获异常=${issues.exceptions.length} 5xx=${issues.serverErrors.length}`)

  const pass = [
    ['红色弹窗出现', present],
    ['弹窗徽标为红色警报', onAlarm.level === '红色警报'],
    ['自动播放拦截时显示开启按钮（或直接出声）', onAlarm.hasEnableBtn === true || onAlarm.started > 0],
    ['开启按钮被真实输入事件点击', onAlarm.hasEnableBtn ? enable.clicked === true : true],
    ['确认收到按钮可点', clicked === true],
    ['确认后弹窗关闭', afterAck.overlayStillVisible === false],
    ['确认后彻底静音（stop / close）', summary.stoppedAfterAck || summary.closedAfterAck],
    ['无未捕获异常', issues.exceptions.length === 0],
    ['无 5xx', issues.serverErrors.length === 0],
    ['无控制台错误', issues.consoleErrors.length === 0],
  ]
  console.log('\n=== 判定 ===')
  pass.forEach(([k, ok]) => console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${k}`))
  if (summary.audioBackendUnsupported) console.log('  NOTE  headless 无 AudioContext，音频链路未验证，现场必须人工听声')
  if (summary.autoplayBlockedInHeadless) {
    console.log('  NOTE  headless 自动播放被拦截：已验证拦截提示与开启按钮；真实出声（2.1）与真人手势恢复（2.3）仍需现场人耳确认')
  }
  if (enable.startedDelta > 0) console.log(`  NOTE  真实手势点击后已启动 ${enable.startedDelta} 个振荡器，恢复路径可用`)
  console.log(`=== ${pass.filter(([, ok]) => ok).length}/${pass.length} PASS；明细 output/delivery-verify/alarm-audio.json ===`)
  if (pass.some(([, ok]) => !ok)) process.exit(1)
}

main().catch((e) => { console.error(`=== FAIL: ${e.message} ===`); process.exit(1) })
