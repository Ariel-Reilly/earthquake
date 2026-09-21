/**
 * Sentinel-1（雷达 SAR）+ Sentinel-2（光学真彩）合成卫星服务。
 *
 * 数据源策略：
 *   ① Copernicus Data Space（https://dataspace.copernicus.eu）— ESA 官方 2024+ 上线，
 *      提供 STAC 搜索 + Sentinel-1 GRD + Sentinel-2 L2A 同步拉取
 *   ② Google Earth Engine（GEE）— 老牌合成引擎，提供预生成 GEE asset / Tile 服务的 256px 瓦片
 *
 * 合成管线：
 *   client 调用 composeSentinelScene() → AI Service /sentinel/compose 提交一个 AOI bbox +
 *   时间窗口 → 后端到 Copernicus / GEE 拉取最近一份 S1 + S2 → 用 GEE Python SDK 做合成：
 *     - S2 真彩（B4/B3/B2）作为底色
 *     - S1 SAR 伪彩色（VV→红，VH→绿，VV/VH 比值→蓝）叠加在 S2 之上，按"形变指示"权重调节不透明度
 *   → 输出两份 PNG WMTS URL（S2 真彩、S1 SAR 伪彩色），前端按 zIndex 叠在卫星图层上
 *
 * 取消支持 v1.7.1 引入的"通用卫星图源"（高德 / 天地图 / ESRI / Mapbox / Google 等）。
 * 视图底图仅用矢量（天地图 / 高德矢量 / OSM 兜底），卫星底图只走 Sentinel 合成。
 */

/**
 * 解析 AI 服务基础 URL。
 * 优先 VITE_AI_SERVICE_BASE（推荐，例如 http://localhost:8001/api/ai）。
 * 没设时回退到 VITE_AI_SERVICE_BASE_URL，再没设就默认 8001（开发约定）。
 *
 * 路径兼容规则（兼容历史 /api/ai 写法）：
 *   - 已以 /api/ai 结尾：直接返回
 *   - 以 /api 结尾：补 /ai
 *   - 其它：拼 /api/ai 尾巴
 */
const DEFAULT_AI_SERVICE_BASE = (() => {
  const raw =
    import.meta.env.VITE_AI_SERVICE_BASE ||
    import.meta.env.VITE_AI_SERVICE_BASE_URL ||
    'http://localhost:8001/api/ai'
  const base = String(raw).replace(/\/$/, '')
  if (/\/api\/ai$/.test(base)) {return base}
  if (/\/api$/.test(base)) {return base + '/ai'}
  return base + '/api/ai'
})()
const COMPOSE_PATH = '/sentinel/compose'
const STATUS_PATH = (jobId) => `/sentinel/jobs/${encodeURIComponent(jobId)}`

/**
 * @typedef {Object} SentinelComposeRequest
 * @property {[number, number, number, number]} bbox  - AOI bbox，[minLon, minLat, maxLon, maxLat]
 * @property {string} [start]    - ISO 日期或相对时间（默认最近 30 天）
 * @property {string} [end]
 * @property {number} [maxCloud]  - 最大云量覆盖（默认 15%）
 * @property {'gee'|'copernicus'} [provider] - 后端合成引擎，默认优先 Copernicus
 * @property {boolean} [sarOverlay] - 是否叠加 SAR 伪彩色（默认 true）
 * @property {boolean} [s2TrueColor] - 是否叠加 S2 真彩（默认 true）
 *
 * @typedef {Object} SentinelJob
 * @property {string} jobId
 * @property {'queued'|'running'|'succeeded'|'failed'|'cancelled'} status
 * @property {number} progress   0..100
 * @property {string} [message]
 * @property {{ s2Url?: string, sarUrl?: string, wmtsTemplate?: string, expiresAt?: string }} [result]
 */

/**
 * 提交一次 Sentinel 合成任务，返回 jobId。
 * 失败抛 Error 并附中文 message 供 UI 直接展示。
 */
export async function composeSentinelScene(req) {
  const body = sanitizeComposeRequest(req)
  const url = `${DEFAULT_AI_SERVICE_BASE}${COMPOSE_PATH}`
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!resp.ok) {
    const text = await safeReadText(resp)
    throw new Error(`提交 Sentinel 合成任务失败（HTTP ${resp.status}）：${text || resp.statusText}`)
  }
  const payload = await resp.json().catch(() => ({}))
  if (!payload || !payload.jobId) {
    throw new Error('Sentinel 合成服务未返回 jobId，请确认 AI Service 已升级到带 /sentinel/compose 路由的版本')
  }
  return /** @type {SentinelJob} */ ({
    jobId: payload.jobId,
    status: payload.status || 'queued',
    progress: Number(payload.progress) || 0,
    message: payload.message,
  })
}

/**
 * 拉取一次任务状态。失败时抛 Error。
 */
export async function fetchSentinelJob(jobId) {
  if (!jobId) {throw new Error('fetchSentinelJob: jobId 必填')}
  const url = `${DEFAULT_AI_SERVICE_BASE}${STATUS_PATH(jobId)}`
  const resp = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!resp.ok) {
    const text = await safeReadText(resp)
    throw new Error(`查询 Sentinel 任务失败（HTTP ${resp.status}）：${text || resp.statusText}`)
  }
  const payload = await resp.json().catch(() => ({}))
  return /** @type {SentinelJob} */ ({
    jobId: payload.jobId || jobId,
    status: payload.status || 'unknown',
    progress: Number(payload.progress) || 0,
    message: payload.message,
    result: payload.result,
  })
}

