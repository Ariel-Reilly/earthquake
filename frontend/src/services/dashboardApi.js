import { createApiError } from "./_architecture/core/errors.js";
import { request, downloadFile } from "./_architecture/core/httpClient.js";
import {
  getRuntimeMode,
  isBackendRuntime,
  getUseBackend,
  initBackendMode,
  requireBackend,
  API_BASE,
} from "./_architecture/core/runtime.js";
import { withQuery } from "../utils/query.js";

export {
  createApiError,
  request,
  downloadFile,
  getRuntimeMode,
  isBackendRuntime,
  getUseBackend,
  initBackendMode,
  requireBackend,
  API_BASE,
  withQuery,
};

// USE_BACKEND ?? runtime.js ??,?? getUseBackend() ??;
export const USE_BACKEND = getUseBackend();

const _latency = 240;
function _clone(data) {
  return typeof structuredClone === "function"
    ? structuredClone(data)
    : JSON.parse(JSON.stringify(data));
}
export function response(data) {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(_clone(data)), _latency);
  });
}

import * as mock from "./mock";

function normalizePaged(payload, fallback = []) {
  if (!payload)
    {return {
      records: fallback,
      total: fallback.length,
      page: 1,
      size: fallback.length,
      pages: 1,
    };}
  // 后端分页响应存在两种字段形态：records（PageResponse）与 items（部分 Controller）。
  // 只认 records 会把 items 形态的响应误判为空，故此处两者都兼容。
  const list = Array.isArray(payload.records)
    ? payload.records
    : Array.isArray(payload.items)
      ? payload.items
      : null;
  if (list) {
    return {
      records: list,
      total: payload.total ?? list.length,
      page: payload.page ?? 1,
      size: payload.size ?? list.length,
      pages: payload.pages ?? 1,
    };
  }
  return {
    records: fallback,
    total: fallback.length,
    page: 1,
    size: fallback.length,
    pages: 1,
  };
}

// withQuery 已迁移到 utils/query.js

/**
 * 统一事件状态 → 会签队列页签文案。
 *
 * 后端把状态存成英文枚举（unified_event.state），界面按中文页签展示，
 * 映射集中在这里，避免各处散落 if-else。
 */
const QUEUE_STATE_STATUS = {
  DETECTED: "待确认",
  CONFIRMING: "已认领",
  CONFIRMED: "已确认",
  REJECTED: "已驳回",
  CLOSED: "已关闭",
  WARNING_ISSUED: "已发布预警",
  EMERGENCY_ACTIVE: "应急处置中",
};

/**
 * 会签队列项 → 前端视图模型。
 *
 * 后端 GET /confirmation/queue 返回 hazardName/hazardType/riskLevel/state，
 * 而界面按 name/type/level/status 取值，缺这层映射时队列在后端模式下整片显示为空。
 * mock 侧已经使用前端约定的字段名，因此这里对两侧统一做一次归一化。
 *
 * @param {object} raw 后端或 mock 返回的原始队列项
 * @returns {object} 前端视图模型
 */
function normalizeQueueItem(raw) {
  if (!raw) {return null;}
  const state = raw.state ?? "";
  const transferred = raw.transferred === true;
  // 转派后事件仍是「确认中」，靠转派记录把它与「已认领」区分开
  const status =
    raw.status ??
    (state === "CONFIRMING" && transferred
      ? "已转派"
      : QUEUE_STATE_STATUS[state] ?? "待确认");
  return {
    ...raw,
    hazardCode: raw.hazardCode ?? raw.code ?? "",
    name: raw.name ?? raw.hazardName ?? raw.title ?? "",
    type: raw.type ?? raw.hazardType ?? "",
    level: raw.level ?? raw.riskLevel ?? "",
    status,
    assignee: raw.assignee ?? null,
    transferred,
    transferredTo: raw.transferredTo ?? null,
    confidence: raw.confidence ?? null,
    aiScore: raw.aiScore ?? raw.riskScore ?? null,
    reason: raw.reason ?? "",
    discoveredAt: raw.discoveredAt ?? raw.createdAt ?? null,
    regionCode: raw.regionCode ?? "",
    longitude: raw.longitude ?? raw.lon ?? null,
    latitude: raw.latitude ?? raw.lat ?? null,
  };
}

/**
 * fallback(): 统一兜底数据源
 * - USE_BACKEND=false 时从 mock.seed.SEED_BASE 读取/克隆
 * - USE_BACKEND=true 时返回空数组，避免 null 传播到 mock
 * 详见 ./mock/seed.js
 */
