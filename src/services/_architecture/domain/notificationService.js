/**
 * 通知服务 API 封装
 */
import { request, createApiError, USE_BACKEND } from "../../dashboardApi.js";

function withQuery(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && `${value}`.trim() !== "")
      {search.set(key, String(value));}
  });
  const suffix = search.toString();
  return suffix ? `?${suffix}` : "";
}

async function apiRequest(path, options = {}) {
  if (!USE_BACKEND) {
    throw createApiError("需要后端服务", "BACKEND_REQUIRED");
  }
  return request(path, options);
}

/**
 * 发送通知
 * @param {Object} payload - 通知请求体
 */
export async function sendNotification(payload) {
  return apiRequest("/notification/send", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

/**
 * 查询发送记录
 * @param {Object} filters - 过滤条件
 */
export async function getNotificationRecords(filters = {}) {
  const { bizType, disasterType, level, status, page = 1, size = 10 } = filters;
  return apiRequest(`/notification/records${withQuery({ bizType, disasterType, level, status, page, size })}`);
}

/**
 * 查询回执详情
 * @param {number} recordId - 记录 ID
 */
export async function getNotificationReceipts(recordId) {
  return apiRequest(`/notification/receipts/${recordId}`);
}

/**
 * 测试发送
 * @param {Object} request - 测试请求
 */
export async function testSend(request) {
  return apiRequest("/notification/test", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

/**
 * 获取渠道配置
 * @param {string} disasterType - 灾种
 * @param {string} level - 级别
 */
export async function getChannelConfigs(disasterType, level) {
  return apiRequest(`/notification/channel-config${withQuery({ disasterType, level })}`);
}

/**
 * 更新渠道配置
 * @param {Object} config - 渠道配置
 */
export async function updateChannelConfig(config) {
  return apiRequest("/notification/channel-config", {
    method: "PUT",
    body: JSON.stringify(config),
  });
}

/**
 * 失败重发
 * @param {number} recordId - 记录 ID
 */
export async function retryNotification(recordId) {
  return apiRequest(`/notification/retry/${recordId}`, {
    method: "POST",
  });
}
