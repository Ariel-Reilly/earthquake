const API_BASE =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8080/api";
const latency = 240;

import { getAuthToken, redirectToLogin } from "../stores/auth";
import * as mock from "./mock";

/**
 * 运行模式：
 * - 'backend'：真实后端（健康检查通过或 VITE_USE_BACKEND=true 显式指定）
 * - 'mock'   ：本地演示数据（后端不可达时自动降级，UI 明确标注，不再静默假装）
 * - 'probing'：探测中
 */
let RUNTIME_MODE =
  String(import.meta.env.VITE_USE_BACKEND ?? "").toLowerCase() === "true"
    ? "backend"
    : "probing";
let USE_BACKEND =
  String(import.meta.env.VITE_USE_BACKEND ?? "").toLowerCase() === "true";

export { USE_BACKEND };

export function getRuntimeMode() {
  return RUNTIME_MODE === "probing" ? "probing" : RUNTIME_MODE;
}

export function isBackendRuntime() {
  return RUNTIME_MODE === "backend";
}

/** 探测后端可用性：显式配置优先，未配置则请求健康检查自动判定 */
export async function initBackendMode() {
  const explicit = String(import.meta.env.VITE_USE_BACKEND ?? "").toLowerCase();
  if (explicit === "true") {
    RUNTIME_MODE = "backend";
    USE_BACKEND = true;
    return RUNTIME_MODE;
  }
  if (explicit === "false") {
    RUNTIME_MODE = "mock";
    USE_BACKEND = false;
    return RUNTIME_MODE;
  }
  try {
    const resp = await fetch(`${API_BASE}/system/health`);
    const payload = await resp.json().catch(() => null);
    RUNTIME_MODE =
      resp.ok && payload?.code === 0 && payload?.data?.status === "UP"
        ? "backend"
        : "mock";
    USE_BACKEND = RUNTIME_MODE === "backend";
  } catch {
    RUNTIME_MODE = "mock";
    USE_BACKEND = false;
  }
  return RUNTIME_MODE;
}

function clone(data) {
  return typeof structuredClone === "function"
    ? structuredClone(data)
    : JSON.parse(JSON.stringify(data));
}

export function createApiError(message, code = "API_ERROR") {
  const error = new Error(message);
  error.code = code;
  return error;
}

function redirectToLoginLocal() {
  // 委托给 auth.js 的 redirectToLogin（已内置并发锁防重复）
  redirectToLogin();
}

function isBackendCompatibleToken(token) {
  return typeof token === "string" && token.split(".").length === 3;
}

function requireBackend(feature) {
  if (!USE_BACKEND) {
    throw createApiError(
      `${feature} 需要后端服务。请启动后端或设置 VITE_USE_BACKEND=true 后重试。`,
      "BACKEND_REQUIRED",
    );
  }
}

export async function request(path, options = {}) {
  const token = getAuthToken();
  const headers = {
    "Content-Type": "application/json",
    ...(isBackendCompatibleToken(token)
      ? { Authorization: `Bearer ${token}` }
      : {}),
    ...(options.headers ?? {}),
  };
  const response = await fetch(`${API_BASE}${path}`, {
    headers,
    credentials: "include",
    ...options,
  });
  const payload = await response.json().catch(() => null);

  if (response.status === 401 && !path.startsWith("/auth/")) {
    redirectToLoginLocal();
    throw createApiError(payload?.message || "登录已过期，请重新登录", "401");
  }

  if (!response.ok || payload?.code !== 0) {
    throw createApiError(
      payload?.message || `请求失败：${response.status}`,
      String(response.status),
    );
  }
  return payload.data;
}

/**
 * 触发浏览器下载后端生成的二进制文件（xlsx 等）。
 * 后端导出接口返回附件流（ExcelExportService），不能走 request() 的 JSON 解析。
 */
async function downloadFile(path, fileName) {
  if (!USE_BACKEND) {
    throw createApiError("导出需要后端服务。请启动后端后重试。", "BACKEND_REQUIRED");
  }
  const token = getAuthToken();
  const headers = isBackendCompatibleToken(token)
    ? { Authorization: `Bearer ${token}` }
    : {};
  const response = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers,
    credentials: "include",
  });
  if (response.status === 401) {
    redirectToLoginLocal();
    throw createApiError("登录已过期，请重新登录", "401");
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    throw createApiError(payload?.message || `导出失败：${response.status}`, String(response.status));
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

export function response(data) {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(clone(data)), latency);
  });
}

function normalizePaged(payload, fallback = []) {
  if (!payload)
    {return {
      records: fallback,
      total: fallback.length,
      page: 1,
      size: fallback.length,
      pages: 1,
    };}
  if (Array.isArray(payload.records)) {return payload;}
  return {
    records: fallback,
    total: fallback.length,
    page: 1,
    size: fallback.length,
    pages: 1,
  };
}

function withQuery(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && `${value}`.trim() !== "")
      {search.set(key, String(value));}
  });
  const suffix = search.toString();
  return suffix ? `?${suffix}` : "";
}

/**
 * 演示数据回退控制：
 * - USE_BACKEND=false 时返回真实 seedState 数据（开发/演示模式）
 * - USE_BACKEND=true 时返回空数组，防止 API 返回 null 时误用 mock 数据
 * 数据源已迁出到 ./mock/seed.js。
 */
function fallback(key) {
  if (USE_BACKEND) {return [];}
  return mock.seed.SEED_BASE[key] ?? [];
}

const seedState = mock.seed.SEED_BASE;
const SEED_ATTACHMENTS = mock.seed.SEED_ATTACHMENTS;

export async function loginRequest(payload) {
  if (USE_BACKEND) {
    const data = await request("/auth/login", {
      method: "POST",
      body: JSON.stringify(payload),
    });
    return { token: data.token, user: data.user };
  }

  const username = String(payload?.username ?? "").trim();
  const password = String(payload?.password ?? "").trim();
  const role = String(payload?.role ?? "admin");

  if (!username || !password) {
    throw createApiError("请输入账号和密码");
  }

  return response(mock.empty.loginMockUser(username, role));
}

export async function logoutRequest() {
  if (USE_BACKEND) {
    return request("/auth/logout", { method: "POST" }).catch(() => null);
  }
  return response(mock.empty.logoutOk);
}

