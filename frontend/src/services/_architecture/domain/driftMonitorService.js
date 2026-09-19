/**
 * 漂移监控服务 API 封装
 * 
 * 提供模型漂移检测、趋势查询、基线管理、重训触发等功能
 */
import { request, createApiError, USE_BACKEND } from "../../dashboardApi.js";

const AI_SERVICE_BASE = import.meta.env.VITE_AI_SERVICE_URL || "http://localhost:8000/api";

function withQuery(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && `${value}`.trim() !== "")
      {search.set(key, String(value));}
  });
  const suffix = search.toString();
  return suffix ? `?${suffix}` : "";
}

async function aiServiceRequest(path, options = {}) {
  const url = `${AI_SERVICE_BASE}${path}`;
  const defaultOptions = {
    headers: {
      "Content-Type": "application/json",
    },
  };
  const mergedOptions = { ...defaultOptions, ...options };
  
  try {
    const response = await fetch(url, mergedOptions);
    if (!response.ok) {
      throw createApiError(`AI服务请求失败: ${response.status}`, response.status);
    }
    return response.json();
  } catch (error) {
    if (USE_BACKEND) {
      // 通过后端代理调用AI服务
      return request(`/ai/drift${path}`, options);
    }
    throw error;
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
  return aiServiceRequest("/drift/detect", {
    method: "POST",
    body: JSON.stringify(request),
  });
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
  const { feature, days = 30 } = options;
  return aiServiceRequest(`/drift/trend/${modelId}${withQuery({ feature, days })}`);
}

/**
 * 获取特征漂移详情
 * 
 * @param {string} modelId - 模型ID
 * @param {string} feature - 特征名
 * @returns {Promise<Object>} 特征漂移详情
 */
export async function getFeatureDriftDetail(modelId, feature) {
  return aiServiceRequest(`/drift/features/${modelId}?feature=${encodeURIComponent(feature)}`);
}

/**
 * 获取基线数据
 * 
 * @param {string} modelId - 模型ID
 * @returns {Promise<Object>} 基线数据信息
 */
export async function getBaseline(modelId) {
  return aiServiceRequest(`/drift/baseline/${modelId}`);
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
  return aiServiceRequest("/drift/baseline", {
    method: "POST",
    body: JSON.stringify(request),
  });
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
  return aiServiceRequest("/drift/retrain", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

/**
 * 获取重训状态
 * 
 * @param {string} jobId - 重训任务ID
 * @returns {Promise<Object>} 重训状态信息
 */
export async function getRetrainStatus(jobId) {
  return aiServiceRequest(`/drift/retrain/${jobId}`);
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
    ]);
    
    return {
      modelId,
      overallStatus: driftResult.overallStatus,
      recommendation: driftResult.recommendation,
      recentTrend: trendResult.trend?.slice(-7) || [],
      features: driftResult.features,
      lastChecked: driftResult.detectedAt,
    };
  } catch (_error) {
    return {
      modelId,
      overallStatus: "UNKNOWN",
      recommendation: "无法获取健康度信息",
      recentTrend: [],
      features: {},
      lastChecked: new Date().toISOString(),
    };
  }
}

/**
 * 漂移告警级别常量
 */
export const DRIFT_LEVELS = {
  NORMAL: { label: "正常", color: "#38a169", class: "teal" },
  SLIGHT: { label: "轻微漂移", color: "#d18f2f", class: "yellow" },
  SIGNIFICANT: { label: "显著漂移", color: "#e53e3e", class: "red" },
  UNKNOWN: { label: "未知", color: "#7a8d89", class: "gray" },
};

/**
 * 获取漂移级别信息
 * 
 * @param {string} level - 漂移级别
 * @returns {Object} 级别信息
 */
export function getDriftLevelInfo(level) {
  return DRIFT_LEVELS[level?.toUpperCase()] || DRIFT_LEVELS.UNKNOWN;
}
