/**
 * 时相对比纯函数（无网络 / 无 Pinia 依赖，便于 vitest 直接覆盖）。
 * 由 satelliteCompareApi.js re-export，页面继续从 api 模块导入即可。
 */

/** 任务终态。轮询遇到这些状态即停止。 */
export const TERMINAL_STATUSES = ['succeeded', 'failed', 'cancelled']

/** 把上游响应统一成 { jobId, status, progress, message, result }。 */
export function normalizeJob(payload, fallbackJobId = '') {
  const p = payload || {}
  return {
    jobId: p.jobId || fallbackJobId,
    status: p.status || 'unknown',
    progress: Number(p.progress) || 0,
    message: p.message || '',
    result: p.result || null,
  }
}

/**
 * 诚实降级判定（B1-4）：只有 dataSource === 'real' 且图片 URL 已被网关重写为
 * 同源 /api/ai/sentinel/image/... 时，才允许展示影像与差异热力图。
 * ai-service 在 STAC 场景缺失/下载失败时会用 PIL 生成演示噪声图，
 * 这类结果绝不能被当作真实卫星差异呈现给甲方。
 */
export function isRealImageJob(job) {
  const result = job?.result
  return (
    result?.dataSource === 'real' &&
    typeof result?.s2Url === 'string' &&
    result.s2Url.includes('/api/ai/sentinel/image/')
  )
}

/**
 * 由灾害点经纬度生成 AOI bbox（默认 ±0.02°，约 2~4 km 见方）。
 * 只接受有限数字；无坐标时返回 null，由调用方提示手工输入。
 */
export function buildBbox(point, paddingDeg = 0.02) {
  const lon = Number(point?.longitude ?? point?.lon ?? point?.lng)
  const lat = Number(point?.latitude ?? point?.lat)
  const pad = Number(paddingDeg)
  if (![lon, lat, pad].every(Number.isFinite) || pad <= 0) {
    return null
  }
  if (lon < -180 || lon > 180 || lat < -85 || lat > 85) {
    return null
  }
  return [
    Number((lon - pad).toFixed(6)),
    Number((lat - pad).toFixed(6)),
    Number((lon + pad).toFixed(6)),
    Number((lat + pad).toFixed(6)),
  ]
}

/**
 * 轮询任务直到终态。首次立即拉取，之后按 interval 轮询；
 * 支持 AbortSignal 取消，超时抛中文错误。
 */
export async function pollJob(fetcher, jobId, onProgress, options = {}) {
  const intervalMs = options.intervalMs ?? 1200
  const timeoutMs = options.timeoutMs ?? 3 * 60 * 1000
  const startedAt = Date.now()
  let job = normalizeJob(await fetcher(jobId, options.signal), jobId)
  onProgress?.(job)
  if (TERMINAL_STATUSES.includes(job.status)) {
    return job
  }

  while (Date.now() - startedAt < timeoutMs) {
    if (options.signal?.aborted) {
      throw new DOMException('轮询已取消', 'AbortError')
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
    job = normalizeJob(await fetcher(jobId, options.signal), jobId)
    onProgress?.(job)
    if (TERMINAL_STATUSES.includes(job.status)) {
      return job
    }
  }
  throw new Error(`时相对比任务超时（>${Math.round(timeoutMs / 1000)}s），请稍后重试或缩小时间范围`)
}

/** UI 文案：状态 + 进度。 */
export function describeJob(job) {
  const percent = Math.max(0, Math.min(100, Math.round(job?.progress || 0)))
  switch (job?.status) {
    case 'queued':
      return { label: '排队中…', tone: 'pending', percent }
    case 'running':
      return { label: `处理中 ${percent}%`, tone: 'running', percent }
    case 'succeeded':
      return { label: '已完成', tone: 'success', percent: 100 }
    case 'failed':
      return { label: job?.message || '任务失败', tone: 'error', percent }
    case 'cancelled':
      return { label: '已取消', tone: 'idle', percent }
    default:
      return { label: '等待开始', tone: 'idle', percent: 0 }
  }
}