/**
 * 轮询 Sentinel 任务直到 status 终态（succeeded / failed / cancelled）。
 * onProgress 在每次轮询拿到中间进度时被调用，便于 UI 渲染进度条。
 * 默认间隔 1500ms，最多轮询 5 分钟。
 *
 * @param {string} jobId
 * @param {(job: SentinelJob) => void} onProgress
 * @param {{ intervalMs?: number, timeoutMs?: number, signal?: AbortSignal }} [options]
 * @returns {Promise<SentinelJob>}
 */
export async function pollSentinelJob(jobId, onProgress, options = {}) {
  const intervalMs = options.intervalMs ?? 1500
  const timeoutMs = options.timeoutMs ?? 5 * 60 * 1000
  const signal = options.signal
  const startedAt = Date.now()

  // 第一次立即拉取，给 UI 立刻一个反馈
  let job = await fetchSentinelJob(jobId)
  if (onProgress) {safeCallback(onProgress, job)}
  if (isTerminalStatus(job.status)) {return job}

  while (Date.now() - startedAt < timeoutMs) {
    if (signal && signal.aborted) {
      throw new DOMException('轮询被取消', 'AbortError')
    }
    await sleep(intervalMs)
    job = await fetchSentinelJob(jobId)
    if (onProgress) {safeCallback(onProgress, job)}
    if (isTerminalStatus(job.status)) {return job}
  }
  throw new Error(`Sentinel 合成任务超时（>${Math.round(timeoutMs / 1000)}s）`)
}

/**
 * 取消一个 Sentinel 合成任务。已处于终态时静默成功。
 */
export async function cancelSentinelJob(jobId) {
  if (!jobId) {return false}
  const url = `${DEFAULT_AI_SERVICE_BASE}${STATUS_PATH(jobId)}/cancel`
  try {
    const resp = await fetch(url, { method: 'POST' })
    return resp.ok
  } catch (e) {
    console.warn('[sentinelService] 取消任务失败', e)
    return false
  }
}

/**
 * 把 WMTS / XYZ 模板 URL 转成 OL ImageStaticSource 可直接用的 url 字符串。
 * 如果后端只返回 AOC bbox + WMTS template，由前端按 z/x/y 替换，OL 用不了 XYZ 接口，
 * 所以这里只透传"已拼接好的瓦片 URL（一般是 EPSG:3857 范围 + 单张 PNG）"。
 *
 * @param {string | undefined} url
 * @returns {string}
 */
export function resolveSentinelTileUrl(url) {
  if (!url) {return ''}
  return String(url)
}

/**
 * 把请求体做一次校验和补全，避免给后端发空 AOI / 非法 bbox。
 */
function sanitizeComposeRequest(req) {
  const fallbackWindow = { days: 30 }
  const bbox = sanitizeBbox(req && req.bbox)
  if (!bbox) {
    throw new Error('Sentinel 合成需要有效的 AOI bbox：[minLon, minLat, maxLon, maxLat]')
  }
  const start = (req && req.start) || relativeDays(fallbackWindow.days)
  const end = (req && req.end) || new Date().toISOString().slice(0, 10)
  const maxCloud = Number.isFinite(req && req.maxCloud) ? req.maxCloud : 15
  return {
    bbox,
    start,
    end,
    maxCloud,
    provider: (req && req.provider) || 'copernicus',
    sarOverlay: req && req.sarOverlay === false ? false : true,
    s2TrueColor: req && req.s2TrueColor === false ? false : true,
  }
}

function sanitizeBbox(bbox) {
  if (!Array.isArray(bbox) || bbox.length !== 4) {return null}
  const [minLon, minLat, maxLon, maxLat] = bbox.map(Number)
  if (![minLon, minLat, maxLon, maxLat].every(Number.isFinite)) {return null}
  if (minLon >= maxLon || minLat >= maxLat) {return null}
  // 简单合法性：经度 -180..180，纬度 -85..85（WMS/WMTS 限制）
  if (minLon < -180 || maxLon > 180 || minLat < -85 || maxLat > 85) {return null}
  return [minLon, minLat, maxLon, maxLat]
}

function relativeDays(days) {
  const d = new Date()
  d.setUTCDate(d.getUTCDate() - days)
  return d.toISOString().slice(0, 10)
}

function isTerminalStatus(status) {
  return status === 'succeeded' || status === 'failed' || status === 'cancelled'
}

function safeCallback(cb, value) {
  try {
    cb(value)
  } catch (e) {
    console.warn('[sentinelService] onProgress 回调异常', e)
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function safeReadText(resp) {
  try {
    return await resp.text()
  } catch (_e) {
    return ''
  }
}

/**
 * UI 用：把"当前合成状态"映射成展示文案。
 */
export function describeJobStatus(job) {
  if (!job) {return { label: '空闲', tone: 'idle', percent: 0 }}
  const percent = Math.max(0, Math.min(100, Math.round(job.progress || 0)))
  switch (job.status) {
    case 'queued':
      return { label: '排队中…', tone: 'pending', percent }
    case 'running':
      return { label: `合成中（${percent}%）`, tone: 'running', percent }
    case 'succeeded':
      return { label: '合成完成', tone: 'success', percent: 100 }
    case 'failed':
      return { label: job.message || '合成失败', tone: 'error', percent }
    case 'cancelled':
      return { label: '已取消', tone: 'idle', percent }
    default:
      return { label: '未知状态', tone: 'idle', percent }
  }
}
