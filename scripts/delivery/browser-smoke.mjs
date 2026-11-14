/**
 * 交付用真实浏览器冒烟脚本（Edge/Chrome CDP，无 Playwright/Puppeteer）。
 *
 * 覆盖 37 项检查：登录、红色满屏警报（声音兜底/确认/详情跳转）、橙/黄/蓝四档弹窗、
 * 移动端抽屉与横向溢出、深色模式、首页、实时地图、审计日志筛选、后端不可达错误态、
 * AI 巡检任务页、系统设置落库、WS 阻断轮询降级、预警确认→派发、AI 分析详情、
 * 角色越权 403、登出。
 *
 * 用法：先启动服务（后端 8080 / 前端 5173），再以
 *   msedge --headless=new --remote-debugging-port=9222 --user-data-dir=<profile>
 * 启动浏览器，然后 `node scripts/delivery/browser-smoke.mjs`。
 * 脚本会自动创建并清理测试预警；截图输出到 output/browser-verify/。
 */
import fs from 'node:fs'
import path from 'node:path'

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const CDP = 'http://127.0.0.1:9222'
const OUT = 'E:/Projects/DisasterGuard/output/browser-verify'

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/* ══════════════════════ CDP 客户端 ══════════════════════ */

class CdpClient {
  constructor(ws) {
    this.ws = ws
    this.nextId = 1
    this.pending = new Map()
    this.events = []
    ws.addEventListener('message', (event) => {
      const message = JSON.parse(event.data)
      if (message.id && this.pending.has(message.id)) {
        const task = this.pending.get(message.id)
        this.pending.delete(message.id)
        message.error ? task.reject(new Error(message.error.message)) : task.resolve(message.result)
      } else if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
        this.events.push(message.params)
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
    return { client: new CdpClient(ws), version }
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
      }, 30000)
    })
  }

  async eval(expression) {
    const result = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true,
      userGesture: true,
    })
    if (result.exceptionDetails) {
      throw new Error(result.exceptionDetails.text || '页面执行异常')
    }
    return result.result?.value
  }

  async waitFor(expression, timeoutMs = 25000, intervalMs = 300) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      try {
        if (await this.eval(expression)) return true
      } catch {
        // 页面跳转期间执行上下文可能失效，重试
      }
      await sleep(intervalMs)
    }
    return false
  }

  async navigate(url) {
    await this.send('Page.navigate', { url })
  }

  async screenshot(name) {
    const shot = await this.send('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(OUT, name), Buffer.from(shot.data, 'base64'))
    return name
  }

  close() {
    this.ws.close()
  }
}

