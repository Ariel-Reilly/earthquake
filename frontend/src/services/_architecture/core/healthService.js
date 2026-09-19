/**
 * AI 微服务健康探活服务 (healthService.js)。
 *
 * 功能：
 *   - 每 30 秒 ping 一次 AI 健康端点 (/api/ai/health)
 *   - 记录连续失败/成功次数，自动在"可用 / 降级 / 不可用"三态间转换
 *   - 将状态写入 useRealtimeStore.aiReachable / aiState，UI 可响应式观察
 *   - 支持外部手动触发 check() 用于即时刷新
 *
 * 三态语义：
 *   ok        - 连续成功 ≥ 2 次
 *   degraded  - 最近 3 次内有失败但最近一次成功
 *   down      - 连续失败 ≥ 3 次（前端进入"人工巡检模式"）
 */
import { request } from "./httpClient.js";
import { API_BASE, getUseBackend } from "./runtime.js";
import { useRealtimeStore } from "../../../stores/realtime.js";

let probeTimer = null;
let inFlight = false;
let consecutiveFailures = 0;
let consecutiveSuccesses = 0;
let lastProbeAt = null;
let lastAiLatencyMs = null;
let probeIntervalMs = 30_000;

/**
 * 计算最终状态：综合连续成功/失败计数。
 */
function deriveState() {
  if (consecutiveFailures >= 3) {return "down";}
  if (consecutiveFailures >= 1 && consecutiveSuccesses === 0) {return "degraded";}
  if (consecutiveSuccesses >= 2) {return "ok";}
  return "ok"; // 默认 OK
}

/**
 * 把当前内部状态同步到 Pinia store。
 */
function publish() {
  try {
    const store = useRealtimeStore();
    const state = deriveState();
    store.setAiHealth({
      reachable: state !== "down",
      state,
      consecutiveFailures,
      consecutiveSuccesses,
      lastProbeAt,
      lastAiLatencyMs,
    });
  } catch {
    // Pinia 不可达时静默忽略（早期错误路径）
  }
}

/**
 * 探测 AI 健康端点。返回 boolean。
 *
 * 实现要点：
 *   - 优先走 httpClient.request 复用 JWT 注入（生产模式）
 *   - 演示模式 (USE_BACKEND=false) 直接返回 true，不阻塞 UI
 *   - 任何异常（超时/网络/5xx）一律视为失败
 */
async function probe() {
  if (inFlight) {return;}
  inFlight = true;
  const t0 = Date.now();
  try {
    if (!getUseBackend()) {
      // 演示模式：永远"健康"，避免产生误导性警告
      consecutiveFailures = 0;
      consecutiveSuccesses += 1;
      lastProbeAt = new Date().toISOString();
      lastAiLatencyMs = 0;
      publish();
      return true;
    }
    // 简单 ping：/api/ai/health 由后端 ai-service 模块暴露（Spring Boot 代理到 Python 微服务）
    await request("/ai/health", { method: "GET" });
    consecutiveFailures = 0;
    consecutiveSuccesses += 1;
    lastAiLatencyMs = Date.now() - t0;
    lastProbeAt = new Date().toISOString();
    return true;
  } catch {
    consecutiveSuccesses = 0;
    consecutiveFailures += 1;
    lastAiLatencyMs = Date.now() - t0;
    lastProbeAt = new Date().toISOString();
    return false;
  } finally {
    inFlight = false;
    publish();
  }
}

/**
 * 启动周期探活。多次调用会先停旧定时器。
 *
 * @param {object} options
 * @param {number} [options.interval=30000] 探活间隔（毫秒）
 * @param {boolean} [options.immediate=true] 是否立即执行一次
 */
export function startAiHealthCheck(options = {}) {
  stopAiHealthCheck();
  probeIntervalMs = Number(options.interval ?? 30_000);
  if (options.immediate !== false) {
    probe().catch(() => {});
  }
  probeTimer = window.setInterval(() => {
    probe().catch(() => {});
  }, Math.max(5000, probeIntervalMs));
}

export function stopAiHealthCheck() {
  if (probeTimer) {
    window.clearInterval(probeTimer);
    probeTimer = null;
  }
}

/**
 * 立即执行一次探活并返回结果。给 UI 按钮"重新检查"用。
 */
export async function checkAiHealthNow() {
  const ok = await probe();
  return { ok, state: deriveState(), lastProbeAt };
}

/**
 * 获取当前健康状态快照（不触发探活）。
 */
export function getAiHealthSnapshot() {
  const state = deriveState();
  return {
    state,
    reachable: state !== "down",
    consecutiveFailures,
    consecutiveSuccesses,
    lastProbeAt,
    lastAiLatencyMs,
  };
}

/**
 * AI 健康端点 URL（仅供调试/展示）。
 */
export function getAiHealthUrl() {
  return `${API_BASE}/ai/health`;
}
