/**
 * 运行模式（运行时探测与切换）。
 *
 * 三种状态:
 *   - 'probing' : 启动中,尚未探测后端可达性
 *   - 'backend' : 后端可达,VITE_USE_BACKEND=true 或健康检查通过
 *   - 'mock'    : 后端不可达,降级到本地 mock
 *
 * VITE_USE_BACKEND 是显式开关:
 *   - 'true'  : 强制 backend 模式
 *   - 'false' : 强制 mock 模式
 *   - 未设置  : 自动探测
 *
 * 该模块是唯一可变状态的持有者。所有 *_Service 与组件必须通过
 * isBackendRuntime() / getRuntimeMode() / getUseBackend() 读取当前状态,
 * 不要直接 import { USE_BACKEND } 来判断分支。
 */

import { createApiError } from "./errors.js";

const API_BASE =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8080/api";

/** @type {'probing' | 'backend' | 'mock'} */
let RUNTIME_MODE =
  String(import.meta.env.VITE_USE_BACKEND ?? "").toLowerCase() === "true"
    ? "backend"
    : "probing";

/** @type {boolean} 当前是否走真实后端。 */
let USE_BACKEND =
  String(import.meta.env.VITE_USE_BACKEND ?? "").toLowerCase() === "true";

function setRuntime(mode, useBackend) {
  RUNTIME_MODE = mode;
  USE_BACKEND = useBackend;
}

export function getRuntimeMode() {
  return RUNTIME_MODE === "probing" ? "probing" : RUNTIME_MODE;
}

export function isBackendRuntime() {
  return RUNTIME_MODE === "backend";
}

export function getUseBackend() {
  return USE_BACKEND;
}

/**
 * 探测后端可达性: 显式配置优先,未配置则请求健康检查自动判定。
 * 通常在 App.vue 启动时调用一次,后续业务代码无需重复探测。
 */
export async function initBackendMode() {
  const explicit = String(import.meta.env.VITE_USE_BACKEND ?? "").toLowerCase();
  if (explicit === "true") {
    setRuntime("backend", true);
    return RUNTIME_MODE;
  }
  if (explicit === "false") {
    setRuntime("mock", false);
    return RUNTIME_MODE;
  }
  try {
    const resp = await fetch(`${API_BASE}/system/health`);
    const payload = await resp.json().catch(() => null);
    const ok =
      resp.ok && payload?.code === 0 && payload?.data?.status === "UP";
    setRuntime(ok ? "backend" : "mock", ok);
  } catch {
    setRuntime("mock", false);
  }
  return RUNTIME_MODE;
}

/**
 * 业务代码如需在非 backend 模式下抛错,统一调用此函数,
 * 错误消息保持一致,避免每处重复写文案。
 */
export function requireBackend(feature) {
  if (!USE_BACKEND) {
    throw createApiError(
      `${feature} 需要后端服务。请启动后端或设置 VITE_USE_BACKEND=true 后重试。`,
      "BACKEND_REQUIRED",
    );
  }
}

export { API_BASE };
