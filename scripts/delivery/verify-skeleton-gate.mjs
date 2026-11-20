/**
 * 骨架屏防回归门禁（正式脚本 / 可重复运行）
 *
 * 前置：
 *   1) 前端 dev server:  cd frontend && npm run dev -- --host 127.0.0.1 --port 5173
 *   2) 浏览器 CDP:       chrome --headless=new --remote-debugging-port=9222 ...
 *      （脚本自动探测 9222 / 9223，也可用环境变量 DG_CDP 指定，如 DG_CDP=http://127.0.0.1:9223）
 *   3) 运行:             node scripts/delivery/verify-skeleton-gate.mjs
 *      或从前端目录:     npm run verify:skeleton
 *
 * 检查项（对应交付收口 P3 要求，共 41 项）：
 *   A. 详情页骨架出现 + role/aria 契约（DETAIL_PAGES 数组，加页时在此扩展）
 *   B. 表格骨架宽度序列稳定（无 Math.random 抖动）
 *   C. 3 秒慢提示挂载、8 秒后不与兜底提示重复（源码契约断言）
 *   D. 1440 / 1024 / 375 三档无横向溢出
 *   E. Phase 2 页面：OTA 表格骨架 + 错误态重试、灾害详情局部骨架（不碰地图）
 *   F. 接口失败后骨架正确收起、错误态可见（不永久卡在骨架）
 *   G. 截图留存到 output/video-review/
 *
 * 说明：mock 模式下数据秒回，骨架仅存约 280ms，故用 MutationObserver 在文档
 *       加载阶段捕获骨架 DOM，再做断言；慢提示 3s 才显示，用源码断言其契约。
 *       后端未启动时接口快速失败，恰好覆盖 F 段的错误态路径。
 */
import { readFileSync as read, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// 以脚本所在位置推导仓库根目录，避免硬编码盘符（可在任意 cwd 下运行）
const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SRC = path.join(REPO, 'frontend/src')
const SHOT_DIR = path.join(REPO, 'output/video-review')
const APP = process.env.DG_APP || 'http://127.0.0.1:5173'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// 动态生成 exp（默认 +365 天），避免写死 token 到期后脚本失效
const TOKEN = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.' +
  Buffer.from(JSON.stringify({ sub: 'admin', exp: Math.floor(Date.now() / 1000) + 365 * 86400, role: 'admin' }))
    .toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') + '.sig'

// CDP 端口自动探测：正式脚本与既有 delivery 脚本一致默认 9222，兼容临时调试用的 9223
async function pickCdp() {
  if (process.env.DG_CDP) return process.env.DG_CDP
  for (const p of [9222, 9223]) {
    try {
      const r = await fetch(`http://127.0.0.1:${p}/json/version`, { signal: AbortSignal.timeout(1200) })
      if (r.ok) return `http://127.0.0.1:${p}`
    } catch { /* 端口未开，试下一个 */ }
  }
  return 'http://127.0.0.1:9222'
}
const CDP = await pickCdp()

// 已实现骨架的详情页：加新页时在此追加即可自动纳入门禁
const DETAIL_PAGES = [
  { name: 'TaskDetail', path: '/dashboard/ai-tasks/task-001', sel: '.td-skeleton' },
  { name: 'DeviceDetail', path: '/dashboard/devices/dev-001', sel: '.dd-skeleton' },
]

let pass = 0
let fail = 0
const log = (name, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
  ok ? pass++ : fail++
}

mkdirSync(SHOT_DIR, { recursive: true })

console.log(`骨架门禁：APP=${APP} CDP=${CDP}`)
let tab
try {
  tab = await (await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })).json()
} catch (e) {
  console.error(`无法连接 CDP ${CDP}：${e.message}\n请先启动浏览器调试端口（见脚本头部前置说明）。`)
  process.exit(2)
}
const ws = new WebSocket(tab.webSocketDebuggerUrl)
await new Promise((res, rej) => {
  ws.addEventListener('open', res, { once: true })
  ws.addEventListener('error', () => rej(new Error('CDP WebSocket 连接失败')), { once: true })
})
let id = 0
const pend = new Map()
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data)
  if (m.id && pend.has(m.id)) { pend.get(m.id)(m.result); pend.delete(m.id) }
})
const send = (method, params = {}) =>
  new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method, params })) })
const ev = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception?.description || ''))
  return r.result?.value
}
const shot = async (name) => {
  const r = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(path.join(SHOT_DIR, `gate-${name}.png`), Buffer.from(r.data, 'base64'))
}

await send('Page.enable')
await send('Runtime.enable')
// 显式设置初始视口，避免复用 CDP 标签页时残留上一轮的小屏 override（会导致误判单列）
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

