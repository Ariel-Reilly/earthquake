/**
 * 多模态融合服务 API 封装
 * 
 * 提供多模态数据融合、贡献度分析、权重配置等功能
 */
import { request, createApiError, USE_BACKEND } from "./dashboardApi";

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
      return request(`/ai/multimodal${path}`, options);
    }
    throw error;
  }
}

/**
 * 多模态融合请求
 * 
 * @param {Object} request - 融合请求
 * @param {string} request.eventId - 事件ID
 * @param {Array<string>} request.modals - 启用的模态列表
 * @param {Object} request.location - 位置 {lng, lat}
 * @param {string} request.timeRange - 时间范围
 * @param {Object} request.satelliteData - 卫星影像数据
 * @param {Object} request.forecastData - 时序预测数据
 * @param {Object} request.socialMediaData - 社交媒体数据
 * @param {Object} request.deviceData - 设备数据
 * @returns {Promise<Object>} 融合结果
 */
export async function fuseModals(request) {
  return aiServiceRequest("/multimodal/fuse", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

/**
 * 获取融合研判结果
 * 
 * @param {string} eventId - 事件ID
 * @returns {Promise<Object>} 融合研判详情
 */
export async function getInsight(eventId) {
  return aiServiceRequest(`/multimodal/insight/${eventId}`);
}

/**
 * 获取贡献度详情
 * 
 * @param {string} eventId - 事件ID
 * @returns {Promise<Object>} 各模态贡献度
 */
export async function getContributions(eventId) {
  return aiServiceRequest(`/multimodal/contributions/${eventId}`);
}

/**
 * 手动刷新融合研判
 * 
 * @param {string} eventId - 事件ID
 * @param {boolean} refresh - 是否强制刷新
 * @returns {Promise<Object>} 刷新结果
 */
export async function refreshInsight(eventId, refresh = true) {
  return aiServiceRequest("/multimodal/refresh", {
    method: "POST",
    body: JSON.stringify({ eventId, refresh }),
  });
}

/**
 * 获取权重配置
 * 
 * @param {string} disasterType - 灾种类型
 * @returns {Promise<Object>} 权重配置
 */
export async function getWeights(disasterType) {
  return aiServiceRequest(`/multimodal/weights/${encodeURIComponent(disasterType)}`);
}

/**
 * 更新权重配置
 * 
 * @param {string} disasterType - 灾种类型
 * @param {Object} weights - 权重配置
 * @returns {Promise<Object>} 更新结果
 */
export async function updateWeights(disasterType, weights) {
  return aiServiceRequest(`/multimodal/weights/${encodeURIComponent(disasterType)}`, {
    method: "PUT",
    body: JSON.stringify({ disasterType, weights }),
  });
}

/**
 * 获取融合研判历史
 * 
 * @param {Object} options - 查询选项
 * @param {string} options.eventId - 事件ID
 * @param {number} options.page - 页码
 * @param {number} options.size - 每页数量
 * @returns {Promise<Object>} 历史记录
 */
export async function getFusionHistory(options = {}) {
  const { eventId, page = 1, size = 20 } = options;
  return aiServiceRequest(`/multimodal/history${withQuery({ eventId, page, size })}`);
}

/**
 * 模态常量定义
 */
export const MODAL_TYPES = {
  SATELLITE: { key: "satellite", label: "卫星影像", color: "#6dbfa5", icon: "Camera" },
  FORECAST: { key: "forecast", label: "时序预测", color: "#4f8f99", icon: "TrendingUp" },
  SOCIAL_MEDIA: { key: "social_media", label: "社交媒体舆情", color: "#805ad5", icon: "MessageSquare" },
  DEVICE: { key: "device", label: "设备数据", color: "#e0a050", icon: "Radio" },
};

/**
 * 风险等级常量
 */
export const RISK_LEVELS = {
  CRITICAL: { label: "红色预警", color: "#e06060", class: "red" },
  HIGH: { label: "橙色预警", color: "#e0a050", class: "orange" },
  MEDIUM: { label: "黄色预警", color: "#d9c040", class: "yellow" },
  LOW: { label: "蓝色预警", color: "#6dbfa5", class: "blue" },
};

/**
 * 获取风险等级信息
 * 
 * @param {string} level - 风险等级
 * @returns {Object} 等级信息
 */
export function getRiskLevelInfo(level) {
  return RISK_LEVELS[level?.toUpperCase()] || RISK_LEVELS.LOW;
}

/**
 * 获取模态信息
 * 
 * @param {string} modalKey - 模态键
 * @returns {Object} 模态信息
 */
export function getModalInfo(modalKey) {
  return MODAL_TYPES[modalKey?.toUpperCase()] || { key: modalKey, label: modalKey, color: "#7a8d89" };
}

/**
 * 融合请求构建器
 * 
 * @param {Object} options - 融合选项
 * @returns {Object} 融合请求体
 */
export function buildFusionRequest(options = {}) {
  const {
    eventId,
    location = null,
    timeRange = "24h",
    modals = ["satellite", "forecast", "social_media", "device"],
    satelliteData = null,
    forecastData = null,
    socialMediaData = null,
    deviceData = null,
  } = options;

  return {
    eventId: eventId || `FUSION-${Date.now()}`,
    modals,
    location,
    timeRange,
    satelliteData,
    forecastData,
    socialMediaData,
    deviceData,
  };
}

/**
 * 模拟融合数据（用于演示和测试）
 * 
 * @param {string} eventId - 事件ID
 * @returns {Object} 模拟融合结果
 */
export function mockFusionResult(eventId) {
  return {
    eventId,
    fusedScore: 87.5,
    level: "HIGH",
    levelColor: "#e0a050",
    confidence: 0.82,
    contributions: {
      satellite: 0.55,
      forecast: 0.25,
      social_media: 0.15,
      device: 0.05,
    },
    explanation: "综合风险评分为 87.5 分，卫星影像贡献最大（55%），影像显示青龙坡区域出现明显地表形变，同时时序预测也提供了 25% 的贡献。建议发布橙色预警。",
    modalDetails: {
      satellite: {
        score: 85,
        desc: "检测到 3 处异常，Sentinel-2 显示显著形变",
        confidence: 0.92,
      },
      forecast: {
        score: 72,
        desc: "预测位移加速趋势（0.85 mm/d²）",
        confidence: 0.78,
      },
      social_media: {
        score: 65,
        desc: "舆情扩散（52 条相关帖文），情感偏负面",
        confidence: 0.65,
      },
      device: {
        score: 60,
        desc: "位移速率偏快（6.2 mm/d）",
        confidence: 0.88,
      },
    },
    createdAt: new Date().toISOString(),
  };
}
