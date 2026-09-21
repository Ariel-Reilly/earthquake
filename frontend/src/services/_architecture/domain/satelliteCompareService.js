/**
 * 时相对比卫星影像差异服务（v1.0 / P0 卫星对比界面）。
 *
 * 流程：
 *   1. 调用 composeSentinelScene() 提交两次合成（灾前 + 灾后），得到 beforeJobId/afterJobId
 *   2. 调用 submitSatelliteDiff() 提交差异计算，得到 diffJobId
 *   3. 调用 pollSatelliteDiffJob() 轮询差异任务直到 succeeded
 *   4. 返回差异热力图 URL + 热点列表
 *
 * 三种对比模式（前端画布渲染层做，本服务只提供数据）：
 *   - 卷帘（swipe）：左右两张图，中间一根竖线可拖动
 *   - 闪烁（flicker）：左右按钮或自动切换
 *   - 并排（side-by-side）：左右各一张
 *
 * 差异叠加层：把差异热力图以 alpha 通道叠在灾前图上，红色高亮变化区。
 */

import { composeSentinelScene, fetchSentinelJob, resolveSentinelTileUrl } from '../core/sentinelService'

// 复用 sentinelService 的解析逻辑，路径前缀相同（差异计算也是 /api/ai/sentinel/*）
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
const DIFF_SUBMIT_PATH = '/sentinel/diff'
const DIFF_STATUS_PATH = (jobId) => `/sentinel/diff-jobs/${encodeURIComponent(jobId)}`

/**
 * @typedef {Object} SatelliteDiffRequest
 * @property {string} [beforeJobId] - 灾前合成任务 ID（与 beforeUrl 二选一）
 * @property {string} [afterJobId]  - 灾后合成任务 ID
 * @property {string} [beforeUrl]   - 灾前图像 URL（HTTP/file/mock）
 * @property {string} [afterUrl]    - 灾后图像 URL
 * @property {[number, number, number, number]} [bbox] - AOI bbox，用于热点经纬度映射
 * @property {number} [threshold]   - 像素差异阈值 0-255，默认 30
 * @property {'inferno'|'viridis'|'red'|'grayscale'} [colormap] - 配色，默认 inferno
 */

/**
 * @typedef {Object} SatelliteDiffResult
 * @property {string} diffUrl        - 差异热力图 PNG URL
 * @property {number} changePixels
 * @property {number} totalPixels
 * @property {number} changePercent  - 变化占比 0..100
 * @property {number} threshold
 * @property {string} colormap
 * @property {{ width: number, height: number }} imageSize
 * @property {Array<{x:number,y:number,width:number,height:number,intensity:number,pixel_count:number,lon?:number,lat?:number}>} hotspots
 * @property {{ beforeUrl: string, afterUrl: string, bbox?: number[], computedAt: string }} meta
 */

/**
 * 提交差异计算任务，返回 jobId。
 *
 * @param {SatelliteDiffRequest} req
 * @returns {Promise<{ jobId: string, status: string, progress: number, message?: string }>}
 */
export async function submitSatelliteDiff(req) {
  const body = sanitizeDiffRequest(req)
  const url = `${DEFAULT_AI_SERVICE_BASE}${DIFF_SUBMIT_PATH}`
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!resp.ok) {
    const text = await safeReadText(resp)
    throw new Error(`提交差异计算任务失败（HTTP ${resp.status}）：${text || resp.statusText}`)
  }
  const payload = await resp.json().catch(() => ({}))
  if (!payload || !payload.jobId) {
    throw new Error('差异计算服务未返回 jobId，请确认 AI Service 已升级到带 /sentinel/diff 路由的版本')
  }
  return {
    jobId: payload.jobId,
    status: payload.status || 'queued',
    progress: Number(payload.progress) || 0,
    message: payload.message,
  }
}

/**
 * 查询差异任务状态。
 * @param {string} jobId
 * @returns {Promise<{ jobId: string, status: string, progress: number, message?: string, result?: SatelliteDiffResult }>}
 */
