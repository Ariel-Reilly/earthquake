/**
 * 时相对比（灾前/灾后）API 客户端 — B1（2026-11-13）。
 *
 * 统一走 Java 网关 /api/ai/sentinel/**（带 JWT + satellite:view 权限），
 * 不直连 ai-service 8001：生产端口不可达、上游无鉴权、合成图 file:// 不可读。
 * 上游字段是 snake_case（Pydantic 模型），这里不做转换，调用方按契约传参。
 * 纯函数（bbox / 降级判定 / 轮询）拆在 satelliteCompareUtils.js，便于单测直接覆盖。
 */
import { getAuthToken, redirectToLogin } from "../stores/auth.js";

export {
  TERMINAL_STATUSES,
  normalizeJob,
  isRealImageJob,
  buildBbox,
  pollJob,
  describeJob,
} from "./satelliteCompareUtils.js";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080/api";
const SENTINEL_BASE = `${API_BASE.replace(/\/$/, "")}/ai/sentinel`;

/** 带 JWT 的统一请求；401 跳登录，其余错误抛出可读中文 message。 */
async function request(path, options = {}) {
  const token = getAuthToken();
  const headers = {
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(options.headers || {}),
  };
  const resp = await fetch(`${SENTINEL_BASE}${path}`, { ...options, headers });
  if (resp.status === 401) {
    redirectToLogin();
    throw new Error("登录已过期，请重新登录");
  }
  const payload = await resp.json().catch(() => null);
  if (!resp.ok) {
    const msg = payload?.message || `时相对比服务返回 HTTP ${resp.status}`;
    const code = payload?.code ? `[${payload.code}] ` : "";
    throw new Error(`${code}${msg}`);
  }
  return payload;
}

/** 提交一期 Sentinel 合成任务（provider 默认 auto：真实源优先，失败由上游标注 mock）。 */
export function submitCompose(params) {
  return request("/compose", { method: "POST", body: JSON.stringify(params) });
}

/** 查询合成任务。 */
export function fetchComposeJob(jobId, signal) {
  return request(`/jobs/${encodeURIComponent(jobId)}`, { signal });
}

/** 取消合成任务（失败不影响主流程）。 */
export async function cancelComposeJob(jobId) {
  try {
    await request(`/jobs/${encodeURIComponent(jobId)}/cancel`, { method: "POST", body: "{}" });
    return true;
  } catch (_e) {
    return false;
  }
}

/** 提交灾前/灾后差异计算任务。 */
export function submitDiff(params) {
  return request("/diff", { method: "POST", body: JSON.stringify(params) });
}

/** 查询差异任务。 */
export function fetchDiffJob(jobId, signal) {
  return request(`/diff-jobs/${encodeURIComponent(jobId)}`, { signal });
}

/**
 * 取网关代理的合成图并转成 object URL。
 * 不能用 <img :src> 直接请求：浏览器不会自动带 Authorization 头，会被 401 拦截。
 * 调用方负责在替换/卸载时 URL.revokeObjectURL()。
 */
export async function fetchImageObjectUrl(url, signal) {
  if (!url || !url.startsWith("/api/ai/sentinel/image/")) {
    throw new Error("图片地址不是受信任的网关代理地址");
  }
  const token = getAuthToken();
  const resp = await fetch(url, {
    signal,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!resp.ok) {
    throw new Error(`影像加载失败（HTTP ${resp.status}）`);
  }
  const blob = await resp.blob();
  return URL.createObjectURL(blob);
}
