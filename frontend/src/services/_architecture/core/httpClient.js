/**
 * HTTP 客户端: 封装 fetch + JWT 注入 + 错误归一化。
 *
 * 用法:
 *   import { request, downloadFile } from '../_architecture/core/httpClient.js';
 *   const data = await request('/hazards');
 *
 * 行为约定:
 *   - 自动从 stores/auth 注入 Authorization header
 *   - 自动解析 JSON 响应 (payload 期望 { code: 0, data, message })
 *   - 401 状态码自动重定向到登录页 (排除 /auth/* 自身)
 *   - 业务错误统一抛出 createApiError, 由 error.code 判定
 *   - 超时不强制(交给浏览器默认)
 */

import { getAuthToken, redirectToLogin } from "../../../stores/auth.js";
import { API_BASE, getUseBackend } from "./runtime.js";
import { createApiError } from "./errors.js";

function isBackendCompatibleToken(token) {
  return typeof token === "string" && token.split(".").length === 3;
}

function authHeader() {
  const token = getAuthToken();
  return isBackendCompatibleToken(token)
    ? { Authorization: `Bearer ${token}` }
    : {};
}

/**
 * 通用 JSON 请求。
 * @param {string} path - 以 / 开头的相对路径 (例: "/hazards")
 * @param {object} options - fetch 选项; body 会被 JSON.stringify
 * @returns {Promise<any>} 后端 payload.data
 */
export async function request(path, options = {}) {
  const { body, headers: extraHeaders, ...rest } = options;
  const headers = {
    "Content-Type": "application/json",
    ...authHeader(),
    ...(extraHeaders ?? {}),
  };
  const init = {
    // 统一禁用浏览器/HTTP 缓存,确保每次进入页面或参数变化时拿到最新数据。
    // 否则用户在各页面间跳转时,会因为 fetch 默认缓存导致"需要手动刷新"。
    cache: "no-store",
    headers,
    credentials: "include",
    ...rest,
  };
  if (body !== undefined) {
    init.body = typeof body === "string" ? body : JSON.stringify(body);
  }
  const response = await fetch(`${API_BASE}${path}`, init);
  const payload = await response.json().catch(() => null);

  if (response.status === 401 && !path.startsWith("/auth/")) {
    redirectToLogin();
    throw createApiError(payload?.message || "登录已过期,请重新登录", "401");
  }

  if (!response.ok || payload?.code !== 0) {
    throw createApiError(
      payload?.message || `请求失败:${response.status}`,
      String(response.status),
    );
  }
  return payload?.data;
}

/**
 * 触发浏览器下载后端生成的二进制文件 (xlsx 等)。
 * 后端导出接口返回附件流 (ExcelExportService), 不能走 request() 的 JSON 解析。
 *
 * @param {string} path - 以 / 开头的相对路径
 * @param {string} fileName - 下载文件名
 */
export async function downloadFile(path, fileName) {
  if (!getUseBackend()) {
    throw createApiError(
      "导出需要后端服务。请启动后端后重试。",
      "BACKEND_REQUIRED",
    );
  }
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: authHeader(),
    credentials: "include",
  });
  if (response.status === 401) {
    redirectToLogin();
    throw createApiError("登录已过期,请重新登录", "401");
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    throw createApiError(
      payload?.message || `导出失败:${response.status}`,
      String(response.status),
    );
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
  return { downloaded: true, fileName };
}