export async function fetchSatelliteDiffJob(jobId) {
  if (!jobId) {throw new Error('fetchSatelliteDiffJob: jobId 必填')}
  const url = `${DEFAULT_AI_SERVICE_BASE}${DIFF_STATUS_PATH(jobId)}`
  const resp = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!resp.ok) {
    const text = await safeReadText(resp)
    throw new Error(`查询差异任务失败（HTTP ${resp.status}）：${text || resp.statusText}`)
  }
  const payload = await resp.json().catch(() => ({}))
  return {
    jobId: payload.jobId || jobId,
    status: payload.status || 'unknown',
    progress: Number(payload.progress) || 0,
    message: payload.message,
    result: payload.result || null,
  }
}

/**
 * 轮询差异任务直到终态。
 * @param {string} jobId
 * @param {(job: any) => void} onProgress
 * @param {{ intervalMs?: number, timeoutMs?: number, signal?: AbortSignal }} [options]
 * @returns {Promise<{ jobId: string, status: string, progress: number, result?: SatelliteDiffResult }>}
 */
export async function pollSatelliteDiffJob(jobId, onProgress, options = {}) {
  const intervalMs = options.intervalMs ?? 1500
  const timeoutMs = options.timeoutMs ?? 5 * 60 * 1000
  const signal = options.signal
  const startedAt = Date.now()

  let job = await fetchSatelliteDiffJob(jobId)
  if (onProgress) {safeCallback(onProgress, job)}
  if (isTerminalStatus(job.status)) {return job}

  while (Date.now() - startedAt < timeoutMs) {
    if (signal && signal.aborted) {throw new DOMException('轮询被取消', 'AbortError')}
    await sleep(intervalMs)
    job = await fetchSatelliteDiffJob(jobId)
    if (onProgress) {safeCallback(onProgress, job)}
    if (isTerminalStatus(job.status)) {return job}
  }
  throw new Error(`差异计算任务超时（>${Math.round(timeoutMs / 1000)}s）`)
}

/**
 * 一站式：传入灾前/灾后 bbox + 时间窗口，自动完成"两次合成 + 一次差异"。
 *
 * @param {{
 *   bbox: [number, number, number, number],
 *   beforeWindow: { start: string, end: string },
 *   afterWindow:  { start: string, end: string },
 *   threshold?: number,
 *   colormap?: 'inferno'|'viridis'|'red'|'grayscale',
 *   maxCloud?: number,
 * }} params
 * @param {{
 *   onComposeProgress?: (stage: 'before'|'after', job: any) => void,
 *   onDiffProgress?: (job: any) => void,
 *   signal?: AbortSignal,
 * }} [callbacks]
 * @returns {Promise<{ beforeJobId: string, afterJobId: string, diff: SatelliteDiffResult, diffJobId: string }>}
 */
export async function runCompareWorkflow(params, callbacks = {}) {
  const { bbox, beforeWindow, afterWindow } = params
  if (!bbox || bbox.length !== 4) {throw new Error('runCompareWorkflow: 需要 AOI bbox')}

  // 1. 灾前合成
  const beforeReq = await composeSentinelScene({
    bbox,
    start: beforeWindow.start,
    end: beforeWindow.end,
    maxCloud: params.maxCloud ?? 15,
  })
  if (callbacks.onComposeProgress) {callbacks.onComposeProgress('before', beforeReq)}
  const beforeDone = await pollCompose(beforeReq.jobId, callbacks.signal, (job) => {
    if (callbacks.onComposeProgress) {callbacks.onComposeProgress('before', job)}
  })

  // 2. 灾后合成
  const afterReq = await composeSentinelScene({
    bbox,
    start: afterWindow.start,
    end: afterWindow.end,
    maxCloud: params.maxCloud ?? 15,
  })
  if (callbacks.onComposeProgress) {callbacks.onComposeProgress('after', afterReq)}
  const afterDone = await pollCompose(afterReq.jobId, callbacks.signal, (job) => {
    if (callbacks.onComposeProgress) {callbacks.onComposeProgress('after', job)}
  })

  // 3. 差异计算
  const diffReq = await submitSatelliteDiff({
    beforeJobId: beforeDone.jobId,
    afterJobId: afterDone.jobId,
    bbox,
    threshold: params.threshold ?? 30,
    colormap: params.colormap ?? 'inferno',
  })
  const diffDone = await pollSatelliteDiffJob(diffReq.jobId, (job) => {
    if (callbacks.onDiffProgress) {callbacks.onDiffProgress(job)}
  }, { signal: callbacks.signal })

  if (diffDone.status !== 'succeeded') {
    throw new Error(`差异任务未成功：${diffDone.message || diffDone.status}`)
  }

  return {
    beforeJobId: beforeDone.jobId,
    afterJobId: afterDone.jobId,
    diffJobId: diffDone.jobId,
    diff: diffDone.result,
  }
}