function fallback(key) {
  if (USE_BACKEND) {return [];}
  return mock.seed.SEED_BASE[key] ?? [];
}

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
    throw createApiError("用户名和密码不能为空");
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
    if (!USE_BACKEND) {requireBackend("综合态势");}
    return request("/dashboard/overview");
  },
  getHazards: async (params = {}) => {
    if (!USE_BACKEND) {requireBackend("灾害列表");}
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

  getAiMonitorHazards: async () => {
    if (!USE_BACKEND) {requireBackend("获取 AI 监测灾害");}
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
    if (!USE_BACKEND) {requireBackend("按视野查询灾害");}
    const data = await request(`/hazards/viewport${withQuery(params)}`);
    return Array.isArray(data)
      ? data
      : normalizePaged(data, fallback("hazardPoints")).records;
  },
  getHazardDetail: async (id) => {
    if (!USE_BACKEND) {requireBackend("灾害详情");}
    return request(`/hazards/${id}`);
  },
  createHazard: async (payload) => {
    requireBackend("新建灾害");
    return request("/hazards", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateHazard: async (id, payload) => {
    requireBackend("更新灾害");
    return request(`/hazards/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteHazard: async (id) => {
    requireBackend("删除灾害");
    return request(`/hazards/${id}`, { method: "DELETE" });
  },
  /**
   * 把灾害点提交到人工确认队列（真实落库，不是前端本地删卡片）。
   * 后端口径：unified_event.state ∈ {DETECTED, CONFIRMING} 即为待确认。
   * 返回 { hazardCode, eventCode, state, alreadyInQueue, message }。
   */
  submitHazardConfirmation: async (hazardCode) => {
    requireBackend("提交人工确认");
    return request(`/hazards/${encodeURIComponent(hazardCode)}/submit-confirmation`, {
      method: "POST",
    });
  },
  /** 归档误报（DISMISS → CLOSED），原因必填。 */
  dismissHazard: async (hazardCode, reason) => {
    requireBackend("归档误报");
    return request(`/hazards/${encodeURIComponent(hazardCode)}/dismiss`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },
  getTasks: async (params = {}) => {
    requireBackend("巡检任务");
    const data = await request(`/tasks${withQuery(params)}`);
    const normalized = normalizePaged(data, fallback("tasks"));
    return {
      list: normalized.records,
      total: normalized.total,
      page: normalized.page,
      size: normalized.size,
      pages: normalized.pages,
    };
  },
  getTaskDetail: async (id) => {
    requireBackend("任务详情");
    return request(`/tasks/${id}`);
  },
  createTask: async (payload) => {
    requireBackend("新建任务");
    return request("/tasks", { method: "POST", body: JSON.stringify(payload) });
  },
  updateTask: async (id, payload) => {
    requireBackend("更新任务");
    return request(`/tasks/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  updateTaskStatus: async (id, status) => {
    requireBackend("更新任务状态");
    return request(`/tasks/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status }),
    });
  },
  deleteTask: async (id) => {
    requireBackend("删除任务");
    return request(`/tasks/${id}`, { method: "DELETE" });
  },
  getDevices: async (params = {}) => {
    requireBackend("设备列表");
    const data = await request(`/devices${withQuery(params)}`);
    const normalized = normalizePaged(data, fallback("devices"));
    return {
      list: normalized.records,
      total: normalized.total,
      page: normalized.page,
      size: normalized.size,
      pages: normalized.pages,
    };
  },
  getDevicesByViewport: async ({ bbox, zoom, state, keyword } = {}) => {
    const params = { bbox, zoom, state, keyword };
    requireBackend("按视野查询设备");
    const data = await request(`/devices/viewport${withQuery(params)}`);
    return Array.isArray(data)
      ? data
      : normalizePaged(data, fallback("devices")).records;
  },
  getDeviceDetail: async (id) => {
    requireBackend("设备详情");
    return request(`/devices/${id}`);
  },
  createDevice: async (device) => {
    requireBackend("创建设备");
    return request("/devices", {
      method: "POST",
      body: JSON.stringify(device),
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
    requireBackend("预警列表");
    const data = await request(`/warnings${withQuery(params)}`);
    const normalized = normalizePaged(data, fallback("warnings"));
    return {
      list: normalized.records,
      total: normalized.total,
      page: normalized.page,
      size: normalized.size,
      pages: normalized.pages,
    };
  },
  getWarningsPreview: async () => {
    requireBackend("预警预览");
    const data = await request(`/warnings/preview`);
    return Array.isArray(data)
      ? { list: data }
      : normalizePaged(data, fallback("warnings"));
  },
  getWarningsByViewport: async ({ bbox, zoom, keyword, status } = {}) => {
    const params = { bbox, zoom, keyword, status };
    requireBackend("按视野查询预警");
    const data = await request(`/warnings/viewport${withQuery(params)}`);
    return Array.isArray(data)
      ? data
      : normalizePaged(data, fallback("warnings")).records;
  },
  getWarningDetail: async (id) => {
    requireBackend("预警详情");
    return request(`/warnings/${id}`);
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
    requireBackend("下发预警");
    return request(`/warnings/${id}/dispatch`, { method: "POST" });
  },
  closeWarning: async (id, payload = {}) => {
    requireBackend("关闭预警");
    const reason = payload.reason ?? "未填写关闭原因";
    return request(`/warnings/${id}/close`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },
  dismissWarning: async (id) => {
    requireBackend("解除预警");
    return request(`/warnings/${id}/dismiss`, { method: "POST" });
  },
  resolveWarning: async (id) => {
    requireBackend("预警解除");
    return request(`/warnings/${id}/resolve`, { method: "POST" });
  },
  getEmergency: async () => {
    requireBackend("应急事件列表");
    const data = await request("/emergency?size=200");
    const records = Array.isArray(data?.records)
      ? data.records
      : Array.isArray(data?.list)
        ? data.list
        : [];
    const incidents = records.map((e) => ({
      id: e.id,
      title: e.title,
      level: e.status === "处置中" ? "处置中" : "已结束",
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
  },
  getPersonnel: async () => {
    requireBackend("人员列表");
    const data = await request(`/personnel/positions`);
    return Array.isArray(data) ? { list: data } : normalizePaged(data, []);
  },
  closeIncident: async (id) => {
    requireBackend("关闭应急事件");
    return request(`/emergency/${id}/close`, {
      method: "POST",
      body: JSON.stringify({ summary: "应急处置已完成" }),
    });
  },
  dispatchIncident: async (id) => {
    requireBackend("派发应急事件");
    return request(`/emergency/${id}/dispatch`, { method: "POST" });
  },
  exportOverviewReport: async () => {
    return downloadFile("/dashboard/overview/export", "综合态势报表.xlsx");
  },
  exportHazardReport: async () => {
    return downloadFile("/hazards/export", "灾害统计报表.xlsx");
  },
  exportTaskReport: async () => {
    return downloadFile("/tasks/export", "巡检任务报表.xlsx");
  },
  exportDeviceReport: async () => {
    return downloadFile("/devices/export", "设备台账报表.xlsx");
  },
  exportWarningReport: async () => {
    return downloadFile("/warnings/export", "预警统计报表.xlsx");
  },
  exportEmergencyReport: async () => {
    return downloadFile("/emergency/export", "应急事件报表.xlsx");
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
      {throw createApiError(payload?.message || "附件上传失败");}
    return payload.data;
  },
  getAttachments: async ({ businessType, businessCode }) => {
    if (USE_BACKEND)
      {return request(
        `/attachments?businessType=${encodeURIComponent(businessType)}&businessCode=${encodeURIComponent(businessCode)}`,
      );}
    return response(mock.empty.emptyAttachments);
  },
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
   * GET /api/inspection/example
   */
  getInspectionExample: async () => {
    if (USE_BACKEND) {return request("/inspection/example");}
    return response(mock.empty.inspectionExampleFallback());
  },

  /**
   * POST /api/personnel/positions/report
   * @param {{userId: string, name?: string, lon: number, lat: number, taskCode?: string, hazardCode?: string, speed?: number, heading?: number}} payload
   */
  reportPersonnelPosition: async (payload = {}) => {
    if (USE_BACKEND) {
      return request("/personnel/positions/report", {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.runtime.reportPersonnelPositionMock(payload));
  },

  /**
   *
   *
   * @param {string|number} hazardId
   * @returns {Promise<{
   *   hazard: object|null,
   *   warnings: Array,
   *   emergencies: Array,
   *   inspections: Array
   * }>}
   */
    getRelatedEvents: async (hazardId, hazardCode) => {
    requireBackend("上报人员定位");
    let resolvedCode = hazardCode;
    let hazard = null;

    const [hazardDetail, warningsResp, emergencyResp] = await Promise.all([
      resolvedCode
        ? Promise.resolve(null)
        : request(`/hazards/${hazardId}`).catch(() => null),
      request(`/warnings?size=200`).catch(() => null),
      request(`/emergency?size=200`).catch(() => null),
    ]);
    if (!resolvedCode) {
      hazard = hazardDetail?.item ?? hazardDetail ?? null;
      resolvedCode = hazard?.hazardCode ?? hazard?.code;
    }
    const warningsRaw = warningsResp?.list ?? warningsResp?.records ?? [];
    const emergenciesRaw = emergencyResp?.incidents ?? emergencyResp?.records ?? [];
    const warnings = warningsRaw.filter(
      (w) => !resolvedCode || w.hazardCode === resolvedCode,
    );
    const emergencies = emergenciesRaw.filter(
      (e) => !resolvedCode || e.hazardCode === resolvedCode,
    );
    return { hazard, warnings, emergencies, inspections: [] };
  },getAiComprehensiveAll: () => {
    if (USE_BACKEND) {return request("/ai/comprehensive-analysis/all");}
    return response(mock.ai.getAiComprehensiveAllMock());
  },

  getAiComprehensiveCached: (hazardCode) => {
    if (USE_BACKEND)
      {return request(`/ai/comprehensive-analysis/cached?hazardCode=${encodeURIComponent(hazardCode)}`);}
    return response(mock.ai.aiComprehensiveCachedMock);
  },

  refreshAiComprehensive: () => {
    if (USE_BACKEND)
      {return request("/ai/comprehensive-analysis/refresh", { method: "POST" });}
    return response(mock.ai.aiComprehensiveRefreshMock);
  },

  /**
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
        // 队列列表要展示 AI 置信度，落库的综合分析里带这个字段
        confidence: r.confidence ?? ai?.confidence ?? null,
        aiLevel: ai?.riskLevel ?? null,
        aiAnalyzedAt: ai?.analyzedAt ?? null,
        aiDisasterType: ai?.disasterType ?? null,
        aiDisasterTypeSource: ai?.disasterTypeSource ?? null,
        aiConcernLabel: ai?.concernLabel ?? null,
        aiDeviceAvailable: ai?.deviceAvailable ?? null,
      };
    });
  },


  /**
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

  getAiDeviceHealth: (deviceCode) => {
    if (USE_BACKEND)
      {return request(`/ai/device-health?deviceCode=${encodeURIComponent(deviceCode)}`);}
    return response(null);
  },

  refreshAiDeviceHealth: () => {
    if (USE_BACKEND)
      {return request("/ai/device-health/refresh", { method: "POST" });}
    return response(mock.ai.aiDeviceHealthRefreshMock);
  },

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


  /**
   * @returns {Promise<{items: Array<{id, hazardCode, name, type, level, confidence, aiScore, reason, discoveredAt, status, assignee}>, total: number, pending: number}>}
   */
  getConfirmationQueue: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/confirmation/queue${withQuery(params)}`);
      const items = (data?.items ?? []).map(normalizeQueueItem).filter(Boolean);
      return { ...(data ?? {}), items, total: data?.total ?? items.length };
    }
    const mockData = await response(mock.workflow.getConfirmationQueueMock());
    const items = (mockData?.items ?? []).map(normalizeQueueItem).filter(Boolean);
    return { ...(mockData ?? {}), items, total: mockData?.total ?? items.length };
  },

  getConfirmationQueueStats: async () => {
    if (USE_BACKEND) {return request("/confirmation/stats");}
    return response(mock.workflow.confirmationQueueStatsMock);
  },

  claimConfirmation: async (id, operator) => {
    requireBackend("认领会签任务");
    return request(`/confirmation/queue/${id}/claim`, {
      method: "POST",
      body: JSON.stringify({ operator }),
    });
  },

  transferConfirmation: async (id, toUser, reason) => {
    requireBackend("转派会签任务");
    return request(`/confirmation/queue/${id}/transfer`, {
      method: "POST",
      body: JSON.stringify({ toUser, reason }),
    });
  },

  confirmHazard: async (id, payload = {}) => {
    requireBackend("确认灾害");
    return request(`/confirmation/queue/${id}/confirm`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  rejectHazard: async (id, payload = {}) => {
    requireBackend("驳回灾害");
    return request(`/confirmation/queue/${id}/reject`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  createWarning: async (payload) => {
    requireBackend("发布预警");
    return request("/warnings", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  getWarningStats: async () => {
    if (USE_BACKEND) {return request("/warnings/stats");}
    return response(mock.workflow.warningStatsMock);
  },

  getResources: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/resources${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.operations.getResourcesMock());
  },

  assignResource: async (payload) => {
    requireBackend("资源调度");
    return request(`/resources/assign`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

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

  getSyncStats: async () => {
    if (USE_BACKEND) {return request("/sync/stats");}
    requireBackend("同步统计");
  },

  getSyncTasks: async () => {
    if (USE_BACKEND) {return request("/sync/tasks");}
    requireBackend("同步任务");
  },

  getSyncHistory: async (params = {}) => {
    if (USE_BACKEND) {return request("/sync/history", { params });}
    requireBackend("同步历史");
  },

  getIntegrityReport: async () => {
    if (USE_BACKEND) {return request("/sync/integrity");}
    requireBackend("数据完整性报告");
  },

  getSyncAnomalies: async () => {
    if (USE_BACKEND) {return request("/sync/anomalies");}
    requireBackend("同步异常列表");
  },

  triggerSyncTask: async (id) => {
    requireBackend("触发同步");
    return request(`/sync/tasks/${encodeURIComponent(id)}/trigger`, {
      method: "POST",
    });
  },

  pauseSyncTask: async (id) => {
    requireBackend("暂停同步");
    return request(`/sync/tasks/${encodeURIComponent(id)}/pause`, {
      method: "POST",
    });
  },

  testDataSource: async (code) => {
    requireBackend("测试数据源连接");
    return request(`/datasources/${encodeURIComponent(code)}/test`, {
      method: "POST",
    });
  },

  createDataSource: async (source) => {
    requireBackend("新增数据源");
    return request("/datasources", {
      method: "POST",
      body: JSON.stringify(source),
    });
  },

  updateDataSource: async (code, source) => {
    requireBackend("更新数据源");
    return request(`/datasources/${encodeURIComponent(code)}`, {
      method: "PUT",
      body: JSON.stringify(source),
    });
  },

  getUsers: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/users${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.system.getUsersMock());
  },

  createUser: async (payload) => {
    requireBackend("新建用户");
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

  resetUserPassword: async (id, newPassword) => {
    requireBackend("重置密码");
    return request(`/users/${id}/reset-password`, {
      method: "POST",
      body: JSON.stringify({ newPassword }),
    });
  },

  /**
   * 提交帮助中心用户反馈。
   * 后端落库到统一操作审计（operation_log，module=support），返回受理号 ticketId。
   * 无后端时直接报错，不伪造"提交成功"。
   */
  submitSupportFeedback: async (payload) => {
    requireBackend("提交反馈");
    return request("/support/feedback", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  getRoles: async () => {
    if (USE_BACKEND) {return request("/roles");}
    return response(mock.system.getRolesMock());
  },

  /**
   * 权限矩阵（角色 → 权限码），数据源为后端 PermissionRegistry。
   * 返回 { roles: string[], permissions: string[], matrix: Record<string, string[]> }。
   * 无后端时返回空矩阵，由页面展示错误态（不造假的权限表）。
   */
  getPermissionMatrix: async () => {
    requireBackend("权限矩阵");
    return request("/roles/permission-matrix");
  },

  /* ── 注册申请 ─────────────────────────────────────────────
   * 匿名接口（captcha / send-code / register / register-status）
   * 统一传 auth:false：这些调用发生在登录之前，本地可能还残留着已过期
   * 的 token，带上它只会让请求平白多一次无意义的鉴权。
   * 管理端接口（getApplications / approve / reject）则是后端
   * @PreAuthorize("hasRole('ADMIN')") 保护的，必须携带 JWT。
   */

  /**
   * 获取图形验证码。
   * @returns {Promise<{captchaId: string, imageBase64: string}>}
   * @throws {Error} 后端不可达时抛出
   */
  getCaptcha: async () => {
    requireBackend("图形验证码");
    return request("/auth/captcha", { auth: false });
  },

  /**
   * 下发短信 / 邮箱验证码。
   *
   * @param {'phone'|'email'} type - 通道类型
   * @param {string} target - 手机号或邮箱地址
   * @returns {Promise<any>} 后端固定返回 null
   * @throws {Error} 通道未启用时后端返回 50301（网关未接入）
   */
  sendRegisterCode: async (type, target) => {
    requireBackend("短信验证码");
    return request("/auth/register/send-code", {
      method: "POST",
      auth: false,
      body: JSON.stringify({ type, target }),
    });
  },

  /**
   * 提交注册申请（仅落库待审核，不会直接建号）。
   * @param {object} payload - 字段见后端 RegisterRequest
   * @returns {Promise<any>}
   */
  registerRequest: async (payload) => {
    requireBackend("注册申请");
    return request("/auth/register", {
      method: "POST",
      auth: false,
      body: JSON.stringify(payload),
    });
  },

  /**
   * 查询某用户名的申请状态。
   *
   * @param {string} username - 登录用户名
   * @returns {Promise<{status: string, rejectReason: string, createdAt: string}|null>}
   *          该用户名没有任何申请记录时后端返回 null
   */
  getRegisterStatus: async (username) => {
    requireBackend("注册状态");
    return request(`/auth/register/status${withQuery({ username })}`, { auth: false });
  },

  /* ── 注册申请审核（admin） ── */

  /**
   * 分页查询注册申请（后端 status 用中文：待审核 / 已通过 / 已拒绝）。
   * @param {{status?: string, keyword?: string, page?: number, size?: number}} params
   * @returns {Promise<{records: any[], total: number, page: number, size: number, pages: number}>}
   */
  getApplications: async (params = {}) => {
    requireBackend("注册申请审核");
    const data = await request(`/admin/applications${withQuery(params)}`);
    return normalizePaged(data, { items: [] });
  },

  /**
   * 审核通过：后端据此创建系统用户。
   * @param {number|string} id - 申请 ID
   * @returns {Promise<any>}
   */
  approveApplication: async (id) => {
    requireBackend("审核通过");
    return request(`/admin/applications/${encodeURIComponent(id)}/approve`, {
      method: "POST",
    });
  },

  /**
   * 审核拒绝。
   * @param {number|string} id - 申请 ID
   * @param {string} reason - 拒绝原因（后端必填，最长 255）
   * @returns {Promise<any>}
   */
  rejectApplication: async (id, reason) => {
    requireBackend("审核拒绝");
    return request(`/admin/applications/${encodeURIComponent(id)}/reject`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },

  getAuditLogs: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/audit/logs${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.system.getAuditLogsMock());
  },

  getSystemMonitor: async () => {
    if (USE_BACKEND) {return request("/system/monitor");}
    return response(mock.system.getSystemMonitorMock());
  },

  getMessages: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/messages${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.workflow.getMessagesMock());
  },

  getMessageStats: async () => {
    if (USE_BACKEND) {return request("/messages/stats");}
    return response(mock.workflow.messageStatsMock);
  },

  markMessageRead: async (id) => {
    requireBackend("标记已读");
    return request(`/messages/${id}/read`, { method: "POST" });
  },

  markAllMessagesRead: async () => {
    requireBackend("全部标记已读");
    return request(`/messages/read-all`, { method: "POST" });
  },

  getSentinelCoverage: async (bbox) => {
    if (USE_BACKEND) {return request(`/sentinel/coverage${bbox ? `?bbox=${encodeURIComponent(bbox)}` : ""}`);}
    return response(mock.analytics.sentinelCoverageMock);
  },

  /**
   * @param {string|number} id ?? ID
   * @returns {Promise<{status: 'RUNNING'|'SUCCESS'|'FAILED'|'PENDING'|'PAUSED'|'TERMINATED'|'TIMEOUT', cpu: number, gpu: number, memMB: number, startedAt: string, finishedAt?: string, duration: number, retries: number, throughput: number}>}
   */
  getTaskRuntime: async (id) => {
    if (USE_BACKEND) {return request(`/tasks/${id}/runtime`);}
    return response(mock.ai.getTaskRuntimeMock(id));
  },

  /**
   * @param {string|number} id ?? ID
   * @param {{since?: string}} params
   * @returns {Promise<{entries: Array<{ts: string, level: 'INFO'|'WARN'|'ERR'|'DEBUG', message: string}>, cursor: string}>}
   */
  getTaskExecutionLog: async (id, params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/tasks/${id}/execution-log${withQuery(params)}`);
      return data ?? { entries: [], cursor: new Date().toISOString() };
    }
    return response(mock.ai.getTaskExecutionLogMock(id));
  },

  /**
   * @param {string|number} id ?? ID
   */
  retryTask: async (id) => {
    requireBackend("重试任务");
    return request(`/tasks/${id}/retry`, { method: "POST" });
  },

  /**
   * @param {string|number} id ?? ID
   */
  terminateTask: async (id) => {
    requireBackend("终止任务");
    return request(`/tasks/${id}/terminate`, { method: "POST" });
  },


  /**
   * @returns {Promise<{confirmation:number, approval:number, pendingWarnings:number, openEmergency:number}>}
   */
  getTodoSummary: async () => {
    if (!USE_BACKEND) {requireBackend("待办汇总");}
    return request("/todo/summary");
  },

  listMyConfirmations: async () => {
    if (USE_BACKEND) {return request("/todo/confirmations");}
    return response(mock.workflow.listMyConfirmationsMock());
  },

  listMyApprovals: async () => {
    if (USE_BACKEND) {return request("/todo/approvals");}
    return response(mock.workflow.listMyApprovalsMock());
  },

  listMyPendingWarnings: async () => {
    if (USE_BACKEND) {return request("/todo/warnings");}
    return response(mock.workflow.listMyPendingWarningsMock());
  },

  listMyOpenTasks: async () => {
    if (USE_BACKEND) {return request("/todo/tasks");}
    return response(mock.workflow.listMyOpenTasksMock());
  },


  listApprovals: async (filters = {}) => {
    if (!USE_BACKEND) {requireBackend("审批列表");}
    return request(`/approvals${withQuery(filters)}`);
  },

  getApprovalStats: async () => {
    if (!USE_BACKEND) {requireBackend("审批统计");}
    return request("/approvals/stats");
  },

  /**
   * @param {string|number} id
   * @param {{decision:'approve'|'reject', comment:string}} payload
   */
  decideApproval: async (id, payload) => {
    if (!USE_BACKEND) {requireBackend("审批决策");}
    return request(`/approvals/${id}/decide`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  /**
   * @param {string|number} id ?? ID
   */
  getDeliverySummary: async (id) => {
    if (USE_BACKEND) {return request(`/warnings/${id}/delivery/summary`);}
    return response(mock.empty.emptyDeliverySummary(id));
  },

  /**
   * @param {string|number} id ?? ID
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
   * @param {string|number} id ?? ID
   * @param {{channel: string, targetIds?: Array<string|number>}} payload
   */
  redeliver: async (id, payload = {}) => {
    requireBackend("重新投递");
    return request(`/warnings/${id}/delivery/redeliver`, {
      method: "POST",
      body: JSON.stringify(payload ?? {}),
    });
  },

  /**
   * @param {{kind?: string, region?: string}} params
   */
  getZoneSummary: async ({ kind, region } = {}) => {
    if (USE_BACKEND) {
      // 后端实际路径是 /api/dashboard/zone/kpi（ZoneDashboardController），
      // 历史上写成了不存在的 /zones/summary，后端从未有过 /api/zones 这个基址。
      return request(`/dashboard/zone/kpi${withQuery({ kind, region })}`);
    }
    return response(mock.analytics.getZoneSummaryMock({ kind, region }));
  },

  /**
   * @param {{kind?: string, region?: string}} params
   */
  getZoneRiskTrend: async ({ kind, region } = {}) => {
    if (USE_BACKEND) {
      // 同上：/zones/risk-trend 不存在，对应后端 /api/dashboard/zone/trend。
      return request(`/dashboard/zone/trend${withQuery({ kind, region })}`);
    }
    return response(mock.analytics.getZoneRiskTrendMock({ kind, region }));
  },

  /**
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
   * @param {string|number} versionId
   */
  getModelDetail: async (versionId) => {
    if (USE_BACKEND) {
      return request(`/ai/models/${versionId}`);
    }
    return response(mock.system.modelDetailMock);
  },

  getModelFamilyStats: async () => {
    if (USE_BACKEND) {
      return request("/ai/models/stats");
    }
    return response(mock.ai.modelFamilyStatsMock);
  },

  getModelTrainingJobs: async () => {
    if (USE_BACKEND) {
      return request("/ai/models/training-jobs");
    }
    return response(mock.ai.trainingJobsMock);
  },

  /**
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

  /**
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

  /**
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

  /**
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

  /**
   * @param {{deviceType?: string, keyword?: string}} filters
   */
  listFirmware: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/ota/firmwares${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
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
   * @param {string|number} firmwareId
   */
  deleteFirmware: async (firmwareId) => {
    if (USE_BACKEND) {
      return request(`/ota/firmwares/${firmwareId}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * @param {{status?: string, deviceId?: string}} filters
   */
  listOtaTasks: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
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
   * @param {string|number} taskId
   */
  cancelOtaTask: async (taskId) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks/${taskId}/cancel`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  /**
   * @param {string|number} taskId
   */
  retryOtaTask: async (taskId) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks/${taskId}/retry`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  /**
   * @param {string|number} taskId
   */
  getOtaTaskDetail: async (taskId) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks/${taskId}`);
    }
    return response(null);
  },

  /**
   * @param {{hazardType?: string, level?: string, channel?: string, status?: string}} filters
   */
  listNoticeTemplates: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/notice-templates${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * @param {string|number} id
   */
  getNoticeTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/notice-templates/${id}`);
    }
    return response(null);
  },

  /**
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
   * @param {string|number} id
   */
  deleteNoticeTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/notice-templates/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * @param {string|number} id
   */
  toggleNoticeTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/notice-templates/${id}/toggle`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  /**
   * @param {string|number} id
   * @param {{channel?: string, target?: string}} payload
   */
  testNoticeTemplate: async (id, payload = {}) => {
    if (USE_BACKEND) {
      return request(`/notice-templates/${id}/test`, {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * @param {{name: string, hazardType: string, level: string, channels: Record<string, string>, enabled?: boolean}} template
   */
  createNoticeTemplate: async (template) => {
    if (USE_BACKEND) {
      return request("/notice-templates", {
        method: "POST",
        body: JSON.stringify(template),
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * @param {string|number} id
   * @param {object} template
   */
  updateNoticeTemplate: async (id, template) => {
    if (USE_BACKEND) {
      return request(`/notice-templates/${id}`, {
        method: "PUT",
        body: JSON.stringify(template),
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * @param {{hazardType?: string, level?: string, status?: string}} filters
   */
  listPlans: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/plans${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * @param {string|number} planId
   */
  getPlanDetail: async (planId) => {
    if (USE_BACKEND) {
      return request(`/plans/${planId}`);
    }
    return response(null);
  },

  /**
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
   * @param {{name: string, hazardType: string, level: string, steps: Array, attachments?: Array, owner?: string, version?: string, enabled?: boolean}} plan
   */
  createPlan: async (plan) => {
    if (USE_BACKEND) {
      return request("/plans", { method: "POST", body: JSON.stringify(plan) });
    }
    return response({ ...mock.empty.ok, id: `plan-${Date.now()}` });
  },

  /**
   * @param {string|number} id
   * @param {{name?: string, hazardType?: string, level?: string, steps?: Array, attachments?: Array, owner?: string, version?: string, enabled?: boolean}} plan
   */
  updatePlan: async (id, plan) => {
    if (USE_BACKEND) {
      return request(`/plans/${id}`, { method: "PUT", body: JSON.stringify(plan) });
    }
    return response(mock.empty.ok);
  },

  /**
   * @param {string|number} planId
   */
  deletePlan: async (planId) => {
    if (USE_BACKEND) {
      return request(`/plans/${planId}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * @param {string|number} planId
   */
  launchPlan: async (planId, params = {}) => {
    if (USE_BACKEND) {
      return request(`/plans/${planId}/launch`, {
        method: "POST",
        body: JSON.stringify(params),
      });
    }
    return response(mock.empty.ok);
  },

  /**
   * @param {string|number} planId
   */
  simulatePlanLaunch: async (planId) => {
    if (USE_BACKEND) {
      return request(`/plans/${planId}/simulate-launch`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  listPlanLaunches: async () => {
    if (USE_BACKEND) {
      return request("/plans/launches");
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * @param {{type?: string, source?: string, status?: string, page?: number, size?: number}} filters
   */
  listBackups: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/backup/records${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
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
   * @param {string|number} id
   */
  deleteBackup: async (id) => {
    if (USE_BACKEND) {
      return request(`/backup/records/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  listBackupStrategies: async () => {
    if (USE_BACKEND) {
      return request("/backup/strategies");
    }
    return response(mock.empty.emptyPaged);
  },

  /**
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
   * @param {string|number} id
   */
  deleteBackupStrategy: async (id) => {
    if (USE_BACKEND) {
      return request(`/backup/strategies/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
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

  listRestoreHistory: async () => {
    if (USE_BACKEND) {
      return request("/backup/restores");
    }
    return response(mock.empty.emptyPaged);
  },

  getAccountSecurity: async () => {
    requireBackend("getAccountSecurity");
    return request("/account/security");
  },

  /**
   * @param {{enabled: boolean, methods: ('totp'|'sms'|'email')[]}} payload
   */
  toggle2FA: async (payload = {}) => {
    requireBackend("toggle2FA");
    return request("/account/security/2fa", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  regenerateBackupCodes: async () => {
    requireBackend("regenerateBackupCodes");
    return request("/account/security/backup-codes/regenerate", {
      method: "POST",
    });
  },

  /**
   * @param {{page?: number, size?: number, status?: string}} filters
   */
  listLoginHistory: async (filters = {}) => {
    requireBackend("listLoginHistory");
    return request(`/account/login-history${withQuery(filters)}`);
  },

  listActiveSessions: async () => {
    requireBackend("listActiveSessions");
    return request("/account/sessions");
  },

  /**
   * @param {string|number} id
   */
  revokeSession: async (id) => {
    requireBackend("revokeSession");
    return request(`/account/sessions/${id}`, { method: "DELETE" });
  },

  /**
   * @param {{page?: number, size?: number, type?: string}} filters
   */
  listAccountAudit: async (filters = {}) => {
    requireBackend("listAccountAudit");
    return request(`/account/audit${withQuery(filters)}`);
  },

  /**
   * @param {{category?: 'operation'|'supervision'|'custom', keyword?: string, page?: number, size?: number}} filters
   */
  listReportTemplates: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/reports/templates${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
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
   * @param {string|number} id
   */
  deleteReportTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/reports/templates/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
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
   * @param {{templateId?: string|number, status?: 'pending'|'running'|'success'|'failed', trigger?: 'manual'|'scheduled'|'event', range?: string, page?: number, size?: number}} filters
   */
  listReportHistory: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/reports/history${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
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

  /**
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

  getCatalogSummary: async () => {
    requireBackend("getCatalogSummary");
    return request("/catalog/summary");
  },

  /**
   * @param {{type?: string, category?: string, source?: string, keyword?: string, page?: number, size?: number}} filters
   */
  listDataAssets: async (filters = {}) => {
    requireBackend("listDataAssets");
    return request(`/catalog/assets${withQuery(filters)}`);
  },

  createCatalogAsset: async (asset) => {
    requireBackend("新增数据资产");
    return request("/catalog/assets", {
      method: "POST",
      body: JSON.stringify(asset),
    });
  },

  /**
   * @param {string|number} id
   */
  getDataAssetDetail: async (id) => {
    requireBackend("getDataAssetDetail");
    return request(`/catalog/assets/${id}`);
  },

  /**
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

  listCatalogUsage: async () => {
    requireBackend("listCatalogUsage");
    return request("/catalog/usage");
  },

  /**
   * @param {string|number} id
   */
  subscribeAssetChange: async (id) => {
    requireBackend("subscribeAssetChange");
    // 后端实际路径是 /api/catalog/assets/{id}/subscribe（CatalogController），
    // 且该接口用 @RequestBody 读取 email/webhook，缺 body 会被 Spring 判为 400。
    return request(`/catalog/assets/${id}/subscribe`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  },


  getObstacleAreas: async () => {
    if (USE_BACKEND) {return request("/path-planning/obstacles");}
    return response(mock.runtime.getObstacleAreasMock());
  },
  planRoutes: async (payload = {}) => {
    if (USE_BACKEND) {
      return request("/path-planning/calculate", {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.runtime.planRoutesWithObstaclesMock(payload));
  },
  exportRouteGeoJson: async (route) => {
    if (USE_BACKEND) {
      return request("/path-planning/export", {
        method: "POST",
        body: JSON.stringify(route),
      });
    }
    return response({ geojson: mock.runtime.exportRouteGeoJsonMock(route) });
  },

  previewWarningResolve: async (warning) => {
    if (USE_BACKEND) {
      return request(`/warnings/${warning?.id ?? warning?.warningId}/resolve/preview`);
    }
    return response(mock.runtime.previewWarningResolveMock(warning));
  },
  getWarningResolveScope: async (id) => {
    if (USE_BACKEND) {return request(`/warnings/${id}/resolve/scope`);}
    return response({ scopes: [
      { value: "ALL", label: "全部" },
      { value: "龙安镇", label: "龙安镇" },
      { value: "石桥镇", label: "石桥镇" },
      { value: "清河镇", label: "清河镇" },
    ] });
  },
  resolveWarning: async (id, payload = {}) => {
    requireBackend("解除预警");
    return request(`/warnings/${id}/resolve`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  getProfile: async () => {
    if (USE_BACKEND) {return request("/profile");}
    return response(mock.system.getUsersMock?.()?.[0] ?? {
      id: "u-admin", username: "admin", name: "系统管理员",
      phone: "138****8888", email: "admin@disasterguard.cn", department: "应急管理中心",
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
    const err = new Error("修改密码");
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

  // P0-4 ??
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
  confirmSign: async ({ eventId, signId, signerId, opinion }) => {
    if (USE_BACKEND) {
      return request(`/events/${eventId}/sign/${signId}/confirm`, {
        method: "PUT",
        body: JSON.stringify({ signerId, opinion }),
      });
    }
    return response(mock.runtime.signConfirmMock(signId, signerId, opinion));
  },
  rejectSign: async ({ eventId, signId, signerId, opinion }) => {
    if (USE_BACKEND) {
      return request(`/events/${eventId}/sign/${signId}/reject`, {
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
  /** @deprecated ?? getZoneTrend ?? */
  getZoneRiskTrend: async (filter = {}) => {
    return dashboardApi.getZoneTrend(filter);
  },
  getZoneRelatedWarningsBy: (params) => dashboardApi.getZoneRelatedWarnings(params),
  getZoneRelatedEmergenciesBy: (params) => dashboardApi.getZoneRelatedEmergencies(params),


  /**
   * @param {Object} params
   *   - page/size: ??
   */
  listUnifiedEvents: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/event/list${withQuery(params)}`);
      const list = Array.isArray(data) ? data : (data?.items ?? data?.records ?? []);
      return {
        items: list,
        total: data?.total ?? list.length,
        page: data?.page ?? 1,
        size: data?.size ?? 20,
        pages: data?.pages ?? 1,
      };
    }
    return response(mock.workflow.getUnifiedEventsMock(params));
  },

  /**
   * @returns {Promise<{DETECTED, CONFIRMING, CONFIRMED, WARNING_ISSUED, EMERGENCY_ACTIVE, CLOSED, REJECTED, total, active}>}
   */
  getUnifiedEventStats: async () => {
    if (USE_BACKEND) {
      const data = await request("/event/stats");
      return data ?? {};
    }
    return response(mock.workflow.unifiedEventsStatsMock);
  },

  getUnifiedEventDetail: async (code) => {
    if (USE_BACKEND) {
      return request(`/event/${encodeURIComponent(code)}`);
    }
    return response(mock.workflow.getUnifiedEventDetailMock(code));
  },

  /**
   * @param {Object} payload
   */
  transitionUnifiedEvent: async (code, payload = {}) => {
    requireBackend("统一事件状态流转");
    return request(`/event/${encodeURIComponent(code)}/transition`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  /**
   * @param {string} code
   */
  getUnifiedEventAvailableTriggers: async (code) => {
    const detail = await dashboardApi.getUnifiedEventDetail(code);
    if (!detail) { return []; }
    return dashboardApi.computeAvailableTriggers(detail.state);
  },

  computeAvailableTriggers: (currentState) => {
    const MAP = {
      DETECTED: [
        { trigger: "AI_DETECT", label: "AI 识别", tone: "blue" },
        { trigger: "MANUAL_REPORT", label: "人工上报", tone: "blue" },
        { trigger: "DISMISS", label: "忽略", tone: "gray" },
      ],
      CONFIRMING: [
        { trigger: "CONFIRM", label: "确认", tone: "green" },
        { trigger: "REJECT", label: "驳回", tone: "red" },
        { trigger: "DISMISS", label: "忽略", tone: "gray" },
      ],
      CONFIRMED: [
        { trigger: "ISSUE_WARNING", label: "发布预警", tone: "orange" },
        { trigger: "TRIGGER_EMERGENCY", label: "启动应急", tone: "red" },
        { trigger: "DISMISS", label: "忽略", tone: "gray" },
      ],
      WARNING_ISSUED: [
        { trigger: "TRIGGER_EMERGENCY", label: "启动应急", tone: "red" },
        { trigger: "CLOSE", label: "关闭", tone: "green" },
      ],
      EMERGENCY_ACTIVE: [
        { trigger: "CLOSE", label: "结束应急", tone: "green" },
      ],
      CLOSED: [
        { trigger: "REOPEN", label: "重新打开", tone: "blue" },
      ],
      REJECTED: [
        { trigger: "REOPEN", label: "重新打开", tone: "blue" },
      ],
    };
    return MAP[currentState] ?? [];
  },

  getStateMachineGraph: () => ({
    states: [
      { id: "DETECTED",          label: "已发现",      color: "#4f8f99", icon: "Eye" },
      { id: "CONFIRMING",        label: "确认中",      color: "#cbaa76", icon: "ClipboardCheck" },
      { id: "CONFIRMED",         label: "已确认",      color: "#638fc2", icon: "CheckCircle2" },
      { id: "WARNING_ISSUED",    label: "预警发布",    color: "#d88561", icon: "Bell" },
      { id: "EMERGENCY_ACTIVE",  label: "应急处置",    color: "#e06060", icon: "Truck" },
      { id: "CLOSED",            label: "已关闭",      color: "#9baead", icon: "Archive" },
      { id: "REJECTED",          label: "已驳回",      color: "#8a968d", icon: "XCircle" },
    ],
    transitions: [
      { from: "DETECTED",         to: "CONFIRMING",       trigger: "AI_DETECT/MANUAL_REPORT" },
      { from: "DETECTED",         to: "CLOSED",           trigger: "DISMISS" },
      { from: "CONFIRMING",       to: "CONFIRMED",        trigger: "CONFIRM" },
      { from: "CONFIRMING",       to: "REJECTED",         trigger: "REJECT" },
      { from: "CONFIRMING",       to: "CLOSED",           trigger: "DISMISS" },
      { from: "CONFIRMED",        to: "WARNING_ISSUED",   trigger: "ISSUE_WARNING" },
      { from: "CONFIRMED",        to: "EMERGENCY_ACTIVE", trigger: "TRIGGER_EMERGENCY" },
      { from: "CONFIRMED",        to: "CLOSED",           trigger: "DISMISS" },
      { from: "WARNING_ISSUED",   to: "EMERGENCY_ACTIVE", trigger: "TRIGGER_EMERGENCY" },
      { from: "WARNING_ISSUED",   to: "CLOSED",           trigger: "CLOSE" },
      { from: "EMERGENCY_ACTIVE", to: "CLOSED",           trigger: "CLOSE" },
      { from: "CLOSED",           to: "WARNING_ISSUED",   trigger: "REOPEN" },
    ],
  }),


  /**
   * GET /api/ai/forecast/auto
   * @param {{deviceCode: string, metric?: string, horizon?: number, historyLimit?: number}} params
   * @returns {Promise<{deviceCode, metric, horizon, predictions: number[], confidence: number, model: string, degraded: boolean}>}
   */
  getDisasterForecast: async ({ deviceCode, metric = 'displacement', horizon = 24, historyLimit = 30 } = {}) => {
    if (USE_BACKEND) {
      return request(`/ai/forecast/auto${withQuery({ deviceCode, metric, horizon, historyLimit })}`);
    }
    return response(mock.ai.getDisasterForecastMock({ deviceCode, metric, horizon, historyLimit }));
  },

  /**
   * GET /api/ai/fusion-risk/auto?hazardCode=xxx
   * @param {string} hazardCode
   * @returns {Promise<{hazardCode, riskLevel: 'LOW'|'MEDIUM'|'HIGH'|'CRITICAL', riskScore: number, confidence: number, model: string, degraded: boolean}>}
   */
  getFusionRisk: async (hazardCode) => {
    if (USE_BACKEND) {
      return request(`/ai/fusion-risk/auto${withQuery({ hazardCode })}`);
    }
    return response(mock.ai.getFusionRiskMock(hazardCode));
  },

  /**
   * GET /api/ai/disaster-assessment?hazardCode=xxx
   * @param {string} hazardCode
   * @returns {Promise<DisasterAssessmentResponseDto>}
   */
  getDisasterAssessment: async (hazardCode) => {
    if (USE_BACKEND) {
      return request(`/ai/disaster-assessment${withQuery({ hazardCode })}`);
    }
    return response(mock.ai.getDisasterAssessmentMock(hazardCode));
  },

  /**
   * GET /api/ai/disaster-assessment/status
   */
  getDisasterAssessmentStatus: async () => {
    if (USE_BACKEND) {
      return request("/ai/disaster-assessment/status");
    }
    return response(mock.ai.disasterAssessmentStatusMock);
  },

  /**
   * POST /api/ai/disaster-assessment/independent
   * @param {{points: Array<{lon: number, lat: number, regionCode?: string, regionName?: string, rainfall24h?: number, rainfall7d?: number, temperature?: number, humidity?: number, elevationM?: number, slopeDeg?: number, soilGrade?: number, vegetationCover?: number, distanceToWaterM?: number, geologyGrade?: number, geologyLabel?: string}>, minRiskLevel?: string, mockWeather?: boolean}} payload
   * @returns {Promise<{totalPoints: number, detectedCount: number, results: IndependentResultDto[], executionMs: number}>}
   */
  runIndependentAssessment: async (payload = {}) => {
    if (USE_BACKEND) {
      return request("/ai/disaster-assessment/independent", {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.ai.runIndependentAssessmentMock(payload));
  },

  /**
   * POST /api/ai/welcome-prompts
   * 根据当前上下文生成 AI 推荐问题（预留接口，前端暂用本地规则）
   * @param {{contextType: string, contextId: string, title?: string, metadata?: object}} context
   * @returns {Promise<{welcomeText: string, quickPrompts: string[]}>}
   */
  getAiWelcomePrompts: async (context) => {
    if (USE_BACKEND) {
      return request("/ai/welcome-prompts", {
        method: "POST",
        body: JSON.stringify(context),
      });
    }
    // 本地兜底逻辑（前端规则引擎）
    return response({
      welcomeText: `你好！正在分析 ${context.contextId}`,
      quickPrompts: [`分析 ${context.contextId}`, '查看详情', '生成报告', '联系应急'],
    });
  },
};
