/**
 * 漂移监控服务 API 封装
 *
 * 提供模型漂移检测、趋势查询、基线管理、重训触发等功能
 */
import { request, createApiError, USE_BACKEND } from '../../dashboardApi.js'
import { withQuery } from '../../../utils/query.js'

// ai-service 监听 8001（与 sentinelService / aiApi 一致）。原先误写 8000，
// 且只认单一环境变量名 VITE_AI_SERVICE_URL，与其余服务不一致，会导致
// 漂移监控在生产环境连不上 AI 服务。
const AI_SERVICE_BASE =
  import.meta.env.VITE_AI_SERVICE_BASE ||
  import.meta.env.VITE_AI_SERVICE_BASE_URL ||
  import.meta.env.VITE_AI_SERVICE_URL ||
  'http://localhost:8001/api/ai'

/**
 * 后端代理回退路径（导出以便单测锁定，避免再拼出 /api/ai/drift/drift/*）。
 * path 自带 /drift/... 前缀，API_BASE 是 /api，故正确结果为 /ai/drift/...。
 */
export function proxyPathFor(path) {
  return `/ai${path}`
}

async function aiServiceRequest(path, options = {}) {
  const url = `${AI_SERVICE_BASE}${path}`
  const defaultOptions = {
    headers: {
      'Content-Type': 'application/json',
    },
  }
  const mergedOptions = { ...defaultOptions, ...options }

  try {
    const response = await fetch(url, mergedOptions)
    if (!response.ok) {
      throw createApiError(`AI服务请求失败: ${response.status}`, response.status)
    }
    return response.json()
  } catch (error) {
    if (USE_BACKEND) {
      // 回退路径：path 本身已含 /drift/...，后端 API_BASE 是 /api，
      // 所以正确拼法为 `/ai${path}` → /api/ai/drift/detect。
      // 历史缺陷写成 `/ai/drift${path}`，实际请求 /api/ai/drift/drift/detect，
      // 只会命中 501 兜底或 404，页面会把能力缺失误判成「检测到 0 漂移」。
      try {
        return await request(proxyPathFor(path), options)
      } catch (proxyError) {
        if (proxyError?.code === '501' || proxyError?.code === '404') {
          // 后端 AiDeprecatedGapController 对 /api/ai/drift/** 显式返回 501（L-01）。
          // 转成语义化错误，让页面显示「能力未接入」而不是 0 值或泛化网络错误。
          throw createApiError(
            '漂移监控能力未接入：后端代理端点已弃用（501）。请直连 ai-service /api/ai/drift/*，或按 KNOWN_LIMITATIONS L-01 视为二期能力。',
            'DRIFT_NOT_IMPLEMENTED'
          )
        }
        throw proxyError
      }
    }
    throw error
  }
}

/**
 * 检测数据漂移
 *
 * @param {Object} request - 检测请求
 * @param {string} request.modelId - 模型ID
 * @param {Array<Object>} request.currentData - 当前数据样本
 * @returns {Promise<Object>} 漂移检测结果
 */
export async function detectDrift(request) {
  return aiServiceRequest('/drift/detect', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * 获取漂移趋势
 *
 * @param {string} modelId - 模型ID
 * @param {Object} options - 查询选项
 * @param {string} options.feature - 可选，指定特征
 * @param {number} options.days - 趋势天数，默认30
 * @returns {Promise<Object>} 漂移趋势数据
 */
export async function getDriftTrend(modelId, options = {}) {
  const { feature, days = 30 } = options
  return aiServiceRequest(`/drift/trend/${modelId}${withQuery({ feature, days })}`)
}

/**
 * 获取特征漂移详情
 *
 * @param {string} modelId - 模型ID
 * @param {string} feature - 特征名
 * @returns {Promise<Object>} 特征漂移详情
 */
export async function getFeatureDriftDetail(modelId, feature) {
  return aiServiceRequest(`/drift/features/${modelId}?feature=${encodeURIComponent(feature)}`)
}

/**
 * 获取基线数据
 *
 * @param {string} modelId - 模型ID
 * @returns {Promise<Object>} 基线数据信息
 */
export async function getBaseline(modelId) {
  return aiServiceRequest(`/drift/baseline/${modelId}`)
}

/**
 * 更新基线数据
 *
 * @param {Object} request - 基线更新请求
 * @param {string} request.modelId - 模型ID
 * @param {Object} request.data - 新基线数据
 * @param {string} request.description - 描述信息
 * @returns {Promise<Object>} 更新结果
 */
export async function updateBaseline(request) {
  return aiServiceRequest('/drift/baseline', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * 触发模型重训
 *
 * @param {Object} request - 重训请求
 * @param {string} request.modelId - 模型ID
 * @param {string} request.reason - 重训原因
 * @param {string} request.dataset - 可选，数据集标识
 * @returns {Promise<Object>} 重训任务信息
 */
export async function triggerRetrain(request) {
  return aiServiceRequest('/drift/retrain', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * 获取重训状态
 *
 * @param {string} jobId - 重训任务ID
 * @returns {Promise<Object>} 重训状态信息
 */
export async function getRetrainStatus(jobId) {
  return aiServiceRequest(`/drift/retrain/${jobId}`)
}

/**
 * 获取模型健康度摘要
 *
 * @param {string} modelId - 模型ID
 * @returns {Promise<Object>} 健康度摘要
 */
export async function getModelHealthSummary(modelId) {
  try {
    const [driftResult, trendResult] = await Promise.all([
      detectDrift({ modelId, currentData: [] }),
      getDriftTrend(modelId, { days: 7 }),
    ])

    return {
      modelId,
      overallStatus: driftResult.overallStatus,
      recommendation: driftResult.recommendation,
      recentTrend: trendResult.trend?.slice(-7) || [],
      features: driftResult.features,
      lastChecked: driftResult.detectedAt,
    }
  } catch (_error) {
    return {
      modelId,
      overallStatus: 'UNKNOWN',
      recommendation: '无法获取健康度信息',
      recentTrend: [],
      features: {},
      lastChecked: new Date().toISOString(),
    }
  }
}

/**
 * 漂移告警级别常量
 */
export const DRIFT_LEVELS = {
  NORMAL: { label: '正常', color: '#38a169', class: 'teal' },
  SLIGHT: { label: '轻微漂移', color: '#d18f2f', class: 'yellow' },
  SIGNIFICANT: { label: '显著漂移', color: '#e53e3e', class: 'red' },
  UNKNOWN: { label: '未知', color: '#8a968d', class: 'gray' },
}

/**
 * 获取漂移级别信息
 *
 * @param {string} level - 漂移级别
 * @returns {Object} 级别信息
 */
export function getDriftLevelInfo(level) {
  return DRIFT_LEVELS[level?.toUpperCase()] || DRIFT_LEVELS.UNKNOWN
}