export const dashboardApi = {
  getOverview: async () => {
    if (!USE_BACKEND) {requireBackend("加载总览数据");}
    return request("/dashboard/overview");
  },
  getHazards: async (params = {}) => {
    if (!USE_BACKEND) {requireBackend("加载灾害点列表");}
    const data = await request(`/hazards${withQuery(params)}`);
    const normalized = normalizePaged(data, fallback("hazardPoints"));
    return {
      list: normalized.records,
      total: normalized.total,
      page: normalized.page,
      size: normalized.size,
      pages: normalized.pages,
    };
  },

  /**
   * v1.6.0：AI 实时监测专用接口 —— 严格按 discovered_at DESC 排序，最新发现的点排第一。
   * 不分页（最多 50 条）。前端"AI 实时监测"面板秒级轮询使用。
   */
  getAiMonitorHazards: async () => {
    if (!USE_BACKEND) {requireBackend("加载 AI 实时监测灾害");}
    const data = await request("/hazards/ai-monitor");
    return Array.isArray(data) ? data : (data.list ?? data.records ?? []);
  },
  getHazardsByViewport: async ({
    bbox,
    zoom,
    level,
    keyword,
    regionCode,
    type,
  } = {}) => {
    const params = { bbox, zoom, level, keyword, regionCode, type };
    if (!USE_BACKEND) {requireBackend("加载灾害点视口数据");}
    const data = await request(`/hazards/viewport${withQuery(params)}`);
    return Array.isArray(data)
      ? data
      : normalizePaged(data, fallback("hazardPoints")).records;
  },
  getHazardDetail: async (id) => {
    if (!USE_BACKEND) {requireBackend("查看灾害点详情");}
    return request(`/hazards/${id}`);
  },
  createHazard: async (payload) => {
    requireBackend("新增灾害点");
    return request("/hazards", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateHazard: async (id, payload) => {
    requireBackend("更新灾害点");
    return request(`/hazards/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteHazard: async (id) => {
    requireBackend("删除灾害点");
    return request(`/hazards/${id}`, { method: "DELETE" });
  },
  getTasks: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/tasks${withQuery(params)}`);
      const normalized = normalizePaged(data, fallback("tasks"));
      return {
        list: normalized.records,
        total: normalized.total,
        page: normalized.page,
        size: normalized.size,
        pages: normalized.pages,
      };
    }
    return response({
      list: seedState.tasks,
      total: seedState.tasks.length,
      page: 1,
      size: seedState.tasks.length,
      pages: 1,
    });
  },
  getTaskDetail: async (id) => {
    if (USE_BACKEND) {return request(`/tasks/${id}`);}
    const item = mock.seed.findTaskById(id) ?? mock.seed.SEED_BASE.tasks[0];
    return response({
      item,
      history: mock.seed.historyOfTask(item.id),
      attachments: SEED_ATTACHMENTS.task,
      linked: mock.seed.linkedOfTask(item.id),
    });
  },
  createTask: async (payload) => {
    requireBackend("创建巡检任务");
    return request("/tasks", { method: "POST", body: JSON.stringify(payload) });
  },
  updateTask: async (id, payload) => {
    requireBackend("更新巡检任务");
    return request(`/tasks/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  updateTaskStatus: async (id, status) => {
    requireBackend("更新巡检任务状态");
    return request(`/tasks/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status }),
    });
  },
  deleteTask: async (id) => {
    requireBackend("删除巡检任务");
    return request(`/tasks/${id}`, { method: "DELETE" });
  },
  getDevices: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/devices${withQuery(params)}`);
      const normalized = normalizePaged(data, fallback("devices"));
      return {
        list: normalized.records,
        total: normalized.total,
        page: normalized.page,
        size: normalized.size,
        pages: normalized.pages,
      };
    }
    return response({
      list: seedState.devices,
      total: seedState.devices.length,
      page: 1,
      size: seedState.devices.length,
      pages: 1,
    });
  },
  getDevicesByViewport: async ({ bbox, zoom, state, keyword } = {}) => {
    const params = { bbox, zoom, state, keyword };
    if (USE_BACKEND) {
      const data = await request(`/devices/viewport${withQuery(params)}`);
      return Array.isArray(data)
        ? data
        : normalizePaged(data, fallback("devices")).records;
    }
    return response(
      seedState.devices.map((item) => ({ ...item, lon: 107.21, lat: 30.61 })),
    );
  },
  getDeviceDetail: async (id) => {
    if (USE_BACKEND) {return request(`/devices/${id}`);}
    const item = mock.seed.findDeviceById(id) ?? mock.seed.SEED_BASE.devices[0];
    return response({
      item,
      history: mock.seed.historyOfDevice(item.id),
      attachments: SEED_ATTACHMENTS.device,
      linked: mock.seed.linkedOfDevice(item.id),
    });
  },
  updateDevice: async (id, payload) => {
    requireBackend("更新设备");
    return request(`/devices/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteDevice: async (id) => {
    requireBackend("删除设备");
    return request(`/devices/${id}`, { method: "DELETE" });
  },
  diagnoseDevice: async (id) => {
    requireBackend("设备诊断");
    return request(`/devices/${id}/diagnose`, { method: "POST" });
  },
  getWarnings: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/warnings${withQuery(params)}`);
      const normalized = normalizePaged(data, fallback("warnings"));
      return {
        list: normalized.records,
        total: normalized.total,
        page: normalized.page,
        size: normalized.size,
        pages: normalized.pages,
      };
    }
    return response({
      list: seedState.warnings,
      total: seedState.warnings.length,
      page: 1,
      size: seedState.warnings.length,
      pages: 1,
    });
  },
  getWarningsPreview: async () => {
    // 预警核心数据高频拉取：只取活跃预警，轻量返回
    if (USE_BACKEND) {
      const data = await request(`/warnings/preview`);
      return Array.isArray(data)
        ? { list: data }
        : normalizePaged(data, fallback("warnings"));
    }
    return response({
      list: seedState.warnings.filter(
        (warning) => !["已关闭", "已忽略", "已解除"].includes(warning.status),
      ),
    });
  },
  getWarningsByViewport: async ({ bbox, zoom, keyword, status } = {}) => {
    const params = { bbox, zoom, keyword, status };
    if (USE_BACKEND) {
      const data = await request(`/warnings/viewport${withQuery(params)}`);
      return Array.isArray(data)
        ? data
        : normalizePaged(data, fallback("warnings")).records;
    }
    return response(seedState.warnings);
  },
  getWarningDetail: async (id) => {
    if (USE_BACKEND) {return request(`/warnings/${id}`);}
    const item = mock.seed.findWarningById(id) ?? mock.seed.SEED_BASE.warnings[0];
    return response({
      item,
      history: mock.seed.historyOfWarning(item.id),
      attachments: SEED_ATTACHMENTS.warning,
      linked: mock.seed.linkedOfWarning(item.id),
    });
  },
  updateWarning: async (id, payload) => {
    requireBackend("更新预警");
    return request(`/warnings/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  confirmWarning: async (id) => {
    requireBackend("确认预警");
    return request(`/warnings/${id}/confirm`, { method: "POST" });
  },
  dispatchWarning: async (id) => {
    requireBackend("派发预警");
    return request(`/warnings/${id}/dispatch`, { method: "POST" });
  },
  closeWarning: async (id, payload = {}) => {
    requireBackend("关闭预警");
    const reason = payload.reason ?? "处置完成";
    return request(`/warnings/${id}/close`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },
  dismissWarning: async (id) => {
    requireBackend("忽略预警");
    return request(`/warnings/${id}/dismiss`, { method: "POST" });
  },
  resolveWarning: async (id) => {
    requireBackend("自动解除预警");
    return request(`/warnings/${id}/resolve`, { method: "POST" });
  },
  getEmergency: async () => {
    if (USE_BACKEND) {
      const data = await request("/emergency?size=200");
      const records = Array.isArray(data?.records)
        ? data.records
        : Array.isArray(data?.list)
          ? data.list
          : [];
      const incidents = records.map((e) => ({
        id: e.id,
        title: e.title,
        level: e.status === "已闭环" ? "已闭环" : "应急响应",
        owner: e.commander,
        step: e.stepName,
        warningId: e.warningCode,
        hazardCode: e.hazardCode,
        time: e.startedAt
          ? new Date(e.startedAt).toLocaleString("zh-CN", { hour12: false })
          : "",
        resultSummary: e.resultSummary,
      }));
      return { incidents };
    }
    return response({ incidents: mock.seed.SEED_BASE.incidents });
  },
  getPersonnel: async () => {
    // 生产模式：值守人员实时位置由移动端上报后端，此处仅透传（点 1、2）
    if (USE_BACKEND) {
      const data = await request(`/personnel/positions`);
      return Array.isArray(data) ? { list: data } : normalizePaged(data, []);
    }
    return response({ list: mock.empty.emptyList() });
  },
  closeIncident: async (id) => {
    requireBackend("关闭应急事件");
    // 闭环语义：POST /emergency/{id}/close 保留事件记录置为"已闭环"，
    // 不是 DELETE 物理删除
    return request(`/emergency/${id}/close`, {
      method: "POST",
      body: JSON.stringify({ summary: "处置完成，事件闭环" }),
    });
  },
  exportOverviewReport: async () => {
    return downloadFile("/dashboard/overview/export", "总览报表.xlsx");
  },
  exportHazardReport: async () => {
    return downloadFile("/hazards/export", "灾害点导出.xlsx");
  },
  exportTaskReport: async () => {
    return downloadFile("/tasks/export", "巡检任务导出.xlsx");
  },
  exportDeviceReport: async () => {
    return downloadFile("/devices/export", "设备状态导出.xlsx");
  },
  exportWarningReport: async () => {
    return downloadFile("/warnings/export", "预警处置导出.xlsx");
  },
  exportEmergencyReport: async () => {
    return downloadFile("/emergency/export", "应急处置导出.xlsx");
  },
  getAnalytics: async () => {
    if (USE_BACKEND) {return request("/dashboard/analytics");}
    return response(mock.analytics.getAnalyticsMock());
  },
  getModuleMeta: async () =>
    USE_BACKEND
      ? request("/dashboard/summary")
      : response(mock.analytics.moduleMetaMock),
  uploadAttachment: async ({ businessType, businessCode, file, uploader }) => {
    if (!USE_BACKEND) {
      return response(mock.empty.uploadReceipt({ businessType, businessCode, file, uploader }));
    }
    const formData = new FormData();
    formData.append("businessType", businessType);
    formData.append("businessCode", businessCode);
    formData.append("file", file);
    const responseData = await fetch(`${API_BASE}/attachments`, {
      method: "POST",
      credentials: "include",
      headers: { "X-User": uploader },
      body: formData,
    });
    const payload = await responseData.json().catch(() => null);
    if (!responseData.ok || payload?.code !== 0)
      {throw createApiError(payload?.message || "上传失败");}
    return payload.data;
  },
  getAttachments: async ({ businessType, businessCode }) => {
    if (USE_BACKEND)
      {return request(
        `/attachments?businessType=${encodeURIComponent(businessType)}&businessCode=${encodeURIComponent(businessCode)}`,
      );}
    return response(mock.empty.emptyAttachments);
  },
  /** 刷新附件 presigned URL（检测到 403/410 时调用）。返回新 URL 字符串。 */
  refreshAttachmentUrl: async (attachmentId) => {
    if (USE_BACKEND) {
      const data = await request(`/attachments/${attachmentId}/refresh-url`, {
        method: "POST",
      });
      return data?.url;
    }
    return null;
  },
  gpsCheck: async ({
    taskCode,
    hazardCode,
    lon,
    lat,
    allowedRadius,
    inspector,
  }) => {
    if (USE_BACKEND) {
      return request("/inspection/gps-check", {
        method: "POST",
        body: JSON.stringify({
          taskCode,
          hazardCode,
          lon,
          lat,
          allowedRadius,
          inspector,
        }),
      });
    }
    return response(
      mock.empty.gpsCheckFallback({ taskCode, hazardCode, lon, lat, allowedRadius }),
    );
  },
  submitInspection: async (payload) => {
    if (USE_BACKEND) {
      return request("/inspection/submit", {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.empty.submitInspectionFallback(payload));
  },
  getInspectionByTask: async (taskCode) => {
    if (USE_BACKEND)
      {return request(`/inspection/task/${encodeURIComponent(taskCode)}`);}
    return response(mock.empty.emptyList());
  },
  getInspectionByHazard: async (hazardCode) => {
    if (USE_BACKEND)
      {return request(`/inspection/hazard/${encodeURIComponent(hazardCode)}`);}
    return response(mock.empty.emptyList());
  },

  /**
   * AI 综合分析落库数据：获取所有灾害点的最新分数（每 5 分钟自动刷新）。
   * @returns {Promise<Array<{
   *   hazardCode, riskLevel, riskScore, confidence, model, analyzedAt,
   *   dimensions, reasoning, suggestedActions
   * }>>}
   */
  getAiComprehensiveAll: () => {
    if (USE_BACKEND) {return request("/ai/comprehensive-analysis/all");}
    return response(mock.ai.getAiComprehensiveAllMock());
  },

  /** 单条综合分析（读落库缓存，不强制重算）。给 HazardDetailPage 顶部研判区用。 */
  getAiComprehensiveCached: (hazardCode) => {
    if (USE_BACKEND)
      {return request(`/ai/comprehensive-analysis/cached?hazardCode=${encodeURIComponent(hazardCode)}`);}
    return response(mock.ai.aiComprehensiveCachedMock);
  },

  /** 强制刷新所有 hazard 的 AI 综合分析。 */
  refreshAiComprehensive: () => {
    if (USE_BACKEND)
      {return request("/ai/comprehensive-analysis/refresh", { method: "POST" });}
    return response(mock.ai.aiComprehensiveRefreshMock);
  },

  /**
   * 把 AI 综合分数合并进任务列表 / 灾害点列表：按 hazardCode 匹配。
   * 用于 TasksPage/AnalyticsPage/WarningsPage 的统一 AI 评分入口。
   * @param {Array<{id, hazardCode, ...}>} rows
   * @param {Map<string, {riskLevel, riskScore}>} aiMap
   */
  attachAiScores: (rows, aiList) => {
    const map = new Map();
    (aiList ?? []).forEach((s) => map.set(s.hazardCode, s));
    return rows.map((r) => {
      const ai = map.get(r.hazardCode);
      return {
        ...r,
        aiScore: ai?.riskScore ?? null,
        aiLevel: ai?.riskLevel ?? null,
        aiAnalyzedAt: ai?.analyzedAt ?? null,
        // v1.3.0 起 ai_comprehensive_score 落库表新增这些字段
        aiDisasterType: ai?.disasterType ?? null,
        aiDisasterTypeSource: ai?.disasterTypeSource ?? null,
        aiConcernLabel: ai?.concernLabel ?? null,
        aiDeviceAvailable: ai?.deviceAvailable ?? null,
      };
    });
  },

  // ----------------------- AI 设备健康评分 -----------------------

  /**
   * 全量 AI 设备健康评分（按 healthScore 升序，异常在前）。
   * @returns {Promise<Array<{
   *   deviceCode, healthLevel, healthScore, anomalyScore, forecastRisk,
   *   metricCoverage, lastAnomalyAt, reasoning, suggestedActions,
   *   confidence, model, analyzedAt
   * }>>}
   */
  getAiDeviceHealthAll: () => {
    if (USE_BACKEND) {return request("/ai/device-health/all");}
    return response(mock.ai.aiDeviceHealthAllMock);
  },

  /** 单设备 AI 健康评分。 */
  getAiDeviceHealth: (deviceCode) => {
    if (USE_BACKEND)
      {return request(`/ai/device-health?deviceCode=${encodeURIComponent(deviceCode)}`);}
    return response(null);
  },

  /** 强制刷新所有设备 AI 健康评分。 */
  refreshAiDeviceHealth: () => {
    if (USE_BACKEND)
      {return request("/ai/device-health/refresh", { method: "POST" });}
    return response(mock.ai.aiDeviceHealthRefreshMock);
  },

  /**
   * 把 AI 设备健康分合并进设备列表：按 deviceCode 匹配。
   */
  attachAiDeviceHealth: (rows, aiList) => {
    const map = new Map();
    (aiList ?? []).forEach((s) => {
      if (s?.deviceCode) {map.set(s.deviceCode, s);}
    });
    return rows.map((r) => {
      const ai = map.get(r.deviceCode ?? r.code);
      return {
        ...r,
        aiHealthScore: ai?.healthScore ?? null,
        aiHealthLevel: ai?.healthLevel ?? null,
        aiAnomalyScore: ai?.anomalyScore ?? null,
        aiForecastRisk: ai?.forecastRisk ?? null,
        aiReasoning: ai?.reasoning ?? [],
        aiSuggestedActions: ai?.suggestedActions ?? [],
        aiAnalyzedAt: ai?.analyzedAt ?? null,
      };
    });
  },

  // ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===
  // v1.7.0 目标架构新增接口
  // ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===

  // ---------- 3.3 人工确认工作台 ----------
  /**
   * 获取"待确认"队列（按紧急度排序）
   * @returns {Promise<{items: Array<{id, hazardCode, name, type, level, confidence, aiScore, reason, discoveredAt, status, assignee}>, total: number, pending: number}>}
   */
  getConfirmationQueue: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/confirmation/queue${withQuery(params)}`);
      return data ?? { items: [], total: 0, pending: 0 };
    }
    return response(mock.workflow.getConfirmationQueueMock());
  },

  /** 待确认统计（仅返回数量，用于顶栏红点） */
  getConfirmationQueueStats: async () => {
    if (USE_BACKEND) {return request("/confirmation/queue/stats");}
    return response(mock.workflow.confirmationQueueStatsMock);
  },

  /** 认领待确认任务：assignee 设为当前操作人 */
  claimConfirmation: async (id, operator) => {
    requireBackend("认领待确认任务");
    return request(`/confirmation/queue/${id}/claim`, {
      method: "POST",
      body: JSON.stringify({ operator }),
    });
  },

  /** 转派给指定专家 */
  transferConfirmation: async (id, toUser, reason) => {
    requireBackend("转派待确认任务");
    return request(`/confirmation/queue/${id}/transfer`, {
      method: "POST",
      body: JSON.stringify({ toUser, reason }),
    });
  },

  /** 确认：通过（即视为正式灾害事件，落 hazard_point） */
  confirmHazard: async (id, payload = {}) => {
    requireBackend("确认灾害事件");
    return request(`/confirmation/queue/${id}/confirm`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  /** 驳回（误报，归档） */
  rejectHazard: async (id, payload = {}) => {
    requireBackend("驳回灾害事件");
    return request(`/confirmation/queue/${id}/reject`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  // ---------- 4.2 预警创建 ----------
  createWarning: async (payload) => {
    requireBackend("创建预警");
    return request("/warnings", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  /** 预警统计（用于顶栏红点） */
  getWarningStats: async () => {
    if (USE_BACKEND) {return request("/warnings/stats");}
    return response(mock.workflow.warningStatsMock);
  },

  // ---------- 6.3 资源调度 ----------
  getResources: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/resources${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.operations.getResourcesMock());
  },

  assignResource: async (payload) => {
    requireBackend("资源指派");
    return request(`/resources/assign`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  // ---------- 5.5 数据源配置 ----------
  getDataSources: async () => {
    if (USE_BACKEND) {return request("/datasources");}
    return response(mock.operations.getDataSourcesMock());
  },

  toggleDataSource: async (code, enabled) => {
    requireBackend("切换数据源");
    return request(`/datasources/${encodeURIComponent(code)}/toggle`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    });
  },

  // ---------- 5.6 数据同步监控（P3-1） ----------
  /**
   * 数据同步统计：总源数、在线、异常、今日同步次数、平均延迟、分钟入库。
   * 对应后端接口：GET /sync/stats。离线模式 requireBackend 兜底。
   */
  getSyncStats: async () => {
    if (USE_BACKEND) {return request("/sync/stats");}
    requireBackend("加载同步统计");
  },

  /**
   * 同步任务列表（含实时状态、延迟、入库行数、错误）。
   * 对应后端接口：GET /sync/tasks。
   */
  getSyncTasks: async () => {
    if (USE_BACKEND) {return request("/sync/tasks");}
    requireBackend("加载同步任务");
  },

  /**
   * 同步历史记录。
   * 对应后端接口：GET /sync/history?from=&to=&task=&status=。
   */
  getSyncHistory: async (params = {}) => {
    if (USE_BACKEND) {return request("/sync/history", { params });}
    requireBackend("加载同步历史");
  },

  /**
   * 数据完整性报告（按表统计完整度 + 字段级问题清单）。
   * 对应后端接口：GET /sync/integrity。
   */
  getIntegrityReport: async () => {
    if (USE_BACKEND) {return request("/sync/integrity");}
    requireBackend("加载完整性报告");
  },

  /**
   * 异常值告警列表（按严重度、字段、设备筛选）。
   * 对应后端接口：GET /sync/anomalies。
   */
  getSyncAnomalies: async () => {
    if (USE_BACKEND) {return request("/sync/anomalies");}
    requireBackend("加载异常值告警");
  },

  /**
   * 手动触发同步任务。
   * 对应后端接口：POST /sync/tasks/:id/trigger。
   */
  triggerSyncTask: async (id) => {
    requireBackend("触发同步任务");
    return request(`/sync/tasks/${encodeURIComponent(id)}/trigger`, {
      method: "POST",
    });
  },

  /**
   * 暂停同步任务。
   * 对应后端接口：POST /sync/tasks/:id/pause。
   */
  pauseSyncTask: async (id) => {
    requireBackend("暂停同步任务");
    return request(`/sync/tasks/${encodeURIComponent(id)}/pause`, {
      method: "POST",
    });
  },

  /**
   * 忽略异常值告警。
   * 对应后端接口：POST /sync/anomalies/:id/ignore。
   */
  ignoreAnomaly: async (id, reason) => {
    requireBackend("忽略异常值");
    return request(`/sync/anomalies/${encodeURIComponent(id)}/ignore`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },

  testDataSource: async (code) => {
    requireBackend("测试数据源连接");
    return request(`/datasources/${encodeURIComponent(code)}/test`, {
      method: "POST",
    });
  },

  // ---------- 8.1 用户管理 ----------
  getUsers: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/users${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.system.getUsersMock());
  },

  createUser: async (payload) => {
    requireBackend("创建用户");
    return request("/users", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  updateUser: async (id, payload) => {
    requireBackend("更新用户");
    return request(`/users/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },

  deleteUser: async (id) => {
    requireBackend("删除用户");
    return request(`/users/${id}`, { method: "DELETE" });
  },

  getRoles: async () => {
    if (USE_BACKEND) {return request("/roles");}
    return response(mock.system.getRolesMock());
  },

  // ---------- 8.3 日志审计 ----------
  getAuditLogs: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/audit/logs${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.system.getAuditLogsMock());
  },

  // ---------- 8.4 系统监控 ----------
  getSystemMonitor: async () => {
    if (USE_BACKEND) {return request("/system/monitor");}
    return response(mock.system.getSystemMonitorMock());
  },

  // ---------- 9.1 消息中心 ----------
  getMessages: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/messages${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.workflow.getMessagesMock());
  },

  /** 消息统计（用于顶栏红点） */
  getMessageStats: async () => {
    if (USE_BACKEND) {return request("/messages/stats");}
    return response(mock.workflow.messageStatsMock);
  },

  markMessageRead: async (id) => {
    requireBackend("标记已读");
    return request(`/messages/${id}/read`, { method: "POST" });
  },

  markAllMessagesRead: async () => {
    requireBackend("全部标已读");
    return request(`/messages/read-all`, { method: "POST" });
  },

  // ---------- 通用：sentinel 卫星合成图层 ----------
  getSentinelCoverage: async (bbox) => {
    if (USE_BACKEND) {return request(`/sentinel/coverage${bbox ? `?bbox=${encodeURIComponent(bbox)}` : ""}`);}
    return response(mock.analytics.sentinelCoverageMock);
  },

  // ---------- v1.7.x：任务执行监控（运行时 / 日志 / 重试 / 终止） ----------
  /**
   * 获取任务运行时指标：CPU/GPU/内存/时长/重试次数/吞吐。
   * 离线模式下根据 taskId + 状态派生稳定 mock，避免每次刷新结果大跳变。
   * @param {string|number} id 任务 ID
   * @returns {Promise<{status: 'RUNNING'|'SUCCESS'|'FAILED'|'PENDING'|'PAUSED'|'TERMINATED'|'TIMEOUT', cpu: number, gpu: number, memMB: number, startedAt: string, finishedAt?: string, duration: number, retries: number, throughput: number}>}
   */
  getTaskRuntime: async (id) => {
    if (USE_BACKEND) {return request(`/tasks/${id}/runtime`);}
    return response(mock.ai.getTaskRuntimeMock(id));
  },

  /**
   * 获取任务执行日志（增量拉取：`since` 为上次游标 ISO 时间）。
   * 离线模式基于 taskId 派生 8-20 条稳定 mock，按 ts 升序。
   * @param {string|number} id 任务 ID
   * @param {{since?: string}} params
   * @returns {Promise<{entries: Array<{ts: string, level: 'INFO'|'WARN'|'ERR'|'DEBUG', message: string}>, cursor: string}>}
   */
  getTaskExecutionLog: async (id, params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/tasks/${id}/log${withQuery(params)}`);
      return data ?? { entries: [], cursor: new Date().toISOString() };
    }
    return response(mock.ai.getTaskExecutionLogMock(id));
  },

  /**
   * 立即重试任务（手动触发一次新执行）。
   * 离线模式用 requireBackend 拦截并返回安全值，UI 上层做 toast 提示。
   * @param {string|number} id 任务 ID
   */
  retryTask: async (id) => {
    requireBackend("重试任务");
    return request(`/tasks/${id}/retry`, { method: "POST" });
  },

  /**
   * 手动终止任务（仅运行中可用）。
   * 离线模式用 requireBackend 拦截，UI 上层根据错误提示用户启用后端。
   * @param {string|number} id 任务 ID
   */
  terminateTask: async (id) => {
    requireBackend("终止任务");
    return request(`/tasks/${id}/terminate`, { method: "POST" });
  },

  // ───────────────────────── 待办中心 / 审批中心 ─────────────────────────
  // 下列方法仅作为前端骨架入口，后端尚未实现 /todo/* 与 /approvals/* 时，
  // 离线模式直接 throw BACKEND_REQUIRED，由页面 try/catch 兜底成「暂无待办」。

  /**
   * 待办中心顶部 4 个汇总数字。
   * @returns {Promise<{confirmation:number, approval:number, pendingWarnings:number, openEmergency:number}>}
   */
  getTodoSummary: async () => {
    if (!USE_BACKEND) {requireBackend("加载待办汇总");}
    return request("/todo/summary");
  },

  /**
   * 待我确认（确认工作台入口）。
   * 离线模式返回 seedState 中 hazards 的前 N 条作为演示数据。
   */
  listMyConfirmations: async () => {
    if (USE_BACKEND) {return request("/todo/confirmations");}
    return response(mock.workflow.listMyConfirmationsMock());
  },

  /**
   * 待我审批。
   * 离线模式以 seedState.warnings 中 status=待确认 作为演示数据。
   */
  listMyApprovals: async () => {
    if (USE_BACKEND) {return request("/todo/approvals");}
    return response(mock.workflow.listMyApprovalsMock());
  },

  /**
   * 待发布预警。
   */
  listMyPendingWarnings: async () => {
    if (USE_BACKEND) {return request("/todo/warnings");}
    return response(mock.workflow.listMyPendingWarningsMock());
  },

  /**
   * 待跟踪任务（含已派发任务和已派发预警的进度）。
   */
  listMyOpenTasks: async () => {
    if (USE_BACKEND) {return request("/todo/tasks");}
    return response(mock.workflow.listMyOpenTasksMock());
  },

  // ───────────────────────── 审批中心 ─────────────────────────

  /**
   * 审批列表（支持过滤）。
   * 离线模式：直接 requireBackend 拦截，UI 上层以「暂无数据」占位。
   */
  listApprovals: async (filters = {}) => {
    if (!USE_BACKEND) {requireBackend("加载审批列表");}
    return request(`/approvals${withQuery(filters)}`);
  },

  /**
   * 审批统计：总待审、已审、通过率、平均耗时 + 14 天每日审批量。
   * 离线模式回退零值骨架。
   */
  getApprovalStats: async () => {
    if (!USE_BACKEND) {requireBackend("加载审批统计");}
    return request("/approvals/stats");
  },

  /**
   * 审批决策（通过 / 驳回）。
   * @param {string|number} id
   * @param {{decision:'approve'|'reject', comment:string}} payload
   */
  decideApproval: async (id, payload) => {
    if (!USE_BACKEND) {requireBackend("提交审批决策");}
    return request(`/approvals/${id}/decide`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  // ---------- v1.7.x：预警发布回执页 ----------
  /**
   * 获取预警的多渠道发布汇总（应发 / 已送达 / 已阅读 / 已确认回执）。
   * 离线模式下返回零值结构，供 UI 在未接后端时仍能渲染骨架。
   * @param {string|number} id 预警 ID
   */
  getDeliverySummary: async (id) => {
    if (USE_BACKEND) {return request(`/warnings/${id}/delivery/summary`);}
    return response(mock.empty.emptyDeliverySummary(id));
  },

  /**
   * 按渠道 / 状态拉取预警的发布明细记录。
   * 离线模式返回空 records，避免误用模拟数据。
   * @param {string|number} id 预警 ID
   * @param {{channel?: string, status?: string}} params
   */
  getDeliveryRecords: async (id, params = {}) => {
    if (USE_BACKEND) {
      const data = await request(
        `/warnings/${id}/delivery/records${withQuery(params)}`,
      );
      return normalizePaged(data, []);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * 重发指定渠道 / 目标的预警。必须接后端（写操作）。
   * @param {string|number} id 预警 ID
   * @param {{channel: string, targetIds?: Array<string|number>}} payload
   */
  redeliver: async (id, payload = {}) => {
    requireBackend("重发预警");
    return request(`/warnings/${id}/delivery/redeliver`, {
      method: "POST",
      body: JSON.stringify(payload ?? {}),
    });
  },

  // ---------- v1.7.x：灾种 / 区域专项大屏 ----------
  /**
   * 灾种 / 区域专项大屏聚合数据。离线模式下从 seedState 聚合过滤后回退。
   * @param {{kind?: string, region?: string}} params
   */
  getZoneSummary: async ({ kind, region } = {}) => {
    if (USE_BACKEND) {
      return request(`/zones/summary${withQuery({ kind, region })}`);
    }
    return response(mock.analytics.getZoneSummaryMock({ kind, region }));
  },

  /**
   * 灾种 / 区域专项的 14 天风险趋势。离线模式按 kind 派生稳定的伪序列。
   * @param {{kind?: string, region?: string}} params
   */
  getZoneRiskTrend: async ({ kind, region } = {}) => {
    if (USE_BACKEND) {
      return request(`/zones/risk-trend${withQuery({ kind, region })}`);
    }
    return response(mock.analytics.getZoneRiskTrendMock({ kind, region }));
  },

  // ---------- v1 PRD 2.5: AI 模型管理 ----------
  /**
   * 模型版本库列表，支持按模型族与状态过滤。
   * @param {{family?: string, status?: string}} params
   */
  listModels: async ({ family, status } = {}) => {
    if (USE_BACKEND) {
      return request(
        `/ai/models${withQuery({ family, status })}`,
      );
    }
    return response(mock.system.modelsListMock);
  },

  /**
   * 模型版本详情（含特征重要性、训练曲线、评估指标）。
   * @param {string|number} versionId
   */
  getModelDetail: async (versionId) => {
    if (USE_BACKEND) {
      return request(`/ai/models/${versionId}`);
    }
    return response(mock.system.modelDetailMock);
  },

  /**
   * 模型族统计聚合（在线数、今日推理次数、平均时延、综合 F1）。
   */
  getModelFamilyStats: async () => {
    if (USE_BACKEND) {
      return request("/ai/models/stats");
    }
    return response(mock.ai.modelFamilyStatsMock);
  },

  /**
   * 训练任务列表。
   */
  getModelTrainingJobs: async () => {
    if (USE_BACKEND) {
      return request("/ai/models/training-jobs");
    }
    return response(mock.ai.trainingJobsMock);
  },

  /**
   * 新建训练任务。
   * @param {{family: string, dataset: string, epochs: number, lr: number, batchSize: number, remark?: string}} payload
   */
  createModelTrainingJob: async (payload = {}) => {
    if (USE_BACKEND) {
      return request("/ai/models/training-jobs", {
        method: "POST",
        body: JSON.stringify(payload ?? {}),
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * 启动训练任务。
   * @param {string|number} id
   */
  startTrainingJob: async (id) => {
    if (USE_BACKEND) {
      return request(`/ai/models/training-jobs/${id}/start`, {
        method: "POST",
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * 停止训练任务。
   * @param {string|number} id
   */
  stopTrainingJob: async (id) => {
    if (USE_BACKEND) {
      return request(`/ai/models/training-jobs/${id}/stop`, {
        method: "POST",
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * 拉取训练日志（支持 since 游标增量拉取）。
   * @param {string|number} id
   * @param {{since?: string}} params
   */
  getTrainingJobLog: async (id, params = {}) => {
    if (USE_BACKEND) {
      return request(
        `/ai/models/training-jobs/${id}/log${withQuery(params)}`,
      );
    }
    return response(mock.ai.trainingJobLogMock);
  },

  /**
   * 激活指定模型版本为当前生产版本。
   * @param {string|number} versionId
   */
  activateModelVersion: async (versionId) => {
    if (USE_BACKEND) {
      return request(`/ai/models/${versionId}/activate`, {
        method: "POST",
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * 下线（软删除）指定模型版本。
   * @param {string|number} versionId
   */
  deleteModelVersion: async (versionId) => {
    if (USE_BACKEND) {
      return request(`/ai/models/${versionId}`, {
        method: "DELETE",
      });
    }
    return response(mock.empty.ok);
  },

  // ===================== 5.3 设备轨迹回放 =====================
  /**
   * 获取设备轨迹点。
   * @param {string|number} deviceId
   * @param {{from: string, to: string}} params
   * @returns {Promise<Array<{lon: number, lat: number, ts: string, speed?: number}>>}
   */
  getDeviceTrack: async (deviceId, { from, to } = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/devices/${deviceId}/track${withQuery({ from, to })}`);
      return Array.isArray(data) ? data : (data.points ?? []);
    }
    return response(mock.runtime.getDeviceTrackMock(deviceId, { from, to }));
  },

  // ===================== 6.4 路径规划 =====================
  /**
   * 路径规划。
   * @param {{startLon: number, startLat: number, endLon: number, endLat: number, avoidZones?: Array}} payload
   * @returns {Promise<Array<{type: 'fastest'|'safest', distance: number, duration: string, warning?: string, geometry: Array}>>}
   */
  planRoute: async (payload = {}) => {
    if (USE_BACKEND) {
      return request("/resources/route", {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.runtime.planRouteMock(payload));
  },

  // ===================== 2.4 AI 巡检报告 =====================
  /**
   * AI 巡检报告数据。
   * @param {{days?: number, from?: string, to?: string}} params
   * @returns {Promise<{
   *   coverage: number, discoveredEvents: number, falsePositiveRate: number, missRate: number,
   *   coverageTrend: number[], stackedBars: Array<{fp: number, miss: number}>,
   *   modelF1: {xgboost: number[], lstm: number[], yolo: number[]},
   *   matrix: number[][]
   * }>}
   */
  getInspectionReport: async (params = {}) => {
    if (USE_BACKEND) {
      return request(`/analytics/inspection-report${withQuery(params)}`);
    }
    return response(mock.analytics.getInspectionReportMock(params));
  },

  // ---------- OTA 与边缘计算 ----------
  /**
   * 固件包库列表。
   * @param {{deviceType?: string, keyword?: string}} filters
   */
  listFirmware: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/ota/firmwares${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * 上传固件包（multipart）。
   * @param {{file: Blob, version: string, deviceType: string, remark?: string}} payload
   */
  uploadFirmware: async (payload = {}) => {
    if (USE_BACKEND) {
      const form = new FormData();
      if (payload.file) {form.append("file", payload.file);}
      Object.entries(payload).forEach(([k, v]) => {
        if (k === "file") {return;}
        if (v === undefined || v === null) {return;}
        form.append(k, String(v));
      });
      return request("/ota/firmwares", { method: "POST", body: form });
    }
    return response(mock.empty.ok);
  },

  /**
   * 删除固件包。
   * @param {string|number} firmwareId
   */
  deleteFirmware: async (firmwareId) => {
    if (USE_BACKEND) {
      return request(`/ota/firmwares/${firmwareId}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * 升级任务列表（支持 status 筛选）。
   * @param {{status?: string, deviceId?: string}} filters
   */
  listOtaTasks: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * 新建批量升级任务。
   * @param {{firmwareId: string|number, deviceIds: Array<string|number>, resumeOnReconnect?: boolean, rollbackOnFailure?: boolean}} payload
   */
  createOtaTask: async (payload = {}) => {
    if (USE_BACKEND) {
      return request("/ota/tasks", {
        method: "POST",
        body: JSON.stringify(payload ?? {}),
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * 取消升级任务。
   * @param {string|number} taskId
   */
  cancelOtaTask: async (taskId) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks/${taskId}/cancel`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  /**
   * 重试升级任务。
   * @param {string|number} taskId
   */
  retryOtaTask: async (taskId) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks/${taskId}/retry`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  /**
   * 升级任务详情（含步骤时间轴与错误日志）。
   * @param {string|number} taskId
   */
  getOtaTaskDetail: async (taskId) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks/${taskId}`);
    }
    return response(null);
  },

  // ---------- 通知模板 ----------
  /**
   * 通知模板列表（按灾种/等级/渠道/状态过滤）。
   * @param {{hazardType?: string, level?: string, channel?: string, status?: string}} filters
   */
  listNoticeTemplates: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/notice-templates${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * 通知模板详情。
   * @param {string|number} id
   */
  getNoticeTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/notice-templates/${id}`);
    }
    return response(null);
  },

  /**
   * 新建或更新通知模板（payload.id 存在时为 PUT）。
   * @param {{id?: string|number, name: string, hazardType: string, level: string, channels: Record<string, string>, enabled?: boolean}} payload
   */
  upsertNoticeTemplate: async (payload = {}) => {
    if (USE_BACKEND) {
      const hasId = payload?.id !== undefined && payload?.id !== null && `${payload.id}` !== "";
      const path = hasId ? `/notice-templates/${payload.id}` : "/notice-templates";
      const method = hasId ? "PUT" : "POST";
      const body = hasId ? payload : (({ id: _id, ...rest }) => rest)(payload);
      return request(path, { method, body: JSON.stringify(body) });
    }
    return response(mock.empty.ok);
  },

  /**
   * 删除通知模板。
   * @param {string|number} id
   */
  deleteNoticeTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/notice-templates/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * 切换通知模板启用状态。
   * @param {string|number} id
   */
  toggleNoticeTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/notice-templates/${id}/toggle`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  // ---------- 应急预案 ----------
  /**
   * 预案库列表。
   * @param {{hazardType?: string, level?: string, status?: string}} filters
   */
  listPlans: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/plans${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * 预案详情（含启动流程、附件、负责人）。
   * @param {string|number} planId
   */
  getPlanDetail: async (planId) => {
    if (USE_BACKEND) {
      return request(`/plans/${planId}`);
    }
    return response(null);
  },

  /**
   * 新建或更新预案（payload.id 存在时为 PUT）。
   * @param {{id?: string|number, name: string, hazardType: string, level: string, steps: Array, attachments?: Array, owner?: string, enabled?: boolean}} payload
   */
  upsertPlan: async (payload = {}) => {
    if (USE_BACKEND) {
      const hasId = payload?.id !== undefined && payload?.id !== null && `${payload.id}` !== "";
      const path = hasId ? `/plans/${payload.id}` : "/plans";
      const method = hasId ? "PUT" : "POST";
      const body = hasId ? payload : (({ id: _id, ...rest }) => rest)(payload);
      return request(path, { method, body: JSON.stringify(body) });
    }
    return response(mock.empty.ok);
  },

  /**
   * 删除预案。
   * @param {string|number} planId
   */
  deletePlan: async (planId) => {
    if (USE_BACKEND) {
      return request(`/plans/${planId}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * 模拟启动预案：推进 timeline 至第 1 步并生成启动记录。
   * @param {string|number} planId
   */
  simulatePlanLaunch: async (planId) => {
    if (USE_BACKEND) {
      return request(`/plans/${planId}/simulate-launch`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  /**
   * 预案启动记录列表。
   */
  listPlanLaunches: async () => {
    if (USE_BACKEND) {
      return request("/plans/launches");
    }
    return response(mock.empty.emptyPaged);
  },

  // ===================== 8.5 备份与恢复 =====================
  /**
   * 备份记录列表（含状态、大小、耗时等）。
   * @param {{type?: string, source?: string, status?: string, page?: number, size?: number}} filters
   */
  listBackups: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/backup/records${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * 创建一次备份任务（手动）。
   * @param {{scope: string[], remark?: string}} payload
   */
  createBackup: async (payload = {}) => {
    if (USE_BACKEND) {
      return request("/backup/records", {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * 触发备份校验（异步，调用方应轮询 verifyStatus 获取进度）。
   * @param {string|number} id
   */
  verifyBackup: async (id) => {
    if (USE_BACKEND) {
      return request(`/backup/records/${id}/verify`, {
        method: "POST",
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * 触发恢复流程。
   * @param {string|number} id
   * @param {{environment: 'dev'|'test'|'prod'|'sandbox', confirm?: boolean}} payload
   */
  restoreBackup: async (id, payload = {}) => {
    if (USE_BACKEND) {
      return request(`/backup/records/${id}/restore`, {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * 删除备份记录。
   * @param {string|number} id
   */
  deleteBackup: async (id) => {
    if (USE_BACKEND) {
      return request(`/backup/records/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * 备份策略列表。
   */
  listBackupStrategies: async () => {
    if (USE_BACKEND) {
      return request("/backup/strategies");
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * 新建或更新备份策略（payload.id 存在时为 PUT）。
   * @param {{id?: string|number, name: string, scope: string[], cron: string, retention: number, trigger?: string, notify?: string[], enabled?: boolean}} payload
   */
  upsertBackupStrategy: async (payload = {}) => {
    if (USE_BACKEND) {
      const hasId = payload?.id !== undefined && payload?.id !== null && `${payload.id}` !== "";
      const path = hasId ? `/backup/strategies/${payload.id}` : "/backup/strategies";
      const method = hasId ? "PUT" : "POST";
      const body = hasId ? payload : (({ id: _id, ...rest }) => rest)(payload);
      return request(path, { method, body: JSON.stringify(body) });
    }
    return response(mock.empty.ok);
  },

  /**
   * 删除备份策略。
   * @param {string|number} id
   */
  deleteBackupStrategy: async (id) => {
    if (USE_BACKEND) {
      return request(`/backup/strategies/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * 立即触发指定备份策略的执行。
   * @param {string|number} id
   */
  runBackupStrategy: async (id) => {
    if (USE_BACKEND) {
      return request(`/backup/strategies/${id}/run`, {
        method: "POST",
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * 恢复历史列表。
   */
  listRestoreHistory: async () => {
    if (USE_BACKEND) {
      return request("/backup/restores");
    }
    return response(mock.empty.emptyPaged);
  },

  // ===================== 账号与安全 =====================
  /**
   * 获取当前账号的安全配置（基本信息、2FA、备用码、近期会话）。
   */
  getAccountSecurity: async () => {
    requireBackend("getAccountSecurity");
    return request("/account/security");
  },

  /**
   * 启用/关闭双因子认证并配置方式。
   * @param {{enabled: boolean, methods: ('totp'|'sms'|'email')[]}} payload
   */
  toggle2FA: async (payload = {}) => {
    requireBackend("toggle2FA");
    return request("/account/security/2fa", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  /**
   * 重新生成备用恢复码（一次性展示，需用户自行保存）。
   */
  regenerateBackupCodes: async () => {
    requireBackend("regenerateBackupCodes");
    return request("/account/security/backup-codes/regenerate", {
      method: "POST",
    });
  },

  /**
   * 账号登录历史。
   * @param {{page?: number, size?: number, status?: string}} filters
   */
  listLoginHistory: async (filters = {}) => {
    requireBackend("listLoginHistory");
    return request(`/account/login-history${withQuery(filters)}`);
  },

  /**
   * 当前账号活跃会话列表。
   */
  listActiveSessions: async () => {
    requireBackend("listActiveSessions");
    return request("/account/sessions");
  },

  /**
   * 登出指定会话。
   * @param {string|number} id
   */
  revokeSession: async (id) => {
    requireBackend("revokeSession");
    return request(`/account/sessions/${id}`, { method: "DELETE" });
  },

  /**
   * 当前账号操作审计。
   * @param {{page?: number, size?: number, type?: string}} filters
   */
  listAccountAudit: async (filters = {}) => {
    requireBackend("listAccountAudit");
    return request(`/account/audit${withQuery(filters)}`);
  },

  // ===================== 报表中心 =====================
  /**
   * 报表模板库（支持按分类 / 关键字筛选）。
   * @param {{category?: 'operation'|'supervision'|'custom', keyword?: string, page?: number, size?: number}} filters
   */
  listReportTemplates: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/reports/templates${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * 新建报表模板（草稿状态）。
   * @param {{name: string, category: 'operation'|'supervision'|'custom', description?: string, fields: string[], palette?: string}} payload
   */
  createReportTemplate: async (payload = {}) => {
    if (USE_BACKEND) {
      return request("/reports/templates", {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.empty.ok, { id: `tpl-${Date.now()}` });
  },

  /**
   * 删除报表模板。
   * @param {string|number} id
   */
  deleteReportTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/reports/templates/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * 触发一次报表生成（手动运行）。
   * @param {string|number} id
   * @param {{range?: string, format?: 'pdf'|'excel'|'csv', recipients?: string[]}} payload
   */
  runReportTemplate: async (id, payload = {}) => {
    if (USE_BACKEND) {
      return request(`/reports/templates/${id}/run`, {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response({ ...mock.empty.ok, historyId: `h-${Date.now()}` });
  },

  /**
   * 新增 / 更新报表订阅配置。
   * @param {{templateId: string|number, cron: 'daily'|'weekly'|'monthly', time: string, recipients: string[], enabled: boolean}} payload
   */
  updateReportSubscription: async (payload = {}) => {
    if (USE_BACKEND) {
      return request("/reports/subscriptions", {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * 报表生成历史（支持按状态、模板、时间筛选）。
   * @param {{templateId?: string|number, status?: 'pending'|'running'|'success'|'failed', trigger?: 'manual'|'scheduled'|'event', range?: string, page?: number, size?: number}} filters
   */
  listReportHistory: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/reports/history${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * 重跑一次历史任务。
   * @param {string|number} id
   */
  rerunReportHistory: async (id) => {
    if (USE_BACKEND) {
      return request(`/reports/history/${id}/rerun`, {
        method: "POST",
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * 取消进行中的报表任务。
   * @param {string|number} id
   */
  cancelReportHistory: async (id) => {
    if (USE_BACKEND) {
      return request(`/reports/history/${id}/cancel`, {
        method: "POST",
      });
    }
    return response(mock.empty.ok);
  },

  // ===================== 多时相对比 =====================
  /**
   * 获取两段时间在同一指标维度下的对比数据。
   * - base / compare：形如 "24h" / "7d" / "30d" / "custom:2025-09-01T00:00:00Z,2025-09-07T23:59:59Z"
   * - mode：并排 / 叠加 / 差异率（用于 KPI 矩阵展示差异）
   * 在线模式：调用后端 `/analytics/compare`；离线模式按 base+compare 哈希出稳定可复现的伪随机序列，
   * 保证每次刷新同一查询条件得到的对比结果一致（基于 seedState 的 incidents/warnings/hazards/devices 派生）。
   * @param {{base: string, compare: string, mode?: 'side'|'overlay'|'delta'}} params
   */
  getTimeRangeComparison: async ({ base, compare, mode = "side" } = {}) => {
    const safeBase = base || "24h";
    const safeCompare = compare || "previous";
    const safeMode = ["side", "overlay", "delta"].includes(mode) ? mode : "side";
    if (USE_BACKEND) {
      const data = await request(
        `/analytics/compare${withQuery({ base: safeBase, compare: safeCompare, mode: safeMode })}`,
      );
      return data ?? response({ base: {}, compare: {}, series: [], kpis: [], categories: {}, regions: {} });
    }
    return response(
      mock.analytics.getTimeRangeComparisonMock({ base: safeBase, compare: safeCompare, mode: safeMode }),
    );
  },

  // ===================== 数据资产目录 =====================
  /**
   * 目录顶部 KPI 概览（资源总数 / 表数量 / 总字段数 / 上次更新时间）。
   */
  getCatalogSummary: async () => {
    requireBackend("getCatalogSummary");
    return request("/catalog/summary");
  },

  /**
   * 数据资源列表（带筛选）。
   * @param {{type?: string, category?: string, source?: string, keyword?: string, page?: number, size?: number}} filters
   */
  listDataAssets: async (filters = {}) => {
    requireBackend("listDataAssets");
    return request(`/catalog/assets${withQuery(filters)}`);
  },

  /**
   * 资源详情（字段 / 血缘 / 标签 / 元数据）。
   * @param {string|number} id
   */
  getDataAssetDetail: async (id) => {
    requireBackend("getDataAssetDetail");
    return request(`/catalog/assets/${id}`);
  },

  /**
   * 提交资源访问权限申请。
   * @param {string|number} id
   * @param {{scenario: string, duration: string|number, reason: string}} payload
   */
  requestDataAssetAccess: async (id, payload = {}) => {
    requireBackend("requestDataAssetAccess");
    return request(`/catalog/assets/${id}/access-requests`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  /**
   * 更新资源标签。
   * @param {string|number} id
   * @param {{tags: string[]}} payload
   */
  updateDataAssetTags: async (id, payload = {}) => {
    requireBackend("updateDataAssetTags");
    return request(`/catalog/assets/${id}/tags`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },

  /**
   * 资源使用排行（按 7/30 天查询次数排序）。
   */
  listCatalogUsage: async () => {
    requireBackend("listCatalogUsage");
    return request("/catalog/usage");
  },

  /**
   * 订阅资源变更通知。
   * @param {string|number} id
   */
  subscribeAssetChange: async (id) => {
    requireBackend("subscribeAssetChange");
    return request(`/catalog/assets/${id}/subscribe-change`, {
      method: "POST",
    });
  },

  // ==========================================================================
  // P0 阶段新增接口
  // ==========================================================================

  // ---------- P0-1 路径规划 ----------
  /** 获取当前灾害影响区域作为避障多边形 */
  getObstacleAreas: async () => {
    if (USE_BACKEND) {return request("/path-planning/obstacles");}
    return response(mock.runtime.getObstacleAreasMock());
  },
  /** 计算路径：返回多条方案 */
  planRoutes: async (payload = {}) => {
    if (USE_BACKEND) {
      return request("/path-planning/calculate", {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.runtime.planRoutesWithObstaclesMock(payload));
  },
  /** 导出路径为 GeoJSON */
  exportRouteGeoJson: async (route) => {
    if (USE_BACKEND) {
      return request("/path-planning/export", {
        method: "POST",
        body: JSON.stringify(route),
      });
    }
    return response({ geojson: mock.runtime.exportRouteGeoJsonMock(route) });
  },

  // ---------- P0-2 预警解除 ----------
  /** 预览解除通知文案 */
  previewWarningResolve: async (warning) => {
    if (USE_BACKEND) {
      return request(`/warnings/${warning?.id ?? warning?.warningId}/resolve/preview`);
    }
    return response(mock.runtime.previewWarningResolveMock(warning));
  },
  /** 解除范围（行政区域下钻） */
  getWarningResolveScope: async (id) => {
    if (USE_BACKEND) {return request(`/warnings/${id}/resolve/scope`);}
    return response({ scopes: [
      { value: "ALL", label: "全部" },
      { value: "龙安镇", label: "龙安镇" },
      { value: "石桥镇", label: "石桥镇" },
      { value: "清河镇", label: "清河镇" },
    ] });
  },
  /** 解除预警（终结既有 resolve 端点 + 理由/范围/渠道） */
  resolveWarning: async (id, payload = {}) => {
    requireBackend("解除预警");
    return request(`/warnings/${id}/resolve`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  // ---------- P0-3 个人偏好 ----------
  getProfile: async () => {
    if (USE_BACKEND) {return request("/profile");}
    return response(mock.system.getUsersMock?.()?.[0] ?? {
      id: "u-admin", username: "admin", name: "李晨曦",
      phone: "138****8888", email: "admin@geosentry.cn", department: "应急指挥中心",
      avatar: "", role: "admin",
    });
  },
  updateProfile: async (payload) => {
    if (USE_BACKEND) {
      return request("/profile", { method: "PUT", body: JSON.stringify(payload) });
    }
    return response({ ok: true, data: payload, updatedAt: new Date().toISOString() });
  },
  changeMyPassword: async (payload) => {
    if (USE_BACKEND) {
      return request("/profile/password", { method: "PUT", body: JSON.stringify(payload) });
    }
    if (payload?.current === "123456") {
      return response({ ok: true });
    }
    const err = new Error("旧密码不正确");
    err.code = "PWD";
    throw err;
  },
  getUserPreference: async (userId) => {
    if (USE_BACKEND) {return request("/profile/notification-preference");}
    return response(mock.runtime.getUserPreferenceMock(userId));
  },
  updateUserPreference: async (payload) => {
    if (USE_BACKEND) {
      return request("/profile/notification-preference", {
        method: "PUT",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.runtime.saveUserPreferenceMock("u-admin", payload));
  },
  updateThemeLanguage: async (payload) => {
    if (USE_BACKEND) {
      return request("/profile/theme-language", {
        method: "PUT",
        body: JSON.stringify(payload),
      });
    }
    return response({ ok: true, ...payload });
  },

  // ---------- P0-4 会签 ----------
  listSignRecords: async (eventId) => {
    if (USE_BACKEND) {return request(`/events/${eventId}/sign`);}
    return response(mock.runtime.listSignRecordsMock(eventId));
  },
  createSign: async (payload) => {
    if (USE_BACKEND) {
      return request(`/events/${payload.eventId}/sign`, {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.runtime.createSignMock(payload));
  },
  confirmSign: async ({ signId, signerId, opinion }) => {
    if (USE_BACKEND) {
      return request(`/events/${signId}/sign/${signId}/confirm`, {
        method: "PUT",
        body: JSON.stringify({ signerId, opinion }),
      });
    }
    return response(mock.runtime.signConfirmMock(signId, signerId, opinion));
  },
  rejectSign: async ({ signId, signerId, opinion }) => {
    if (USE_BACKEND) {
      return request(`/events/${signId}/sign/${signId}/reject`, {
        method: "PUT",
        body: JSON.stringify({ signerId, opinion }),
      });
    }
    return response(mock.runtime.signRejectMock(signId, signerId, opinion));
  },
  transferEvent: async ({ eventId, toUser, reason }) => {
    if (USE_BACKEND) {
      return request(`/events/${eventId}/transfer`, {
        method: "POST",
        body: JSON.stringify({ toUser, reason }),
      });
    }
    return response(mock.runtime.transferEventMock(eventId, toUser, reason));
  },

  // ---------- P0-5 区域专项大屏 ----------
  getZoneKpi: async (params = {}) => {
    if (USE_BACKEND) {return request("/dashboard/zone/kpi" + withQuery(params));}
    return response(mock.runtime.getZoneKpiMock(params));
  },
  getZoneTrend: async (params = {}) => {
    if (USE_BACKEND) {return request("/dashboard/zone/trend" + withQuery(params));}
    return response(mock.runtime.getZoneTrendMock(params));
  },
  getZoneTopHazards: async (params = {}) => {
    if (USE_BACKEND) {return request("/dashboard/zone/top-hazards" + withQuery(params));}
    return response(mock.runtime.getZoneTopHazardsMock(params));
  },
  getZoneRelatedWarnings: async (params = {}) => {
    if (USE_BACKEND) {return request("/dashboard/zone/related-warnings" + withQuery(params));}
    return response(mock.runtime.getZoneRelatedWarningsMock(params));
  },
  getZoneRelatedEmergencies: async (params = {}) => {
    if (USE_BACKEND) {return request("/dashboard/zone/related-emergencies" + withQuery(params));}
    return response(mock.runtime.getZoneRelatedEmergenciesMock(params));
  },

  // ===X= ZoneDashboardPage 向后兼容 shim（聚合 KPI / 趋势 / Top5 / 关联预警 / 关联应急） ===X=
  /** @deprecated 用 getZoneKpi/getZoneTrend/getZoneTopHazards 分别替代 */
  getZoneSummary: async (filter = {}) => {
    const [kpi, trend, topHazards, relatedWarnings, relatedIncidents] = await Promise.all([
      dashboardApi.getZoneKpi(filter).catch(() => ({})),
      dashboardApi.getZoneTrend(filter).catch(() => ({ points: [] })),
      dashboardApi.getZoneTopHazards(filter).catch(() => []),
      dashboardApi.getZoneRelatedWarnings(filter).catch(() => []),
      dashboardApi.getZoneRelatedEmergencies(filter).catch(() => []),
    ]);
    return { ...kpi, topHazards, relatedWarnings, relatedIncidents, points: trend.points ?? [] };
  },
  /** @deprecated 用 getZoneTrend 替代 */
  getZoneRiskTrend: async (filter = {}) => {
    return dashboardApi.getZoneTrend(filter);
  },
  /** 兼容旧路径别名 */
  getZoneRelatedWarningsBy: (params) => dashboardApi.getZoneRelatedWarnings(params),
  getZoneRelatedEmergenciesBy: (params) => dashboardApi.getZoneRelatedEmergencies(params),
};