// 文档加载前注入登录态 + 骨架观察器：捕获每个骨架首次出现时的契约属性
const selectors = DETAIL_PAGES.map((p) => p.sel).concat(['.skeleton-table', '.hz-skeleton'])
await send('Page.addScriptToEvaluateOnNewDocument', {
  source: `
  (function(){
    try {
      localStorage.setItem('disasterguard_token', '${TOKEN}');
      localStorage.setItem('disasterguard_user', JSON.stringify({id:'u-admin',username:'admin',name:'管理员',role:'admin'}));
    } catch(e){}
    window.__gate = {};
    var sels = ${JSON.stringify(selectors)};
    var watch = function(){
      sels.forEach(function(sel){
        var el = document.querySelector(sel);
        if (el && !window.__gate[sel]) {
          window.__gate[sel] = {
            role: el.getAttribute('role'),
            label: el.getAttribute('aria-label'),
            busy: el.getAttribute('aria-busy'),
            widths: Array.from((el.querySelector('.skeleton-table-row')||{querySelectorAll:function(){return []}}).querySelectorAll('.skeleton-bar')).map(function(b){return b.style.width}),
            gridCols: (function(){
              var g = el.querySelector('.dd-grid,.td-grid');
              return g ? getComputedStyle(g).gridTemplateColumns : getComputedStyle(el).gridTemplateColumns;
            })()
          };
        }
      });
    };
    var o = new MutationObserver(watch);
    document.addEventListener('DOMContentLoaded', function(){ o.observe(document.documentElement,{childList:true,subtree:true}); watch(); });
  })();`
})

async function capture(route) {
  await ev('window.__gate = {}')
  await send('Page.navigate', { url: APP + route })
  for (let i = 0; i < 50; i++) {
    if ((await ev('Object.keys(window.__gate || {}).length')) > 0) break
    await sleep(25)
  }
  return ev('window.__gate')
}

// A. 详情页骨架 + 契约
for (const p of DETAIL_PAGES) {
  const g = await capture(p.path)
  const s = g[p.sel]
  log(`${p.name} 骨架出现`, !!s)
  if (s) {
    log(`${p.name} role=status`, s.role === 'status', `role=${s.role}`)
    log(`${p.name} aria-label`, !!s.label, `label=${s.label}`)
    log(`${p.name} aria-busy=true`, s.busy === 'true', `busy=${s.busy}`)
    log(`${p.name} 两列 grid 同构`, String(s.gridCols).trim().split(/\s+/).length === 2, `cols=${s.gridCols}`)
  }
  await shot(`${p.name.toLowerCase()}`)
}

// B. 表格骨架宽度稳定（选一个用 SkeletonTable 的页面）
const dev = await capture('/dashboard/devices')
const tbl = dev['.skeleton-table']
const seq = tbl?.widths?.join('|') || ''
log('SkeletonTable 宽度序列稳定', /^(70%|80%|90%|100%)(\|(70%|80%|90%|100%))*$/.test(seq) && seq.length > 0, `seq=${seq}`)
const tblSrc = read(path.join(SRC, 'components/common/SkeletonTable.vue'), 'utf8')
// 去掉注释后再检测实际随机调用，避免注释里提到 Math.random 造成误报
const tblCode = tblSrc.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')
log('SkeletonTable 无 Math.random 调用', !/Math\.random\s*\(/.test(tblCode))

// C. 慢提示契约：3 秒组件存在且 delay=3000；8 秒兜底提示与 3 秒提示互斥
const slowSrc = read(path.join(SRC, 'components/common/SkeletonSlowHint.vue'), 'utf8')
log('慢提示默认 3000ms', /delay[^}]*default:\s*3000/.test(slowSrc.replace(/\s+/g, ' ')))
const aiSrc = read(path.join(SRC, 'components/ai/AiComprehensiveTab.vue'), 'utf8')
const hintIdx = aiSrc.indexOf('<SkeletonSlowHint')
const hintBlock = hintIdx >= 0 ? aiSrc.slice(hintIdx, hintIdx + 220) : ''
log('3s 提示受 <8s 守卫（不与兜底重复）', /v-if="loadingSeconds < 8"/.test(hintBlock))
log('8s 兜底提示存在', /loadingSeconds >= 8/.test(aiSrc))
// 所有整页骨架都应接慢提示
const skeletonFiles = [
  'AiInsightSkeleton', 'AiResultsSkeleton', 'AnalyticsSkeleton', 'DamageAssessmentSkeleton',
  'DataSourcesSkeleton', 'DataSyncSkeleton', 'EmergencySkeleton', 'InspectionSkeleton',
  'OverviewSkeleton', 'RealTimeMapSkeleton', 'SystemMonitorSkeleton', 'WarningsSkeleton',
  'ZoneDashboardSkeleton', 'TaskDetailSkeleton', 'DeviceDetailSkeleton', 'HazardDetailSkeleton',
]
const missingHint = skeletonFiles.filter((n) => !/SkeletonSlowHint/.test(read(path.join(SRC, `components/common/${n}.vue`), 'utf8')))
log('所有整页骨架均接慢提示', missingHint.length === 0, missingHint.length ? `缺失: ${missingHint.join(',')}` : `${skeletonFiles.length} 个`)

