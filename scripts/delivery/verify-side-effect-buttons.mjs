/**
 * A1-4 有副作用按钮 / 交互在隔离库实跑（2026-11-13）。
 *
 * 上一轮 verify-button-sweep.mjs 只点「刷新/查询」等无副作用按钮，
 * 本脚本补齐 ONSITE_ACCEPTANCE_CHECKLIST.md 第 5 节里可自动化的部分：
 *   5.1 关闭预警（原因必填校验 + 真实落库 + 审计）
 *   5.2 通知送达详情口径
 *   5.3 AI 发布向导可达性与演示数据标识
 *   5.4 ≥2 人会签发起 / 确认（含 admin 代签，B2-2）
 *   5.5 导出文件真实生成
 * 全部写操作落在隔离库（compose.delivery-verify.yml），演练结束 down -v 即清空。
 *
 * 前置：nginx(dist) 25173 → 隔离后端 28083；CDP 9222；隔离库容器可 docker exec。
 * 用法：EXPECT_AI_DISABLED=1 node scripts/delivery/verify-side-effect-buttons.mjs
 */
import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

const APP = (process.env.E2E_APP_BASE || 'http://127.0.0.1:25173').replace(/\/$/, '')
const CDP = process.env.CDP_BASE || 'http://127.0.0.1:9222'
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const EXPECT_AI_DISABLED = process.env.EXPECT_AI_DISABLED === '1'
const OUT = path.resolve('output/delivery-verify')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function sql(query) {
  return execFileSync('docker', ['exec', 'dg-delivery-verify-postgres', 'psql',
    '-U', 'disasterguard', '-d', 'disasterguard', '-t', '-A', '-c', query],
    { encoding: 'utf8' }).trim()
}

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
    const all5xx = slice.filter((e) => e.method === 'Network.responseReceived'
      && (e.params?.response?.status ?? 0) >= 500)
    const aiDegraded = EXPECT_AI_DISABLED
      ? all5xx.filter((e) => e.params.response.status === 503 && /\/api\/ai\//.test(e.params.response.url))
      : []
    return {
      serverErrors: all5xx.filter((e) => !aiDegraded.includes(e)).map((e) => `${e.params.response.status} ${e.params.response.url}`),
      aiDegradedRequests: aiDegraded.map((e) => `${e.params.response.status} ${e.params.response.url}`),
      exceptions: slice.filter((e) => e.method === 'Runtime.exceptionThrown')
        .map((e) => e.params.exceptionDetails?.exception?.description || e.params.exceptionDetails?.text || 'unknown'),
      consoleErrors: slice.filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
        .map((e) => e.params.args?.map((a) => a.value ?? a.description).join(' ') || '')
        .filter((s) => !/favicon|404 \(Not Found\)|net::ERR_/i.test(s)),
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

const REASON = 'A1-4 预检：现场处置完成，险情解除'

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  console.log(`=== A1-4 有副作用按钮实跑（隔离库）(${APP}) ===`)
  const auth = await fetch(`${APP}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: PASSWORD }),
  }).then((r) => r.json())
  const token = auth?.data?.token
  if (!token) throw new Error('登录失败')
  const H = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
  const api = async (url, options = {}) => {
    const r = await fetch(APP + url, { ...options, headers: { ...H, ...(options.headers || {}) } })
    return { status: r.status, body: await r.json().catch(() => null) }
  }

  // ── 创建一条专用于关闭流程的预警 ──
  const created = await api('/api/warnings', {
    method: 'POST',
    body: JSON.stringify({
      title: 'A1-4 关闭原因预检', level: 'blue', hazardCode: 'HZ-SBOX-04', hazardName: '副作用预检点',
      hazardType: 'waterlogging', region: '渝中区', lon: 106.53875, lat: 29.553875,
      bufferKm: 1.0, channels: ['APP'], content: '有副作用按钮预检', operator: 'admin',
    }),
  })
  const warningId = created.body?.data?.id
  if (!warningId) throw new Error(`创建预警失败 HTTP ${created.status}`)
  console.log(`  测试预警 id=${warningId}`)

  // ── 浏览器：5.1 关闭预警原因必填校验 + 真实提交 ──
  const { client, targetId } = await Cdp.create()
  await client.send('Page.enable'); await client.send('Runtime.enable'); await client.send('Network.enable')
  await client.send('Page.navigate', { url: APP + '/' })
  await waitReady(client)
  await client.eval(`(() => {
    localStorage.setItem('disasterguard_token', ${JSON.stringify(token)});
    localStorage.setItem('disasterguard_user', ${JSON.stringify(JSON.stringify(auth.data.user))});
    return true;
  })()`)
  const from = client.events.length
  await client.send('Page.navigate', { url: `${APP}/dashboard/warnings/${warningId}` })
  await waitReady(client); await sleep(1400)

  const openBtn = await client.eval(`(() => {
    const b = [...document.querySelectorAll('button')].find(x => !x.disabled && x.innerText.trim() === '关闭预警')
    if (!b) return false
    b.click(); return true
  })()`)
  await sleep(500)
  const dialogOpen = await client.eval(`!!document.querySelector('.close-dialog')`)

  // 不填原因直接确认 → 应被校验拦住
  await client.eval(`(() => { const b=[...document.querySelectorAll('.close-dialog .primary-btn')][0]; if(b) b.click(); return true })()`)
  await sleep(400)
  const emptyCheck = await client.eval(`({
    error: document.querySelector('.close-dialog-error')?.innerText || '',
    stillOpen: !!document.querySelector('.close-dialog'),
  })`)

  // 填原因再提交
  await client.eval(`(() => {
    const ta = document.querySelector('.close-dialog textarea')
    if (!ta) return false
    const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set
    setter.call(ta, ${JSON.stringify(REASON)})
    ta.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(250)
  const filled = await client.eval(`document.querySelector('.close-dialog textarea')?.value || ''`)
  await client.eval(`(() => { const b=[...document.querySelectorAll('.close-dialog .primary-btn')][0]; if(b) b.click(); return true })()`)
  await sleep(1800)
  const afterSubmit = await client.eval(`({
    dialogOpen: !!document.querySelector('.close-dialog'),
    showsReason: document.body.innerText.includes(${JSON.stringify(REASON)}),
  })`)
  const closeUiIssues = client.issues(from)

  // DB 核对状态与关闭原因
  const dbClose = sql(`select coalesce(status,'-')||' | '||coalesce(close_reason,'-') from warning_event where id=${warningId};`)

  // ── 5.5 导出下载：xlsx 魔数 PK + 字节数 ──
  const exportResp = await fetch(`${APP}/api/warnings/export`, { method: 'POST', headers: H })
  const exportBuf = Buffer.from(await exportResp.arrayBuffer())
  const exportOk = exportResp.status === 200 && exportBuf.length > 1000
    && exportBuf[0] === 0x50 && exportBuf[1] === 0x4b

  // ── 5.4 ≥2 人会签发起 + 确认（含 admin 代签）──
  const users = sql(`select id::text||':'||username from sys_user where username <> 'admin' order by id limit 2;`)
    .split('\n').filter(Boolean).map((s) => { const [id, name] = s.split(':'); return { id, name } })
  const eventId = sql(`select event_code from unified_event where state in ('DETECTED','CONFIRMING') order by event_code limit 1;`)
  const signResp = await api(`/api/events/${eventId}/sign`, {
    method: 'POST',
    body: JSON.stringify({
      signers: [
        { id: users[0]?.id ?? '2', name: users[0]?.name ?? 'expert1', specialty: '地质' },
        { id: users[1]?.id ?? '3', name: users[1]?.name ?? 'expert2', specialty: '水文' },
      ],
      mode: 'PARALLEL', timeout: 24, timeoutAction: 'ESCALATE', comment: 'A1-4 会签预检',
    }),
  })
  const signId = signResp.body?.data?.signId ?? null
  let confirmResp = null
  if (signId) {
    // admin 持有 sign:proxy，代第一位专家确认
    confirmResp = await api(`/api/events/${eventId}/sign/${signId}/confirm`, {
      method: 'PUT', body: JSON.stringify({ signerId: users[0]?.id ?? '2', opinion: 'A1-4 代签预检通过' }),
    })
  }
  const signList = await api(`/api/events/${eventId}/sign`)
  const signRows = Array.isArray(signList.body?.data) ? signList.body.data : []
  const dbSign = sql(`select count(*)||' 行' from event_sign where event_id='${eventId}';`)
  const signConfirmed = signRows.some((r) => r.signerStatus === 'CONFIRMED' || r.signer_status === 'CONFIRMED')

  // ── 5.2 通知送达详情 / 5.3 AI 发布向导（页面可达与口径文案）──
  await client.send('Page.navigate', { url: `${APP}/dashboard/warnings/${warningId}` })
  await waitReady(client); await sleep(1100)
  const deliveryTab = await client.eval(`(() => {
    const b = [...document.querySelectorAll('[role=tab],button')].find(x => x.innerText.includes('通知与送达'))
    if (!b) return { found: false }
    b.click(); return { found: true }
  })()`)
  await sleep(900)
  const deliveryText = await client.eval(`document.body.innerText.slice(0, 4000)`)
  // 5.3 发布预警向导：从「预警与处置」列表页的按钮打开 4 步弹窗
  await client.send('Page.navigate', { url: `${APP}/dashboard/warnings` })
  await waitReady(client); await sleep(1200)
  const wizardOpen = await client.eval(`(() => {
    const b = [...document.querySelectorAll('button')].find(x => !x.disabled && x.innerText.trim() === '发布预警')
    if (b) b.click()
    return { buttonFound: !!b }
  })()`)
  await sleep(700)
  const wizard = await client.eval(`(() => {
    const modal = document.querySelector('.warning-create-modal-overlay')
    const steps = [...document.querySelectorAll('.step-indicator span')].map(s => s.innerText.trim())
    return {
      modalOpen: !!modal, steps, stepCount: steps.length,
      hint: modal?.querySelector('.modal-header p')?.innerText || '',
      title: modal?.querySelector('h2')?.innerText || '',
    }
  })()`)
  // AI 洞察页：AI 服务关闭时应显示诚实降级说明，而不是伪造评分
  await client.send('Page.navigate', { url: `${APP}/dashboard/ai-insight` })
  await waitReady(client); await sleep(1100)
  const insight = await client.eval(`(() => {
    const t = document.body.innerText
    return {
      textLen: t.length,
      hasDegradeNotice: /演示模式|模拟评分|不展示|加载失败|未接入|不可用/.test(t),
      isLoadingState: /正在分析|加载中|后端连接中/.test(t),
      hasFakeScore: /87\.5|模拟评分已生成|假数据/.test(t),
      sample: t.slice(0, 220),
    }
  })()`)
  const allUiIssues = client.issues(from)

  const summary = {
    checkedAt: new Date().toISOString(), app: APP, expectAiDisabled: EXPECT_AI_DISABLED,
    warningId, reason: REASON,
    closeFlow: { openBtn, dialogOpen, emptyCheck, filled, afterSubmit, dbClose },
    exportFlow: { status: exportResp.status, bytes: exportBuf.length, zipMagic: exportBuf.slice(0, 2).toString('hex'), ok: exportOk },
    signFlow: { eventId, users, signStatus: signResp.status, signId, confirmStatus: confirmResp?.status ?? null,
      confirmCode: confirmResp?.body?.code ?? null, rows: signRows.length, signConfirmed, dbSign },
    deliveryFlow: { tabFound: deliveryTab.found, textSample: deliveryText.slice(0, 300) },
    wizardFlow: { ...wizardOpen, ...wizard },
    insightFlow: insight,
    ...allUiIssues,
    closeUiIssues,
  }
  fs.writeFileSync(path.join(OUT, 'side-effect-buttons.json'), JSON.stringify(summary, null, 2))
  await fetch(`${CDP}/json/close/${targetId}`).catch(() => {})

  console.log(`  关闭流程：按钮=${openBtn} 弹窗=${dialogOpen} 空值校验「${emptyCheck.error}」 弹窗仍开=${emptyCheck.stillOpen}`)
  console.log(`            提交后弹窗关闭=${!afterSubmit.dialogOpen} 页面回显原因=${afterSubmit.showsReason} DB=${dbClose}`)
  console.log(`  导出：HTTP ${exportResp.status}，${exportBuf.length} B，魔数 ${exportBuf.slice(0, 2).toString('hex')}`)
  console.log(`  会签：event=${eventId} 2 人，发起 HTTP ${signResp.status} signId=${signId}，admin 代签 HTTP ${confirmResp?.status ?? '-'}/${confirmResp?.body?.code ?? '-'}，DB ${dbSign}`)
  console.log(`  送达 Tab=${deliveryTab.found}；发布向导 按钮=${wizardOpen.buttonFound} 弹窗=${wizard.modalOpen} 步骤=${wizard.stepCount}「${wizard.hint}」`)
  console.log(`  AI 洞察：文案=${insight.textLen} 加载态=${insight.isLoadingState} 伪造评分=${insight.hasFakeScore}`)
  if (insight.isLoadingState) {
    console.log('  NOTE  演练后端 AI_ENABLED=false，AI 洞察页停在「正在分析 / 后端连接中」加载态；现场启动 AI 服务后须确认该页完整渲染')
  }
  console.log(`  控制台错误=${allUiIssues.consoleErrors.length} 异常=${allUiIssues.exceptions.length} 5xx=${allUiIssues.serverErrors.length}`)

  const pass = [
    ['关闭预警按钮与原因弹窗可用', openBtn === true && dialogOpen === true],
    ['原因必填校验生效（不填不可提交）', emptyCheck.error.includes('请输入关闭原因') && emptyCheck.stillOpen === true],
    ['填原因后提交成功并落库', afterSubmit.dialogOpen === false && dbClose.includes(REASON)],
    ['导出文件真实生成（xlsx 魔数 + 字节数）', exportOk === true],
    ['≥2 人会签发起成功', signResp.status === 200 && signResp.body?.code === 0 && !!signId],
    ['admin 代签确认成功', confirmResp?.status === 200 && confirmResp?.body?.code === 0],
    ['会签状态落库（≥1 人 CONFIRMED）', signConfirmed === true],
    ['通知与送达 Tab 可打开', deliveryTab.found === true],
    ['发布预警 4 步向导可打开', wizardOpen.buttonFound === true && wizard.modalOpen === true && wizard.stepCount === 4],
    ['AI 洞察页可达且未展示伪造评分', insight.textLen > 100 && insight.hasFakeScore === false],
    ['无非预期 5xx / 异常 / 控制台错误', allUiIssues.serverErrors.length === 0 && allUiIssues.exceptions.length === 0 && allUiIssues.consoleErrors.length === 0],
  ]
  console.log('\n=== 判定 ===')
  pass.forEach(([k, ok]) => console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${k}`))
  console.log(`=== ${pass.filter(([, ok]) => ok).length}/${pass.length} PASS；明细 output/delivery-verify/side-effect-buttons.json ===`)
  if (pass.some(([, ok]) => !ok)) process.exit(1)
}

main().catch((e) => { console.error(`=== FAIL: ${e.message} ===`); process.exit(1) })