/**
 * 把差异热力图 PNG URL 包装成浏览器可加载的 url（兼容 mock:// / file://）。
 */
export function resolveDiffTileUrl(url) {
  return resolveSentinelTileUrl(url)
}

/**
 * UI 用：差异任务状态映射成展示文案。
 */
export function describeDiffJobStatus(job) {
  if (!job) {return { label: '空闲', tone: 'idle', percent: 0 }}
  const percent = Math.max(0, Math.min(100, Math.round(job.progress || 0)))
  switch (job.status) {
    case 'queued':
      return { label: '排队中…', tone: 'pending', percent }
    case 'running':
      return { label: `差异计算中（${percent}%）`, tone: 'running', percent }
    case 'succeeded':
      return { label: '差异计算完成', tone: 'success', percent: 100 }
    case 'failed':
      return { label: job.message || '差异计算失败', tone: 'error', percent }
    case 'cancelled':
      return { label: '已取消', tone: 'idle', percent }
    default:
      return { label: '未知状态', tone: 'idle', percent }
  }
}

/**
 * 把 bbox 内的一个归一化热点坐标（0..1）映射为地图经纬度（用于在地图上画标记）。
 */
export function hotspotToLonLat(hotspot, bbox) {
  if (!bbox || bbox.length !== 4) {return null}
  const [minLon, minLat, maxLon, maxLat] = bbox
  if (typeof hotspot.lon === 'number' && typeof hotspot.lat === 'number') {
    return { lon: hotspot.lon, lat: hotspot.lat }
  }
  const lon = minLon + (hotspot.x + hotspot.width / 2) * (maxLon - minLon)
  const lat = maxLat - (hotspot.y + hotspot.height / 2) * (maxLat - minLat)
  return { lon: Number(lon.toFixed(6)), lat: Number(lat.toFixed(6)) }
}

// 内部辅助

function sanitizeDiffRequest(req) {
  const out = {}
  if (req.beforeJobId) {out.before_job_id = req.beforeJobId}
  if (req.afterJobId) {out.after_job_id = req.afterJobId}
  if (req.beforeUrl) {out.before_url = req.beforeUrl}
  if (req.afterUrl) {out.after_url = req.afterUrl}
  if (req.bbox && Array.isArray(req.bbox) && req.bbox.length === 4) {out.bbox = req.bbox}
  if (Number.isFinite(req.threshold)) {out.threshold = Math.max(0, Math.min(255, req.threshold))}
  if (['inferno', 'viridis', 'red', 'grayscale'].includes(req.colormap)) {
    out.colormap = req.colormap
  }
  if (!out.before_job_id && !out.before_url) {
    throw new Error('必须提供 beforeJobId 或 beforeUrl')
  }
  if (!out.after_job_id && !out.after_url) {
    throw new Error('必须提供 afterJobId 或 afterUrl')
  }
  return out
}

function isTerminalStatus(status) {
  return status === 'succeeded' || status === 'failed' || status === 'cancelled'
}

async function pollCompose(jobId, signal, onProgress) {
  const intervalMs = 1500
  const timeoutMs = 5 * 60 * 1000
  const startedAt = Date.now()
  let job = await fetchSentinelJob(jobId)
  if (onProgress) {onProgress(job)}
  if (isTerminalStatus(job.status)) {return job}
  while (Date.now() - startedAt < timeoutMs) {
    if (signal && signal.aborted) {throw new DOMException('轮询被取消', 'AbortError')}
    await sleep(intervalMs)
    job = await fetchSentinelJob(jobId)
    if (onProgress) {onProgress(job)}
    if (isTerminalStatus(job.status)) {return job}
  }
  throw new Error('Sentinel 合成任务超时')
}

function safeCallback(cb, value) {
  try { cb(value) } catch (_e) { /* swallow */ }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function safeReadText(resp) {
  try { return await resp.text() } catch (_e) { return '' }
}