// D. 三档响应式无横向溢出（用详情页实测）
for (const w of [1440, 1024, 375]) {
  await send('Emulation.setDeviceMetricsOverride', { width: w, height: 860, deviceScaleFactor: 1, mobile: w < 600 })
  await send('Page.navigate', { url: APP + DETAIL_PAGES[0].path })
  await sleep(500)
  const r = await ev('({s:document.documentElement.scrollWidth,c:document.documentElement.clientWidth})')
  log(`${w}px 无横向溢出`, r.s <= r.c + 1, `scrollW=${r.s} clientW=${r.c}`)
  if (w === 375) await shot('detail-375')
}

// E. Phase 2 页面契约：OTA + 灾害详情局部骨架（源码断言 + 浏览器实测）
// 复位视口到 1440，避免 D 段残留 375px 影响断言
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

const otaSrc = read(path.join(SRC, 'views/device/OtaManagePage.vue'), 'utf8')
const hazSrc = read(path.join(SRC, 'views/hazard/HazardDetailPage.vue'), 'utf8')
const hzSkelSrc = read(path.join(SRC, 'components/common/HazardDetailSkeleton.vue'), 'utf8')
// OTA：两处 SkeletonTable(7 列) + loadError + role=alert + 重试 + try/finally 保底
log('OTA 接入 SkeletonTable(7 列)', (otaSrc.match(/<SkeletonTable[\s\S]*?:columns="7"/g) || []).length >= 2)
log('OTA 有持久错误态 role=alert', /class="detail-load-error"[^>]*role="alert"/.test(otaSrc))
log('OTA 错误态带重试按钮', /重试加载/.test(otaSrc) && /@click="loadAll"/.test(otaSrc))
log('OTA loadAll 有 finally 收起 loading', /finally\s*\{[\s\S]*?loading\.value = false/.test(otaSrc))
// HazardDetail：三处局部骨架 section + 保留错误态重试 + 不碰地图整页骨架
log('HazardDetail 接入局部骨架(三处 section)', ['timeline', 'info', 'evidence'].every((s) => hazSrc.includes(`section="${s}"`)))
log('局部骨架只覆盖信息区(无地图容器)', !/hz-skeleton[\s\S]*?(ol-map|OpenLayers|map-container)/i.test(hzSkelSrc))
log('HazardDetail 保留错误态重试', /loadError/.test(hazSrc) && /重试/.test(hazSrc))
log('局部骨架 role/aria 契约', /role="status"/.test(hzSkelSrc) && /aria-busy="true"/.test(hzSkelSrc))

// 浏览器实测：OTA 页骨架出现 + 契约（后端未起→骨架撤掉后应见错误卡，不永久卡）
const otaGate = await capture('/dashboard/devices/ota')
const otaTbl = otaGate['.skeleton-table']
log('OTA 表格骨架出现', !!otaTbl)
if (otaTbl) {
  log('OTA 表格骨架 role=status', otaTbl.role === 'status', `role=${otaTbl.role}`)
  log('OTA 表格骨架 aria-busy=true', otaTbl.busy === 'true', `busy=${otaTbl.busy}`)
}
await sleep(500)
const otaAfter = await ev('({sk:!!document.querySelector(".skeleton-table"),err:!!document.querySelector("[role=alert]")})')
log('OTA 失败后骨架收起且不永久卡', otaAfter.sk === false, `sk=${otaAfter.sk}`)
log('OTA 失败后错误态可见', otaAfter.err === true, `err=${otaAfter.err}`)

// 浏览器实测：灾害详情局部骨架出现 + 契约
const hazGate = await capture('/dashboard/hazards/h-001')
const hz = hazGate['.hz-skeleton']
log('HazardDetail 局部骨架出现', !!hz)
if (hz) {
  log('HazardDetail 局部骨架 role=status', hz.role === 'status', `role=${hz.role}`)
  log('HazardDetail 局部骨架 aria-busy=true', hz.busy === 'true', `busy=${hz.busy}`)
}

// 三档无横向溢出：两个新页面
for (const route of ['/dashboard/devices/ota', '/dashboard/hazards/h-001']) {
  for (const w of [1440, 1024, 375]) {
    await send('Emulation.setDeviceMetricsOverride', { width: w, height: 860, deviceScaleFactor: 1, mobile: w < 600 })
    await send('Page.navigate', { url: APP + route })
    await sleep(400)
    const r = await ev('({s:document.documentElement.scrollWidth,c:document.documentElement.clientWidth})')
    log(`${route.split('/').pop()} @${w}px 无横向溢出`, r.s <= r.c + 1, `${r.s}/${r.c}`)
  }
}

console.log(`\n===== 骨架门禁 ${pass}/${pass + fail} PASS =====`)
ws.close()
process.exit(fail > 0 ? 1 : 0)
