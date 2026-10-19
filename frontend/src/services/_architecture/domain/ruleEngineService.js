/**
 * 规则引擎服务 API 封装
 */
import { request, createApiError, USE_BACKEND } from "../../dashboardApi.js";
import { withQuery } from "../../../utils/query.js";

async function apiRequest(path, options = {}) {
  if (!USE_BACKEND) {
    throw createApiError("需要后端服务", "BACKEND_REQUIRED");
  }
  return request(path, options);
}

/**
 * 获取规则列表
 */
export async function getRules(filters = {}) {
  const { disasterType, level, enabled, page = 1, size = 20 } = filters;
  return apiRequest(`/rules${withQuery({ disasterType, level, enabled, page, size })}`);
}

/**
 * 获取规则详情
 */
export async function getRule(id) {
  return apiRequest(`/rules/${id}`);
}

export async function createRule(rule) {
  return apiRequest("/rules", {
    method: "POST",
    body: JSON.stringify(rule),
  });
}

export async function updateRule(id, rule) {
  return apiRequest(`/rules/${id}`, {
    method: "PUT",
    body: JSON.stringify(rule),
  });
}

export async function deleteRule(id) {
  return apiRequest(`/rules/${id}`, {
    method: "DELETE",
  });
}

/**
 * 启用/停用规则
 */
export async function toggleRule(id) {
  return apiRequest(`/rules/${id}/toggle`, {
    method: "PUT",
  });
}

export async function testRule(request) {
  return apiRequest("/rules/test", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

/**
 * 获取执行日志
 */
export async function getRuleLogs(ruleId, page = 1, size = 20) {
  return apiRequest(`/rules/${ruleId}/logs${withQuery({ page, size })}`);
}

/**
 * 获取所有执行日志
 */
export async function getAllRuleLogs(page = 1, size = 20) {
  return apiRequest(`/rules/logs${withQuery({ page, size })}`);
}

export async function getFieldDicts() {
  return apiRequest("/rules/fields");
}

/**
 * 可用操作符
 */
export async function getOperators() {
  return apiRequest("/rules/operators");
}

/**
 * 刷新规则缓存
 */
export async function reloadRules() {
  return apiRequest("/rules/reload", {
    method: "POST",
  });
}

export async function rollbackRule(id, version) {
  return apiRequest(`/rules/${id}/rollback?version=${version}`, {
    method: "POST",
  });
}
