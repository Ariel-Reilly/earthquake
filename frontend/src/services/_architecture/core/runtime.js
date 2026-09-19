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
 *   - 未设置  : 自动探测（启动时探测一次；之后每 30 秒复检，路由切换时再探测一次）
 *
 * 该模块是唯一可变状态的持有者。所有 *_Service 与组件必须通过
 * isBackendRuntime() / getRuntimeMode() / getUseBackend() 读取当前状态,
 * 不要直接 import { USE_BACKEND } 来判断分支。
 */

import { createApiError } from "./errors.js";

const API_BASE =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8080/api";

const HEALTH_PATH = "/system/health";
const REPROBE_INTERVAL_MS = 30_000; // mock 模式下每 30 秒复检一次

const EXPLICIT_FLAG = String(import.meta.env.VITE_USE_BACKEND ?? "")
  .toLowerCase();

/** @type {'probing' | 'backend' | 'mock'} */
let RUNTIME_MODE =
  EXPLICIT_FLAG === "true"
    ? "backend"
    : EXPLICIT_FLAG === "false"
      ? "mock"
      : "probing";

/** @type {boolean} 当前是否走真实后端。 */
let USE_BACKEND = EXPLICIT_FLAG === "true";

let _reprobeTimer = null;
let _reprobeInFlight = null;
let _listeners = new Set();

function setRuntime(mode, useBackend) {
  const prevMode = RUNTIME_MODE;
  const prevUse = USE_BACKEND;
  RUNTIME_MODE = mode;
  USE_BACKEND = useBackend;
  if (prevMode !== mode || prevUse !== useBackend) {
    // 异步通知，避免在 probe 调用栈里触发同步副作用
    queueMicrotask(() => {
      for (const fn of _listeners) {
        try { fn({ mode, useBackend }); } catch (_) { /* ignore */ }
      }
    });
  }
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

/** 订阅运行时切换（backend ↔ mock）。返回取消订阅函数。 */
export function subscribeRuntime(fn) {
  _listeners.add(fn);
  return () => _listeners.delete(fn);
}

/**
 * 单次探测后端可达性。
 * 返回 { ok, status }，不修改全局状态。
 */
async function probeOnce() {
  try {
    const resp = await fetch(`${API_BASE}${HEALTH_PATH}`, {
      // 8 秒上限，避免卡住首屏
      signal: AbortSignal.timeout(8000),
    });
    const payload = await resp.json().catch(() => null);
    const ok =
      resp.ok && payload?.code === 0 && payload?.data?.status === "UP";
    return { ok, status: resp.status };
  } catch (e) {
    return { ok: false, status: 0, error: e?.message };
  }
}

/**
 * 启动复检定时器：仅在自动探测模式（未显式设置）下生效。
 * mock 状态下每 REPROBE_INTERVAL_MS 探测一次，命中 backend 后自动切换并停止定时器。
 */
function startReprobeTimer() {
  if (_reprobeTimer) {return;}
  if (EXPLICIT_FLAG === "true" || EXPLICIT_FLAG === "false") {return;}
  _reprobeTimer = window.setInterval(async () => {
    if (_reprobeInFlight) {return;}
    _reprobeInFlight = (async () => {
      const r = await probeOnce();
      if (r.ok && RUNTIME_MODE !== "backend") {
        setRuntime("backend", true);
        stopReprobeTimer();
      }
    })().finally(() => {
      _reprobeInFlight = null;
    });
  }, REPROBE_INTERVAL_MS);
}

function stopReprobeTimer() {
  if (_reprobeTimer) {
    window.clearInterval(_reprobeTimer);
    _reprobeTimer = null;
  }
}

/**
 * 探测后端可达性: 显式配置优先,未配置则请求健康检查自动判定。
 * 通常在 App.vue 启动时调用一次；之后由定时器 + recheckBackendMode() 维护。
 */
export async function initBackendMode() {
  if (EXPLICIT_FLAG === "true") {
    setRuntime("backend", true);
    return RUNTIME_MODE;
  }
  if (EXPLICIT_FLAG === "false") {
    setRuntime("mock", false);
    return RUNTIME_MODE;
  }
  const r = await probeOnce();
  if (r.ok) {
    setRuntime("backend", true);
  } else {
    setRuntime("mock", false);
    startReprobeTimer();
  }
  return RUNTIME_MODE;
}

/**
 * 手动触发一次重新探测（例如路由切换时调用）。
 * @returns {Promise<'backend'|'mock'|'probing'>}
 */
export async function recheckBackendMode() {
  if (EXPLICIT_FLAG === "true") {return "backend";}
  if (EXPLICIT_FLAG === "false") {return "mock";}
  const r = await probeOnce();
  if (r.ok) {
    setRuntime("backend", true);
    stopReprobeTimer();
  } else {
    setRuntime("mock", false);
    startReprobeTimer();
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