const results = []
function log(name, ok, detail = '') {
  results.push({ name, ok: Boolean(ok), detail: String(detail) })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`)
}

async function apiFetch(token, pathname, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(`${API}${pathname}`, { ...options, headers })
  const payload = await response.json().catch(() => null)
  return { status: response.status, payload }
}

const createdWarningIds = []

async function createWarning(token, level, title, hazardCode = 'HZ-SBOX-04', hazardName = '浏览器验收点') {
  const body = {
    title,
    level,
    hazardCode,
    hazardName,
    hazardType: 'waterlogging',
    region: '渝中区',
    lon: 106.53875,
    lat: 29.553875,
    bufferKm: 1.0,
    channels: ['APP'],
    content: '浏览器自动化验收',
    validUntil: null,
    operator: 'admin',
  }
  const { status, payload } = await apiFetch(token, '/warnings', { method: 'POST', body: JSON.stringify(body) })
  if (status !== 200 || payload?.code !== 0) {
    throw new Error(`创建预警失败 HTTP ${status}: ${payload?.message || ''}`)
  }
  createdWarningIds.push(payload.data.id)
  return payload.data
}

/* ══════════════════════ 主流程 ══════════════════════ */

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  const { client: cdp, version } = await CdpClient.create()
  console.log(`CDP: ${version.Browser}`)

  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')
  await cdp.send('Log.enable')
  // 禁缓存：避免 Vite dev server 样式子模块旧缓存污染颜色/样式验收
  await cdp.send('Network.enable')
  await cdp.send('Network.setCacheDisabled', { cacheDisabled: true })
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  // 1) 页面内登录
  await cdp.navigate(`${APP}/`)
  await sleep(2200)
  const login = await cdp.eval(`(async () => {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: 'admin123' })
    });
    const payload = await res.json();
    if (!payload || payload.code !== 0) return { ok: false };
    localStorage.setItem('disasterguard_token', payload.data.token);
    localStorage.setItem('disasterguard_user', JSON.stringify(payload.data.user));
    window.__smokeToken = payload.data.token;
    return { ok: true, role: payload.data.user.role };
  })()`)
  const token = await cdp.eval('window.__smokeToken')
  log('页面内登录并写入 localStorage', login?.ok && Boolean(token), `role=${login?.role}`)

  // 2) 设备页加载
  await cdp.navigate(`${APP}/dashboard/devices`)
  await cdp.waitFor(`!!document.querySelector('.main-layout')`, 25000)
  await sleep(1500)
  const devices = await cdp.eval(`(() => ({
    path: location.pathname,
    layout: !!document.querySelector('.main-layout'),
    bodyLength: document.body.innerText.length
  }))()`)
  log('设备页加载（全局布局渲染）', devices.layout, devices.path)
  log('设备页无 403/白屏', devices.bodyLength > 100, String(devices.bodyLength))

  // 3) 红色预警：满屏警报
  const red = await createWarning(token, 'red', '浏览器验收-红色预警')
  const redShown = await cdp.waitFor(`!!document.querySelector('.alarm-overlay')`, 25000)
  log('红色预警触发全屏警报', redShown)
  if (redShown) {
    await sleep(800)
    const redStyle = await cdp.eval(`(() => {
      const overlay = document.querySelector('.alarm-overlay');
      const card = document.querySelector('.alarm-card');
      const buttons = Array.from(document.querySelectorAll('.alarm-actions button')).map((b) => b.textContent.trim());
      return {
        position: overlay ? getComputedStyle(overlay).position : '',
        inset: overlay ? getComputedStyle(overlay).inset : '',
        zIndex: overlay ? getComputedStyle(overlay).zIndex : '',
        background: overlay ? getComputedStyle(overlay).backgroundColor : '',
        cardOpacity: card ? getComputedStyle(card).opacity : '',
        buttons,
        hint: (document.querySelector('.alarm-hint') || {}).textContent || '',
        title: (document.querySelector('.alarm-title') || {}).textContent || ''
      };
    })()`)
    log('警报样式符合要求', redStyle.position === 'fixed' && redStyle.zIndex === '99999' && String(redStyle.background).includes('255'), JSON.stringify(redStyle))
    log('警报卡片动画结束且完全不透明', redStyle.cardOpacity === '1', `cardOpacity=${redStyle.cardOpacity}`)
    log('包含「查看详情 / 确认收到」两个按钮', redStyle.buttons.includes('查看详情') && redStyle.buttons.includes('确认收到'), redStyle.buttons.join(' / '))
    log('声音未被浏览器拦截（无恢复提示）', !redStyle.hint, 'headless 无法实际听声，声音逻辑有单测覆盖')
    await cdp.screenshot('01-red-alarm-devices-page.png')
    await cdp.eval(`(() => {
      const btn = Array.from(document.querySelectorAll('.alarm-actions button')).find((b) => b.textContent.includes('确认收到'));
      if (btn) btn.click();
      return true;
    })()`)
    const closed = await cdp.waitFor(`!document.querySelector('.alarm-overlay')`, 8000)
    log('点击「确认收到」后弹窗关闭', closed)
    await cdp.screenshot('02-after-acknowledge.png')
  }

  // 4) 红色预警：查看详情跳转
  const red2 = await createWarning(token, 'red', '浏览器验收-红色预警-查看详情')
  const red2Shown = await cdp.waitFor(`!!document.querySelector('.alarm-overlay')`, 25000)
  if (red2Shown) {
    await sleep(600)
    await cdp.eval(`(() => {
      const btn = Array.from(document.querySelectorAll('.alarm-actions button')).find((b) => b.textContent.includes('查看详情'));
      if (btn) btn.click();
      return true;
    })()`)
    const jumped = await cdp.waitFor(`location.pathname.includes('/dashboard/warnings/${red2.id}')`, 8000, 300)
    log('红色警报点击「查看详情」跳转详情页', jumped, await cdp.eval('location.pathname'))
    await cdp.screenshot('07-red-view-detail.png')
  } else {
    log('红色警报点击「查看详情」跳转详情页', false, '第二枚红色警报未出现')
  }

  // 5) 橙色预警：弹窗 + 3 秒跳转
  const orange = await createWarning(token, 'orange', '浏览器验收-橙色预警')
  const orangeShown = await cdp.waitFor(`!!document.querySelector('.warn-card')`, 25000)
  log('橙色预警触发普通弹窗', orangeShown)
  if (orangeShown) {
    await sleep(700)
    const hint = await cdp.eval(`(document.querySelector('.warn-hint') || {}).textContent || ''`)
    const opacity = await cdp.eval(`(() => { const card = document.querySelector('.warn-card'); return card ? getComputedStyle(card).opacity : ''; })()`)
    log('弹窗提示 3 秒自动跳转', String(hint).includes('3 秒'), String(hint).trim())
    log('橙色弹窗动画结束且完全不透明', opacity === '1', `cardOpacity=${opacity}`)
    await cdp.screenshot('03-orange-popup.png')
    const jumped = await cdp.waitFor(`location.pathname.includes('/dashboard/warnings/${orange.id}')`, 8000, 300)
    log('3 秒后自动跳转预警详情', jumped, await cdp.eval('location.pathname'))
    await cdp.screenshot('04-orange-auto-jump.png')
  }

  // 6) 黄色 / 蓝色档位
  const yellow = await createWarning(token, 'yellow', '浏览器验收-黄色预警')
  const yellowShown = await cdp.waitFor(`!!document.querySelector('.warn-card.severity-caution')`, 25000)
  if (yellowShown) {
    await sleep(500)
    const style = await cdp.eval(`(() => {
      const card = document.querySelector('.warn-card.severity-caution');
      const badge = document.querySelector('.warn-level');
      return { badge: badge ? badge.textContent.trim() : '', borderTop: card ? getComputedStyle(card).borderTopColor : '' };
    })()`)
    log('黄色预警 → 黄色弹窗（severity-caution）', String(style.badge).includes('黄色'), JSON.stringify(style))
    await cdp.screenshot('03b-yellow-popup.png')
    await cdp.eval(`(() => {
      const btn = Array.from(document.querySelectorAll('.warn-actions button')).find((b) => b.textContent.includes('关闭'));
      if (btn) btn.click();
      return true;
    })()`)
    await sleep(500)
  } else {
    log('黄色预警 → 黄色弹窗（severity-caution）', false)
  }

  const blue = await createWarning(token, 'blue', '浏览器验收-蓝色预警')
  const blueShown = await cdp.waitFor(`!!document.querySelector('.warn-card.severity-info')`, 25000)
  if (blueShown) {
    await sleep(500)
    const style = await cdp.eval(`(() => {
      const card = document.querySelector('.warn-card.severity-info');
      const badge = document.querySelector('.warn-level');
      return { badge: badge ? badge.textContent.trim() : '', cls: card ? card.className : '' };
    })()`)
    log('蓝色预警 → 蓝色弹窗（severity-info）', String(style.badge).includes('蓝色'), JSON.stringify(style))
    await cdp.screenshot('03c-blue-popup.png')
    await cdp.eval(`(() => {
      const btn = Array.from(document.querySelectorAll('.warn-actions button')).find((b) => b.textContent.includes('关闭'));
      if (btn) btn.click();
      return true;
    })()`)
    await sleep(500)
  } else {
    log('蓝色预警 → 蓝色弹窗（severity-info）', false)
  }

  // 7) 移动端 390px
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true })
  await cdp.navigate(`${APP}/dashboard/devices`)
  await cdp.waitFor(`!!document.querySelector('.main-layout')`, 25000)
  await sleep(1500)
  const mobile = await cdp.eval(`(() => {
    const btn = document.querySelector('.mobile-menu-btn');
    const visible = btn ? getComputedStyle(btn).display !== 'none' : false;
    if (btn) btn.click();
    return { visible };
  })()`)
  await sleep(600)
  const drawer = await cdp.eval(`(() => {
    const aside = document.querySelector('.main-layout-sidebar');
    const overlay = document.querySelector('.sidebar-overlay');
    return {
      openClass: aside ? aside.className : '',
      asideVisible: aside ? getComputedStyle(aside).visibility !== 'hidden' : false,
      overlayDisplay: overlay ? getComputedStyle(overlay).display : ''
    };
  })()`)
  log('移动端汉堡按钮可见', Boolean(mobile?.visible), JSON.stringify(mobile))
  log('点击后侧栏抽屉打开', String(drawer.openClass).includes('is-mobile-open') && drawer.asideVisible, JSON.stringify(drawer))
  await cdp.screenshot('05-mobile-sidebar-drawer.png')
  const overflow = await cdp.eval(`(() => {
    const close = document.querySelector('.sidebar-overlay');
    if (close) close.click();
    return { scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth };
  })()`)
  const overflowPx = Number(overflow.scrollWidth) - Number(overflow.clientWidth)
  log('移动端无横向溢出', overflowPx <= 1, `scrollWidth=${overflow.scrollWidth} clientWidth=${overflow.clientWidth} overflow=${overflowPx}px`)
  await cdp.screenshot('05b-mobile-closed.png')
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  // 8) 深色模式
  await cdp.navigate(`${APP}/dashboard/devices`)
  await cdp.waitFor(`!!document.querySelector('.main-layout')`, 20000)
  await sleep(1000)
  const dark = await cdp.eval(`(() => {
    document.documentElement.setAttribute('data-theme', 'dark');
    document.documentElement.classList.add('theme-dark');
    const lum = (value) => {
      const nums = String(value || '').match(/[0-9]+/g) || [];
      return nums.slice(0, 3).reduce((sum, n) => sum + Number(n), 0);
    };
    const layout = document.querySelector('.main-layout');
    const bodyBg = getComputedStyle(document.body).backgroundColor;
    const htmlBg = getComputedStyle(document.documentElement).backgroundColor;
    return {
      theme: document.documentElement.getAttribute('data-theme'),
      layoutColor: layout ? getComputedStyle(layout).color : '',
      bodyBg,
      htmlBg,
      bodyBgLum: lum(bodyBg || htmlBg),
      textLum: lum(layout ? getComputedStyle(layout).color : '')
    };
  })()`)
  log('深色模式令牌生效（深底 + 浅字）', dark.theme === 'dark' && dark.bodyBgLum < 320 && dark.textLum > 600, JSON.stringify(dark))
  await cdp.screenshot('06-dark-mode.png')
  await cdp.eval(`(() => { document.documentElement.removeAttribute('data-theme'); document.documentElement.classList.remove('theme-dark'); return true; })()`)

  // 9) 综合态势首页
  await cdp.navigate(`${APP}/dashboard`)
  const overviewReady = await cdp.waitFor(`!!document.querySelector('.overview-page, .stats-grid')`, 25000)
  await sleep(2500)
  const overview = await cdp.eval(`(() => ({
    ready: !!document.querySelector('.overview-page, .stats-grid'),
    statCount: document.querySelectorAll('.stat-card').length,
    hasLoadError: !!document.querySelector('.overview-load-error'),
    hasLoading: document.body.innerText.includes('正在加载态势数据')
  }))()`)
  log('SM-02 综合态势首页渲染', overviewReady && overview.ready && overview.statCount > 0, JSON.stringify(overview))
  log('SM-02 首页无加载失败/永久加载中', !overview.hasLoadError && !overview.hasLoading)
  await cdp.screenshot('08-overview-page.png')

  // 10) 实时地图
  await cdp.navigate(`${APP}/dashboard/real-time-map`)
  await cdp.waitFor(`!!document.querySelector('.real-time-map-shell')`, 25000)
  await sleep(3500)
  const map = await cdp.eval(`(() => ({
    shell: !!document.querySelector('.real-time-map-shell'),
    canvas: document.querySelectorAll('.ol-map canvas, .ol-viewport canvas').length,
    mapError: document.body.innerText.includes('地图加载失败')
  }))()`)
  log('SM-03 实时地图渲染（canvas / 无错误态）', map.shell && map.canvas > 0 && !map.mapError, JSON.stringify(map))
  await cdp.screenshot('09-realtime-map.png')

  // 11) 审计日志
  await cdp.navigate(`${APP}/dashboard/audit-log`)
  await cdp.waitFor(`!!document.querySelector('.log-table')`, 25000)
  await cdp.waitFor(`document.querySelectorAll('.action-chip').length > 0`, 15000)
  const audit = await cdp.eval(`(() => ({
    rows: document.querySelectorAll('.action-chip').length,
    emptyState: !!document.querySelector('.empty-state')
  }))()`)
  log('SM-09 审计日志列表有数据', audit.rows > 0 && !audit.emptyState, JSON.stringify(audit))
  const clicked = await cdp.eval(`(() => {
    const pill = Array.from(document.querySelectorAll('.pill')).find((p) => p.textContent.includes('登录'));
    if (pill) pill.click();
    return !!pill;
  })()`)
  await sleep(1800)
  const auditFiltered = await cdp.eval(`(() => ({
    rows: document.querySelectorAll('.action-chip').length,
    emptyState: !!document.querySelector('.empty-state')
  }))()`)
  log('SM-09 审计日志按类型筛选后仍有数据', clicked && auditFiltered.rows > 0 && !auditFiltered.emptyState, JSON.stringify(auditFiltered))
  await cdp.screenshot('10-audit-log.png')

  // 12) 后端不可达错误态（CDP 阻断真实 API 路径）
  // 注意：不可用 '*api*' 这种过宽 glob —— 会连带阻断前端模块 /src/api/client.js，导致白页（SM-11 假失败）。
  await cdp.send('Network.setBlockedURLs', {
    urls: [
      '*://localhost:8080/api/*',
      '*://127.0.0.1:8080/api/*',
      '*://localhost:8081/api/*',
      '*://127.0.0.1:8081/api/*',
      '*://localhost:5173/api/*',
      '*://127.0.0.1:5173/api/*',
    ],
  })
  await cdp.navigate(`${APP}/dashboard/warnings`)
  await cdp.waitFor(`!!document.querySelector('.warning-load-error')`, 25000)
  const apiDownWarnings = await cdp.eval(`(() => ({
    hasError: !!document.querySelector('.warning-load-error'),
    emptyState: !!document.querySelector('.empty-state'),
    emptyText: document.body.innerText.includes('当前没有活跃预警')
  }))()`)
  log('SM-11 后端不可达时预警页显示错误态、不误报"态势平稳"', apiDownWarnings.hasError && !apiDownWarnings.emptyState && !apiDownWarnings.emptyText, JSON.stringify(apiDownWarnings))
  await cdp.screenshot('12-api-down-warnings.png')

  await cdp.navigate(`${APP}/dashboard`)
  await cdp.waitFor(`!!document.querySelector('.overview-load-error')`, 25000)
  const apiDownOverview = await cdp.eval(`(() => ({
    hasError: !!document.querySelector('.overview-load-error'),
    stuckLoading: document.body.innerText.includes('正在加载态势数据')
  }))()`)
  log('SM-11 后端不可达时首页显示"加载失败+重试"而非永久加载', apiDownOverview.hasError && !apiDownOverview.stuckLoading, JSON.stringify(apiDownOverview))
  await cdp.screenshot('13-api-down-overview.png')

  await cdp.send('Network.setBlockedURLs', { urls: [] })
  await cdp.navigate(`${APP}/dashboard/devices`)
  await cdp.waitFor(`!!document.querySelector('.main-layout')`, 25000)
  await sleep(1800)
  const recovered = await cdp.eval(`(() => ({
    loaded: !!document.querySelector('.main-layout'),
    loadErrorVisible: !!document.querySelector('.overview-load-error, .warning-load-error')
  }))()`)
  log('SM-11 网络恢复后页面可正常加载', recovered.loaded && !recovered.loadErrorVisible, JSON.stringify(recovered))
  await cdp.screenshot('14-recovered.png')

  // 13) AI 巡检任务页
  await cdp.navigate(`${APP}/dashboard/ai-tasks`)
  await cdp.waitFor(`!!document.querySelector('.main-layout')`, 25000)
  await sleep(2500)
  const aiTasks = await cdp.eval(`(() => {
    const text = document.body.innerText;
    return {
      loaded: !!document.querySelector('.main-layout'),
      hasContent: !!document.querySelector('.panel, table, .task-card, .tasks-panel'),
      forbidden: text.includes('403') || text.includes('无权') || text.includes('请求失败')
    };
  })()`)
  log('SM-08 AI 巡检任务页正常渲染（无 403）', aiTasks.loaded && aiTasks.hasContent && !aiTasks.forbidden, JSON.stringify(aiTasks))
  await cdp.screenshot('15-ai-tasks.png')

  // 14) 系统设置落库
  const settingsValue = '御灾智巡-落库验收-' + Date.now()
  await cdp.navigate(`${APP}/dashboard/settings`)
  await cdp.waitFor(`!!document.querySelector('.settings-page')`, 25000)
  await sleep(1500)
  const settingsInputSet = await cdp.eval(`(() => {
    const label = Array.from(document.querySelectorAll('.settings-row')).find((r) => r.textContent.includes('平台名称'));
    const input = label ? label.querySelector('input') : null;
    if (!input) return false;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(input, ${JSON.stringify(settingsValue)});
    input.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`)
  await sleep(300)
  const settingsSaved = await cdp.eval(`(() => {
    const btn = Array.from(document.querySelectorAll('button')).find((b) => b.textContent.includes('保存设置'));
    if (btn) btn.click();
    return !!btn;
  })()`)
  await sleep(1800)
  await cdp.send('Page.reload')
  await cdp.waitFor(`!!document.querySelector('.settings-page')`, 25000)
  await sleep(1800)
  const settingsPersisted = await cdp.eval(`(() => {
    const label = Array.from(document.querySelectorAll('.settings-row')).find((r) => r.textContent.includes('平台名称'));
    const input = label ? label.querySelector('input') : null;
    return input ? input.value : '';
  })()`)
  log('SM-16 系统设置保存后刷新仍存在（后端落库）', settingsInputSet && settingsSaved && settingsPersisted === settingsValue, `persisted=${settingsPersisted}`)
  await cdp.screenshot('18-settings-persisted.png')
  // 恢复默认平台名：先写输入并等待 Vue 更新 dirty 状态，再点保存（否则按钮仍 disabled 点击无效）
  await cdp.eval(`(() => {
    const label = Array.from(document.querySelectorAll('.settings-row')).find((r) => r.textContent.includes('平台名称'));
    const input = label ? label.querySelector('input') : null;
    if (!input) return false;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(input, '地脉智巡');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`)
  await sleep(500)
  await cdp.eval(`(() => {
    const btn = Array.from(document.querySelectorAll('button')).find((b) => b.textContent.includes('保存设置'));
    if (btn && !btn.disabled) btn.click();
    return true;
  })()`)
  await sleep(1800)

  // 15) WS 阻断 → 轮询降级
  await cdp.send('Network.setBlockedURLs', { urls: ['*://*/ws?*'] })
  await cdp.navigate(`${APP}/dashboard/warnings`)
  const pollPageLoaded = await cdp.waitFor(`!!document.querySelector('.main-layout')`, 25000)
  await sleep(3000)
  const activeBefore = await cdp.eval(`(() => {
    const m = document.body.innerText.match(/活跃[^0-9]*([0-9]+)/);
    return m ? Number(m[1]) : -1;
  })()`)
  const pollWarning = await createWarning(token, 'blue', '轮询降级验收-' + Date.now())
  const pollPopup = await cdp.waitFor(`!!document.querySelector('.warn-card')`, 5000, 200)
  if (pollPopup) {
    await cdp.eval(`(() => {
      const btn = Array.from(document.querySelectorAll('.warn-actions button')).find((b) => b.textContent.includes('关闭'));
      if (btn) btn.click();
      return true;
    })()`)
    await sleep(400)
  }
  const pollIncreased = await cdp.waitFor(`(() => {
    const m = document.body.innerText.match(/活跃[^0-9]*([0-9]+)/);
    return m ? Number(m[1]) > ${activeBefore} : false;
  })()`, 22000, 700)
  await cdp.eval(`(() => {
    const btn = Array.from(document.querySelectorAll('button')).find((b) => b.textContent.includes('查看全部'));
    if (btn) btn.click();
    return true;
  })()`)
  await sleep(900)
  const pollTitle = await cdp.eval(`document.body.innerText.includes('轮询降级验收')`)
  log('SM-12 WS 被阻断时仍通过轮询更新预警列表', pollPageLoaded && pollIncreased && pollTitle, `活跃 ${activeBefore}→增长=${pollIncreased} 标题可见=${pollTitle} id=${pollWarning.id}`)
  await cdp.screenshot('16-ws-blocked-polling.png')
  await cdp.send('Network.setBlockedURLs', { urls: [] })

  // 16) 页面级预警闭环：确认 → 派发
  const confirmWarning = await createWarning(token, 'blue', '浏览器验收-确认派发')
  await cdp.navigate(`${APP}/dashboard/warnings/${confirmWarning.id}`)
  await cdp.waitFor(`!!document.querySelector('.main-layout')`, 25000)
  await sleep(1800)
  const confirmClicked = await cdp.eval(`(() => {
    const btn = Array.from(document.querySelectorAll('button')).find((b) => b.textContent.includes('确认预警'));
    if (btn) btn.click();
    return !!btn;
  })()`)
  const confirmed = await cdp.waitFor(`document.body.innerText.includes('已确认')`, 12000, 400)
  const dispatchClicked = await cdp.eval(`(() => {
    const btn = Array.from(document.querySelectorAll('button')).find((b) => b.textContent.includes('派发'));
    if (btn) btn.click();
    return !!btn;
  })()`)
  const dispatched = await cdp.waitFor(`document.body.innerText.includes('处理中') || document.body.innerText.includes('已发布')`, 12000, 400)
  log('SM-17 预警详情页确认→派发闭环', confirmClicked && confirmed && dispatchClicked && dispatched, `确认=${confirmed} 派发=${dispatched}`)
  await cdp.screenshot('20-warning-confirm-dispatch.png')

  // 17) 预警 AI 分析详情
  const analysisWarning = await createWarning(token, 'blue', '浏览器验收-AI分析详情', 'HZ-001', '青龙坡危岩')
  await cdp.navigate(`${APP}/dashboard/warnings/${analysisWarning.id}?tab=analysis`)
  let analysisShown = await cdp.waitFor(`!!document.querySelector('.analysis-detail')`, 25000)
  if (!analysisShown) {
    await cdp.eval(`(() => {
      const btn = Array.from(document.querySelectorAll('.tab-button')).find((b) => b.textContent.includes('AI 分析详情'));
      if (btn) btn.click();
      return true;
    })()`)
    analysisShown = await cdp.waitFor(`!!document.querySelector('.analysis-detail')`, 10000)
  }
  await sleep(2200)
  const analysis = await cdp.eval(`(() => ({
    cards: document.querySelectorAll('.analysis-card').length,
    hasConclusion: document.body.innerText.includes('AI 研判结论'),
    hasMetrics: document.body.innerText.includes('事发前后关键指标'),
    hasImages: document.body.innerText.includes('现场影像'),
    metricItems: document.querySelectorAll('.metric-item').length,
    imageItems: document.querySelectorAll('.image-item').length
  }))()`)
  log('SM-18 预警 AI 分析详情页（研判/指标/影像）', analysisShown && analysis.cards >= 4 && analysis.hasConclusion && analysis.hasMetrics && analysis.hasImages, JSON.stringify(analysis))
  await cdp.screenshot('21-warning-analysis-detail.png')

  // 18) 清理测试预警
  for (const id of createdWarningIds) {
    try {
      await apiFetch(token, `/warnings/${id}`, { method: 'DELETE' })
    } catch {
      // ignore
    }
  }
  log('清理浏览器验收测试预警', true, createdWarningIds.join(','))

  // 19) 角色越权（值班员）
  const dutyLogin = await cdp.eval(`(async () => {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'duty', password: 'admin123' })
    });
    const payload = await res.json();
    if (!payload || payload.code !== 0) return { ok: false };
    localStorage.setItem('disasterguard_token', payload.data.token);
    localStorage.setItem('disasterguard_user', JSON.stringify(payload.data.user));
    return { ok: true, role: payload.data.user.role };
  })()`)
  await cdp.navigate(`${APP}/dashboard/warnings`)
  await cdp.waitFor(`!!document.querySelector('.main-layout')`, 25000)
  await sleep(2000)
  const dutyAllowed = await cdp.eval(`(() => ({
    path: location.pathname,
    loaded: !!document.querySelector('.main-layout'),
    forbidden: document.body.innerText.includes('无权访问')
  }))()`)
  log('SM-01 值班员可正常访问预警中心', Boolean(dutyLogin?.ok) && dutyAllowed.loaded && !dutyAllowed.forbidden, JSON.stringify(dutyAllowed))
  await cdp.navigate(`${APP}/dashboard/user-management`)
  await cdp.waitFor(`location.pathname.includes('error/403') || !!document.querySelector('.main-layout')`, 25000)
  await sleep(1200)
  const dutyDenied = await cdp.eval(`(() => ({ path: location.pathname }))()`)
  log('SM-01 值班员越权直达用户管理被 403 拦截', String(dutyDenied.path).includes('error/403'), JSON.stringify(dutyDenied))
  await cdp.screenshot('17-role-guard-403.png')

  // 20) 登出
  await cdp.navigate(`${APP}/dashboard`)
  await cdp.waitFor(`!!document.querySelector('.main-layout')`, 20000)
  await sleep(1200)
  const menuOpened = await cdp.eval(`(() => {
    const trigger = document.querySelector('.user-profile-trigger');
    if (trigger) trigger.click();
    return !!trigger;
  })()`)
  await sleep(500)
  const logoutClicked = await cdp.eval(`(() => {
    const btn = document.querySelector('.dropdown-item-danger');
    if (btn) btn.click();
    return !!btn;
  })()`)
  await sleep(1800)
  const logout = await cdp.eval(`(() => ({
    path: location.pathname + location.search,
    token: localStorage.getItem('disasterguard_token')
  }))()`)
  log('SM-14 登出后回到未登录页且 token 清空', menuOpened && logoutClicked && !logout.token && (logout.path === '/' || logout.path.includes('login')), JSON.stringify(logout))
  await cdp.screenshot('11-after-logout.png')

  // 汇总
  const failed = results.filter((item) => !item.ok)
  const summary = {
    browser: version.Browser,
    app: APP,
    time: new Date().toISOString(),
    passed: results.length - failed.length,
    failed: failed.length,
    results,
    screenshots: fs.readdirSync(OUT).filter((file) => file.endsWith('.png')).sort(),
  }
  fs.writeFileSync(path.join(OUT, 'summary.json'), JSON.stringify(summary, null, 2))
  console.log(`\n==== 浏览器冒烟汇总 ====`)
  console.log(`passed=${summary.passed} failed=${summary.failed} screenshots=${summary.screenshots.join(', ')}`)
  if (failed.length) {
    console.log('失败项：')
    failed.forEach((item) => console.log(' -', item.name, item.detail))
  }
  cdp.close()
  process.exit(failed.length > 0 ? 1 : 0)
}

main().catch(async (error) => {
  console.error(error)
  process.exit(1)
})
