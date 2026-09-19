// Step 3: ???????? _architecture/core/,???? re-export ????????
// ??: ???? `import + export`,???? `export { foo } from '...'`,
// ?? ESLint flat config ?? no-undef ???? foo ????????
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
};

// USE_BACKEND ?? runtime.js ??,?? getUseBackend() ??;
// ???? export { USE_BACKEND } ???? boolean ??(????????)
export const USE_BACKEND = getUseBackend();

// response(): mock ?????? (latency ms), ????????
const _latency = 240;
function _clone(data) {
  return typeof structuredClone === "function"
    ? structuredClone(data)
    : JSON.parse(JSON.stringify(data));
}
/**
 * ????????: ?????? _latency ms ????????
 * ?? mock ???? notificationService / ????????????
 */
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
 * ????????????
 * - USE_BACKEND=false ?????? seedState ??????/??????
 * - USE_BACKEND=true ?????????? API ?? null ???? mock ??
 * ???????? ./mock/seed.js??
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
    throw createApiError("????????");
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
    if (!USE_BACKEND) {requireBackend("??????");}
    return request("/dashboard/overview");
  },
  getHazards: async (params = {}) => {
    if (!USE_BACKEND) {requireBackend("????????");}
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
   * v1.6.0?AI ???????? ???? ???? discovered_at DESC ??????????????
   * ???????? 50 ??????"AI ????"??????????
   */
  getAiMonitorHazards: async () => {
    if (!USE_BACKEND) {requireBackend("?? AI ??????");}
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
    if (!USE_BACKEND) {requireBackend("??????????");}
    const data = await request(`/hazards/viewport${withQuery(params)}`);
    return Array.isArray(data)
      ? data
      : normalizePaged(data, fallback("hazardPoints")).records;
  },
  getHazardDetail: async (id) => {
    if (!USE_BACKEND) {requireBackend("????????");}
    return request(`/hazards/${id}`);
  },
  createHazard: async (payload) => {
    requireBackend("??????");
    return request("/hazards", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateHazard: async (id, payload) => {
    requireBackend("??????");
    return request(`/hazards/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteHazard: async (id) => {
    requireBackend("??????");
    return request(`/hazards/${id}`, { method: "DELETE" });
  },
  getTasks: async (params = {}) => {
    requireBackend("??????");
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
    requireBackend("????????");
    return request(`/tasks/${id}`);
  },
  createTask: async (payload) => {
    requireBackend("??????");
    return request("/tasks", { method: "POST", body: JSON.stringify(payload) });
  },
  updateTask: async (id, payload) => {
    requireBackend("??????");
    return request(`/tasks/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  updateTaskStatus: async (id, status) => {
    requireBackend("??????????");
    return request(`/tasks/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status }),
    });
  },
  deleteTask: async (id) => {
    requireBackend("??????");
    return request(`/tasks/${id}`, { method: "DELETE" });
  },
  getDevices: async (params = {}) => {
    requireBackend("??????");
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
    requireBackend("????????");
    const data = await request(`/devices/viewport${withQuery(params)}`);
    return Array.isArray(data)
      ? data
      : normalizePaged(data, fallback("devices")).records;
  },
  getDeviceDetail: async (id) => {
    requireBackend("??????");
    return request(`/devices/${id}`);
  },
  updateDevice: async (id, payload) => {
    requireBackend("????");
    return request(`/devices/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteDevice: async (id) => {
    requireBackend("????");
    return request(`/devices/${id}`, { method: "DELETE" });
  },
  diagnoseDevice: async (id) => {
    requireBackend("????");
    return request(`/devices/${id}/diagnose`, { method: "POST" });
  },
  getWarnings: async (params = {}) => {
    requireBackend("??????");
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
    // ??????????????????????
    requireBackend("??????");
    const data = await request(`/warnings/preview`);
    return Array.isArray(data)
      ? { list: data }
      : normalizePaged(data, fallback("warnings"));
  },
  getWarningsByViewport: async ({ bbox, zoom, keyword, status } = {}) => {
    const params = { bbox, zoom, keyword, status };
    requireBackend("????????");
    const data = await request(`/warnings/viewport${withQuery(params)}`);
    return Array.isArray(data)
      ? data
      : normalizePaged(data, fallback("warnings")).records;
  },
  getWarningDetail: async (id) => {
    requireBackend("??????");
    return request(`/warnings/${id}`);
  },
  updateWarning: async (id, payload) => {
    requireBackend("????");
    return request(`/warnings/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  confirmWarning: async (id) => {
    requireBackend("????");
    return request(`/warnings/${id}/confirm`, { method: "POST" });
  },
  dispatchWarning: async (id) => {
    requireBackend("????");
    return request(`/warnings/${id}/dispatch`, { method: "POST" });
  },
  closeWarning: async (id, payload = {}) => {
    requireBackend("????");
    const reason = payload.reason ?? "????";
    return request(`/warnings/${id}/close`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },
  dismissWarning: async (id) => {
    requireBackend("????");
    return request(`/warnings/${id}/dismiss`, { method: "POST" });
  },
  resolveWarning: async (id) => {
    requireBackend("??????");
    return request(`/warnings/${id}/resolve`, { method: "POST" });
  },
  getEmergency: async () => {
    requireBackend("?????????");
    const data = await request("/emergency?size=200");
    const records = Array.isArray(data?.records)
      ? data.records
      : Array.isArray(data?.list)
        ? data.list
        : [];
    const incidents = records.map((e) => ({
      id: e.id,
      title: e.title,
      level: e.status === "????" ? "????" : "???????",
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
    requireBackend("??????");
    const data = await request(`/personnel/positions`);
    return Array.isArray(data) ? { list: data } : normalizePaged(data, []);
  },
  closeIncident: async (id) => {
    requireBackend("?????????");
    // ?????POST /emergency/{id}/close ????????"????"??
    // ?? DELETE ????
    return request(`/emergency/${id}/close`, {
      method: "POST",
      body: JSON.stringify({ summary: "??????????" }),
    });
  },
  exportOverviewReport: async () => {
    return downloadFile("/dashboard/overview/export", "????.xlsx");
  },
  exportHazardReport: async () => {
    return downloadFile("/hazards/export", "??????.xlsx");
  },
  exportTaskReport: async () => {
    return downloadFile("/tasks/export", "??????.xlsx");
  },
  exportDeviceReport: async () => {
    return downloadFile("/devices/export", "?????????.xlsx");
  },
  exportWarningReport: async () => {
    return downloadFile("/warnings/export", "??????.xlsx");
  },
  exportEmergencyReport: async () => {
    return downloadFile("/emergency/export", "?????????.xlsx");
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
      {throw createApiError(payload?.message || "????");}
    return payload.data;
  },
  getAttachments: async ({ businessType, businessCode }) => {
    if (USE_BACKEND)
      {return request(
        `/attachments?businessType=${encodeURIComponent(businessType)}&businessCode=${encodeURIComponent(businessCode)}`,
      );}
    return response(mock.empty.emptyAttachments);
  },
  /** ???? presigned URL???? 403/410 ???????? URL ?????? */
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
   * v1.9.0 P1?????????????? payload?????????????
   * GET /api/inspection/example
   */
  getInspectionExample: async () => {
    if (USE_BACKEND) {return request("/inspection/example");}
    return response(mock.empty.inspectionExampleFallback());
  },

  /**
   * v1.9.0 P1???????????????? Redis?WebSocket ??????
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
   * v1.7.0 P1????????????????????????????
   * ?????hazard ???????warning / emergency / inspection ????????
   *
   * ??????
   *   - ?????? seedState ??????
   *   - ???????? hazard ?? / warnings / emergency???? hazardCode ???? join??
   *     ???????????? /hazards/{id}/events ???P1 ???? v1.8 ??????
   *
   * @param {string|number} hazardId
   * @param {string} hazardCode  -- ???? hazardCode??????????
   * @returns {Promise<{
   *   hazard: object|null,
   *   warnings: Array,
   *   emergencies: Array,
   *   inspections: Array
   * }>}
   */
    getRelatedEvents: async (hazardId, hazardCode) => {
    requireBackend("??????????????");
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

  /** ??????????????????????? HazardDetailPage ???????? */
  getAiComprehensiveCached: (hazardCode) => {
    if (USE_BACKEND)
      {return request(`/ai/comprehensive-analysis/cached?hazardCode=${encodeURIComponent(hazardCode)}`);}
    return response(mock.ai.aiComprehensiveCachedMock);
  },

  /** ???????? hazard ?? AI ?????? */
  refreshAiComprehensive: () => {
    if (USE_BACKEND)
      {return request("/ai/comprehensive-analysis/refresh", { method: "POST" });}
    return response(mock.ai.aiComprehensiveRefreshMock);
  },

  /**
   * ?? AI ???????????? / ???????? hazardCode ????
   * ?? TasksPage/AnalyticsPage/WarningsPage ???? AI ??????
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
        // v1.3.0 ?? ai_comprehensive_score ??????????
        aiDisasterType: ai?.disasterType ?? null,
        aiDisasterTypeSource: ai?.disasterTypeSource ?? null,
        aiConcernLabel: ai?.concernLabel ?? null,
        aiDeviceAvailable: ai?.deviceAvailable ?? null,
      };
    });
  },

  // ----------------------- AI ?????? -----------------------

  /**
   * ?? AI ???????? healthScore ??????????
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

  /** ???? AI ?????? */
  getAiDeviceHealth: (deviceCode) => {
    if (USE_BACKEND)
      {return request(`/ai/device-health?deviceCode=${encodeURIComponent(deviceCode)}`);}
    return response(null);
  },

  /** ?????????? AI ?????? */
  refreshAiDeviceHealth: () => {
    if (USE_BACKEND)
      {return request("/ai/device-health/refresh", { method: "POST" });}
    return response(mock.ai.aiDeviceHealthRefreshMock);
  },

  /**
   * ?? AI ?????????????? deviceCode ????
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
  // v1.7.0 ????????
  // ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===

  // ---------- 3.3 ???????? ----------
  /**
   * ??"????"?????????????
   * @returns {Promise<{items: Array<{id, hazardCode, name, type, level, confidence, aiScore, reason, discoveredAt, status, assignee}>, total: number, pending: number}>}
   */
  getConfirmationQueue: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/confirmation/queue${withQuery(params)}`);
      return data ?? { items: [], total: 0, pending: 0 };
    }
    return response(mock.workflow.getConfirmationQueueMock());
  },

  /** ????????????????????????GET /api/confirmation/stats? */
  getConfirmationQueueStats: async () => {
    if (USE_BACKEND) {return request("/confirmation/stats");}
    return response(mock.workflow.confirmationQueueStatsMock);
  },

  /** ????????assignee ???????? */
  claimConfirmation: async (id, operator) => {
    requireBackend("????????");
    return request(`/confirmation/queue/${id}/claim`, {
      method: "POST",
      body: JSON.stringify({ operator }),
    });
  },

  /** ???????? */
  transferConfirmation: async (id, toUser, reason) => {
    requireBackend("????????");
    return request(`/confirmation/queue/${id}/transfer`, {
      method: "POST",
      body: JSON.stringify({ toUser, reason }),
    });
  },

  /** ??????????????????? hazard_point?? */
  confirmHazard: async (id, payload = {}) => {
    requireBackend("??????");
    return request(`/confirmation/queue/${id}/confirm`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  /** ?????????? */
  rejectHazard: async (id, payload = {}) => {
    requireBackend("??????");
    return request(`/confirmation/queue/${id}/reject`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  // ---------- 4.2 ???? ----------
  createWarning: async (payload) => {
    requireBackend("????");
    return request("/warnings", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  /** ???????????? */
  getWarningStats: async () => {
    if (USE_BACKEND) {return request("/warnings/stats");}
    return response(mock.workflow.warningStatsMock);
  },

  // ---------- 6.3 ???? ----------
  getResources: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/resources${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.operations.getResourcesMock());
  },

  assignResource: async (payload) => {
    requireBackend("????");
    return request(`/resources/assign`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  // ---------- 5.5 ?????? ----------
  getDataSources: async () => {
    if (USE_BACKEND) {return request("/datasources");}
    return response(mock.operations.getDataSourcesMock());
  },

  toggleDataSource: async (code, enabled) => {
    requireBackend("??????");
    return request(`/datasources/${encodeURIComponent(code)}/toggle`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    });
  },

  // ---------- 5.6 ???????P3-1?? ----------
  /**
   * ????????????????????????????????????????????????
   * ???????GET /sync/stats?????? requireBackend ????
   */
  getSyncStats: async () => {
    if (USE_BACKEND) {return request("/sync/stats");}
    requireBackend("??????");
  },

  /**
   * ??????????????????????????????????
   * ???????GET /sync/tasks??
   */
  getSyncTasks: async () => {
    if (USE_BACKEND) {return request("/sync/tasks");}
    requireBackend("??????");
  },

  /**
   * ????????
   * ???????GET /sync/history?from=&to=&task=&status=??
   */
  getSyncHistory: async (params = {}) => {
    if (USE_BACKEND) {return request("/sync/history", { params });}
    requireBackend("??????");
  },

  /**
   * ???????????????? + ??????????
   * ???????GET /sync/integrity??
   */
  getIntegrityReport: async () => {
    if (USE_BACKEND) {return request("/sync/integrity");}
    requireBackend("????????");
  },

  /**
   * ?????????????????????????
   * ???????GET /sync/anomalies??
   */
  getSyncAnomalies: async () => {
    if (USE_BACKEND) {return request("/sync/anomalies");}
    requireBackend("????????");
  },

  /**
   * ??????????
   * ???????POST /sync/tasks/:id/trigger??
   */
  triggerSyncTask: async (id) => {
    requireBackend("??????");
    return request(`/sync/tasks/${encodeURIComponent(id)}/trigger`, {
      method: "POST",
    });
  },

  /**
   * ????????
   * ???????POST /sync/tasks/:id/pause??
   */
  pauseSyncTask: async (id) => {
    requireBackend("??????");
    return request(`/sync/tasks/${encodeURIComponent(id)}/pause`, {
      method: "POST",
    });
  },

  /**
   * ??????????
   * ???????POST /sync/anomalies/:id/ignore??
   */
  ignoreAnomaly: async (id, reason) => {
    requireBackend("??????");
    return request(`/sync/anomalies/${encodeURIComponent(id)}/ignore`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },

  testDataSource: async (code) => {
    requireBackend("????????");
    return request(`/datasources/${encodeURIComponent(code)}/test`, {
      method: "POST",
    });
  },

  // ---------- 8.1 ???? ----------
  getUsers: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/users${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.system.getUsersMock());
  },

  createUser: async (payload) => {
    requireBackend("????");
    return request("/users", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  updateUser: async (id, payload) => {
    requireBackend("????");
    return request(`/users/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },

  deleteUser: async (id) => {
    requireBackend("????");
    return request(`/users/${id}`, { method: "DELETE" });
  },

  getRoles: async () => {
    if (USE_BACKEND) {return request("/roles");}
    return response(mock.system.getRolesMock());
  },

  // ---------- 8.3 ???? ----------
  getAuditLogs: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/audit/logs${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.system.getAuditLogsMock());
  },

  // ---------- 8.4 ???? ----------
  getSystemMonitor: async () => {
    if (USE_BACKEND) {return request("/system/monitor");}
    return response(mock.system.getSystemMonitorMock());
  },

  // ---------- 9.1 ???? ----------
  getMessages: async (params = {}) => {
    if (USE_BACKEND) {
      const data = await request(`/messages${withQuery(params)}`);
      return normalizePaged(data, { items: [] });
    }
    return response(mock.workflow.getMessagesMock());
  },

  /** ???????????? */
  getMessageStats: async () => {
    if (USE_BACKEND) {return request("/messages/stats");}
    return response(mock.workflow.messageStatsMock);
  },

  markMessageRead: async (id) => {
    requireBackend("????");
    return request(`/messages/${id}/read`, { method: "POST" });
  },

  markAllMessagesRead: async () => {
    requireBackend("??????");
    return request(`/messages/read-all`, { method: "POST" });
  },

  // ---------- ???sentinel ?????? ----------
  getSentinelCoverage: async (bbox) => {
    if (USE_BACKEND) {return request(`/sentinel/coverage${bbox ? `?bbox=${encodeURIComponent(bbox)}` : ""}`);}
    return response(mock.analytics.sentinelCoverageMock);
  },

  // ---------- v1.7.x???????????? / ?? / ?? / ???? ----------
  /**
   * ??????????CPU/GPU/??/??/????/????
   * ???????? taskId + ????????? mock??????????????
   * @param {string|number} id ?? ID
   * @returns {Promise<{status: 'RUNNING'|'SUCCESS'|'FAILED'|'PENDING'|'PAUSED'|'TERMINATED'|'TIMEOUT', cpu: number, gpu: number, memMB: number, startedAt: string, finishedAt?: string, duration: number, retries: number, throughput: number}>}
   */
  getTaskRuntime: async (id) => {
    if (USE_BACKEND) {return request(`/tasks/${id}/runtime`);}
    return response(mock.ai.getTaskRuntimeMock(id));
  },

  /**
   * ??????????????`since` ?????? ISO ??????
   * ?????? taskId ?? 8-20 ???? mock?? ts ????
   * @param {string|number} id ?? ID
   * @param {{since?: string}} params
   * @returns {Promise<{entries: Array<{ts: string, level: 'INFO'|'WARN'|'ERR'|'DEBUG', message: string}>, cursor: string}>}
   */
  getTaskExecutionLog: async (id, params = {}) => {
    if (USE_BACKEND) {
      // v1.9.1 P0?????? /api/tasks/{id}/execution-log?? .log ?????????
      const data = await request(`/tasks/${id}/execution-log${withQuery(params)}`);
      return data ?? { entries: [], cursor: new Date().toISOString() };
    }
    return response(mock.ai.getTaskExecutionLogMock(id));
  },

  /**
   * ????????????????????
   * ?????? requireBackend ???????????UI ???? toast ????
   * @param {string|number} id ?? ID
   */
  retryTask: async (id) => {
    requireBackend("????");
    return request(`/tasks/${id}/retry`, { method: "POST" });
  },

  /**
   * ????????????????
   * ?????? requireBackend ???UI ????????????????
   * @param {string|number} id ?? ID
   */
  terminateTask: async (id) => {
    requireBackend("????");
    return request(`/tasks/${id}/terminate`, { method: "POST" });
  },

  // ?????????????????????????????????????????????????? ???? / ???? ??????????????????????????????????????????????????
  // ???????????????????? /todo/* ?? /approvals/* ??
  // ?????? throw BACKEND_REQUIRED???? try/catch ????????????????

  /**
   * ?????? 4 ????????
   * @returns {Promise<{confirmation:number, approval:number, pendingWarnings:number, openEmergency:number}>}
   */
  getTodoSummary: async () => {
    if (!USE_BACKEND) {requireBackend("????????");}
    return request("/todo/summary");
  },

  /**
   * ????????????????
   * ?????? seedState ?? hazards ?? N ??????????
   */
  listMyConfirmations: async () => {
    if (USE_BACKEND) {return request("/todo/confirmations");}
    return response(mock.workflow.listMyConfirmationsMock());
  },

  /**
   * ??????
   * ?????? seedState.warnings ?? status=???? ????????
   */
  listMyApprovals: async () => {
    if (USE_BACKEND) {return request("/todo/approvals");}
    return response(mock.workflow.listMyApprovalsMock());
  },

  /**
   * ????????
   */
  listMyPendingWarnings: async () => {
    if (USE_BACKEND) {return request("/todo/warnings");}
    return response(mock.workflow.listMyPendingWarningsMock());
  },

  /**
   * ????????????????????????
   */
  listMyOpenTasks: async () => {
    if (USE_BACKEND) {return request("/todo/tasks");}
    return response(mock.workflow.listMyOpenTasksMock());
  },

  // ?????????????????????????????????????????????????? ???? ??????????????????????????????????????????????????

  /**
   * ????????????
   * ???????? requireBackend ???UI ??????????????????
   */
  listApprovals: async (filters = {}) => {
    if (!USE_BACKEND) {requireBackend("??????");}
    return request(`/approvals${withQuery(filters)}`);
  },

  /**
   * ???????????????????????????????? + 14 ????????
   * ?????????????????
   */
  getApprovalStats: async () => {
    if (!USE_BACKEND) {requireBackend("??????");}
    return request("/approvals/stats");
  },

  /**
   * ????????? / ??????
   * @param {string|number} id
   * @param {{decision:'approve'|'reject', comment:string}} payload
   */
  decideApproval: async (id, payload) => {
    if (!USE_BACKEND) {requireBackend("??????");}
    return request(`/approvals/${id}/decide`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  // ---------- v1.7.x???????? ----------
  /**
   * ????????????????? / ????? / ???? / ????????
   * ?????????????? UI ??????????????
   * @param {string|number} id ?? ID
   */
  getDeliverySummary: async (id) => {
    if (USE_BACKEND) {return request(`/warnings/${id}/delivery/summary`);}
    return response(mock.empty.emptyDeliverySummary(id));
  },

  /**
   * ???? / ?????????????????
   * ???????? records????????????
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
   * ?????? / ?????????????????????
   * @param {string|number} id ?? ID
   * @param {{channel: string, targetIds?: Array<string|number>}} payload
   */
  redeliver: async (id, payload = {}) => {
    requireBackend("????");
    return request(`/warnings/${id}/delivery/redeliver`, {
      method: "POST",
      body: JSON.stringify(payload ?? {}),
    });
  },

  // ---------- v1.7.x???? / ?????? ----------
  /**
   * ?? / ?????????????????? seedState ??????????
   * @param {{kind?: string, region?: string}} params
   */
  getZoneSummary: async ({ kind, region } = {}) => {
    if (USE_BACKEND) {
      return request(`/zones/summary${withQuery({ kind, region })}`);
    }
    return response(mock.analytics.getZoneSummaryMock({ kind, region }));
  },

  /**
   * ?? / ?????? 14 ????????????? kind ??????????
   * @param {{kind?: string, region?: string}} params
   */
  getZoneRiskTrend: async ({ kind, region } = {}) => {
    if (USE_BACKEND) {
      return request(`/zones/risk-trend${withQuery({ kind, region })}`);
    }
    return response(mock.analytics.getZoneRiskTrendMock({ kind, region }));
  },

  // ---------- v1 PRD 2.5: AI ???? ----------
  /**
   * ??????????????????????
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
   * ??????????????????????????????
   * @param {string|number} versionId
   */
  getModelDetail: async (versionId) => {
    if (USE_BACKEND) {
      return request(`/ai/models/${versionId}`);
    }
    return response(mock.system.modelDetailMock);
  },

  /**
   * ????????????????????????????????? F1????
   */
  getModelFamilyStats: async () => {
    if (USE_BACKEND) {
      return request("/ai/models/stats");
    }
    return response(mock.ai.modelFamilyStatsMock);
  },

  /**
   * ????????
   */
  getModelTrainingJobs: async () => {
    if (USE_BACKEND) {
      return request("/ai/models/training-jobs");
    }
    return response(mock.ai.trainingJobsMock);
  },

  /**
   * ????????
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
   * ????????
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
   * ????????
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
   * ?????????? since ??????????
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
   * ??????????????????
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
   * ????????????????
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

  // ===================== 5.3 ?????? =====================
  /**
   * ??????????
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

  // ===================== 6.4 ???? =====================
  /**
   * ??????
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

  // ===================== 2.4 AI ???? =====================
  /**
   * AI ????????
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

  // ---------- OTA ?????? ----------
  /**
   * ????????
   * @param {{deviceType?: string, keyword?: string}} filters
   */
  listFirmware: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/ota/firmwares${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * ??????multipart????
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
   * ????????
   * @param {string|number} firmwareId
   */
  deleteFirmware: async (firmwareId) => {
    if (USE_BACKEND) {
      return request(`/ota/firmwares/${firmwareId}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * ?????????? status ???????
   * @param {{status?: string, deviceId?: string}} filters
   */
  listOtaTasks: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * ??????????
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
   * ????????
   * @param {string|number} taskId
   */
  cancelOtaTask: async (taskId) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks/${taskId}/cancel`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  /**
   * ????????
   * @param {string|number} taskId
   */
  retryOtaTask: async (taskId) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks/${taskId}/retry`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  /**
   * ??????????????????????
   * @param {string|number} taskId
   */
  getOtaTaskDetail: async (taskId) => {
    if (USE_BACKEND) {
      return request(`/ota/tasks/${taskId}`);
    }
    return response(null);
  },

  // ---------- ???? ----------
  /**
   * ??????????/??/??/?????????
   * @param {{hazardType?: string, level?: string, channel?: string, status?: string}} filters
   */
  listNoticeTemplates: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/notice-templates${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * ????????
   * @param {string|number} id
   */
  getNoticeTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/notice-templates/${id}`);
    }
    return response(null);
  },

  /**
   * ????????????payload.id ???? PUT????
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
   * ????????
   * @param {string|number} id
   */
  deleteNoticeTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/notice-templates/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * ???????????????
   * @param {string|number} id
   */
  toggleNoticeTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/notice-templates/${id}/toggle`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  // ---------- ??????? ----------
  /**
   * ????????
   * @param {{hazardType?: string, level?: string, status?: string}} filters
   */
  listPlans: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/plans${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * ???????????????????????
   * @param {string|number} planId
   */
  getPlanDetail: async (planId) => {
    if (USE_BACKEND) {
      return request(`/plans/${planId}`);
    }
    return response(null);
  },

  /**
   * ????????payload.id ???? PUT????
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
   * ??????
   * @param {string|number} planId
   */
  deletePlan: async (planId) => {
    if (USE_BACKEND) {
      return request(`/plans/${planId}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * ?????????? timeline ?? 1 ??????????
   * @param {string|number} planId
   */
  simulatePlanLaunch: async (planId) => {
    if (USE_BACKEND) {
      return request(`/plans/${planId}/simulate-launch`, { method: "POST" });
    }
    return response(mock.empty.ok);
  },

  /**
   * ??????????
   */
  listPlanLaunches: async () => {
    if (USE_BACKEND) {
      return request("/plans/launches");
    }
    return response(mock.empty.emptyPaged);
  },

  // ===================== 8.5 ?????? =====================
  /**
   * ????????????????????????????
   * @param {{type?: string, source?: string, status?: string, page?: number, size?: number}} filters
   */
  listBackups: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/backup/records${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * ????????????????
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
   * ???????????????? verifyStatus ????????
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
   * ????????
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
   * ????????
   * @param {string|number} id
   */
  deleteBackup: async (id) => {
    if (USE_BACKEND) {
      return request(`/backup/records/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * ????????
   */
  listBackupStrategies: async () => {
    if (USE_BACKEND) {
      return request("/backup/strategies");
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * ??????????payload.id ???? PUT????
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
   * ????????
   * @param {string|number} id
   */
  deleteBackupStrategy: async (id) => {
    if (USE_BACKEND) {
      return request(`/backup/strategies/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * ????????????????
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
   * ????????
   */
  listRestoreHistory: async () => {
    if (USE_BACKEND) {
      return request("/backup/restores");
    }
    return response(mock.empty.emptyPaged);
  },

  // ===================== ?????? =====================
  /**
   * ??????????????????2FA????????????
   */
  getAccountSecurity: async () => {
    requireBackend("getAccountSecurity");
    return request("/account/security");
  },

  /**
   * ??/??????????????
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
   * ???????????????????????????????
   */
  regenerateBackupCodes: async () => {
    requireBackend("regenerateBackupCodes");
    return request("/account/security/backup-codes/regenerate", {
      method: "POST",
    });
  },

  /**
   * ????????
   * @param {{page?: number, size?: number, status?: string}} filters
   */
  listLoginHistory: async (filters = {}) => {
    requireBackend("listLoginHistory");
    return request(`/account/login-history${withQuery(filters)}`);
  },

  /**
   * ????????????
   */
  listActiveSessions: async () => {
    requireBackend("listActiveSessions");
    return request("/account/sessions");
  },

  /**
   * ????????
   * @param {string|number} id
   */
  revokeSession: async (id) => {
    requireBackend("revokeSession");
    return request(`/account/sessions/${id}`, { method: "DELETE" });
  },

  /**
   * ??????????
   * @param {{page?: number, size?: number, type?: string}} filters
   */
  listAccountAudit: async (filters = {}) => {
    requireBackend("listAccountAudit");
    return request(`/account/audit${withQuery(filters)}`);
  },

  // ===================== ???? =====================
  /**
   * ???????????? / ????????
   * @param {{category?: 'operation'|'supervision'|'custom', keyword?: string, page?: number, size?: number}} filters
   */
  listReportTemplates: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/reports/templates${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * ??????????????
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
   * ????????
   * @param {string|number} id
   */
  deleteReportTemplate: async (id) => {
    if (USE_BACKEND) {
      return request(`/reports/templates/${id}`, { method: "DELETE" });
    }
    return response(mock.empty.ok);
  },

  /**
   * ??????????????????
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
   * ?? / ??????????
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
   * ?????????????????????????????
   * @param {{templateId?: string|number, status?: 'pending'|'running'|'success'|'failed', trigger?: 'manual'|'scheduled'|'event', range?: string, page?: number, size?: number}} filters
   */
  listReportHistory: async (filters = {}) => {
    if (USE_BACKEND) {
      return request(`/reports/history${withQuery(filters)}`);
    }
    return response(mock.empty.emptyPaged);
  },

  /**
   * ????????????
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
   * ????????????
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

  // ===================== ?????? =====================
  /**
   * ??????????????????????
   * - base / compare???? "24h" / "7d" / "30d" / "custom:2025-09-01T00:00:00Z,2025-09-07T23:59:59Z"
   * - mode???? / ?? / ?????? KPI ????????
   * ?????????? `/analytics/compare`?????? base+compare ????????????????
   * ???????????????????????? seedState ?? incidents/warnings/hazards/devices ??????
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

  // ===================== ?????? =====================
  /**
   * ???? KPI ????????? / ???? / ???? / ??????????
   */
  getCatalogSummary: async () => {
    requireBackend("getCatalogSummary");
    return request("/catalog/summary");
  },

  /**
   * ???????????????
   * @param {{type?: string, category?: string, source?: string, keyword?: string, page?: number, size?: number}} filters
   */
  listDataAssets: async (filters = {}) => {
    requireBackend("listDataAssets");
    return request(`/catalog/assets${withQuery(filters)}`);
  },

  /**
   * ???????? / ???? / ?? / ??????
   * @param {string|number} id
   */
  getDataAssetDetail: async (id) => {
    requireBackend("getDataAssetDetail");
    return request(`/catalog/assets/${id}`);
  },

  /**
   * ????????????
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
   * ????????
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
   * ???????? 7/30 ??????????
   */
  listCatalogUsage: async () => {
    requireBackend("listCatalogUsage");
    return request("/catalog/usage");
  },

  /**
   * ??????????
   * @param {string|number} id
   */
  subscribeAssetChange: async (id) => {
    requireBackend("subscribeAssetChange");
    return request(`/catalog/assets/${id}/subscribe-change`, {
      method: "POST",
    });
  },

  // ==========================================================================
  // P0 ??????
  // ==========================================================================

  // ---------- P0-1 ???? ----------
  /** ?????????????????? */
  getObstacleAreas: async () => {
    if (USE_BACKEND) {return request("/path-planning/obstacles");}
    return response(mock.runtime.getObstacleAreasMock());
  },
  /** ???????????? */
  planRoutes: async (payload = {}) => {
    if (USE_BACKEND) {
      return request("/path-planning/calculate", {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response(mock.runtime.planRoutesWithObstaclesMock(payload));
  },
  /** ?????? GeoJSON */
  exportRouteGeoJson: async (route) => {
    if (USE_BACKEND) {
      return request("/path-planning/export", {
        method: "POST",
        body: JSON.stringify(route),
      });
    }
    return response({ geojson: mock.runtime.exportRouteGeoJsonMock(route) });
  },

  // ---------- P0-2 ???? ----------
  /** ???????? */
  previewWarningResolve: async (warning) => {
    if (USE_BACKEND) {
      return request(`/warnings/${warning?.id ?? warning?.warningId}/resolve/preview`);
    }
    return response(mock.runtime.previewWarningResolveMock(warning));
  },
  /** ???????????? */
  getWarningResolveScope: async (id) => {
    if (USE_BACKEND) {return request(`/warnings/${id}/resolve/scope`);}
    return response({ scopes: [
      { value: "ALL", label: "??" },
      { value: "????", label: "????" },
      { value: "????", label: "????" },
      { value: "????", label: "????" },
    ] });
  },
  /** ?????????? resolve ?? + ??/??/???? */
  resolveWarning: async (id, payload = {}) => {
    requireBackend("????");
    return request(`/warnings/${id}/resolve`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  // ---------- P0-3 ???? ----------
  getProfile: async () => {
    if (USE_BACKEND) {return request("/profile");}
    return response(mock.system.getUsersMock?.()?.[0] ?? {
      id: "u-admin", username: "admin", name: "????",
      phone: "138****8888", email: "admin@disasterguard.cn", department: "?????????",
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
    const err = new Error("??????");
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

  // ---------- P0-4 ?? ----------
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

  // ---------- P0-5 ?????? ----------
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

  // ===X= ZoneDashboardPage ???? shim???? KPI / ?? / Top5 / ???? / ??????? ===X=
  /** @deprecated ?? getZoneKpi/getZoneTrend/getZoneTopHazards ???? */
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
  /** ???????? */
  getZoneRelatedWarningsBy: (params) => dashboardApi.getZoneRelatedWarnings(params),
  getZoneRelatedEmergenciesBy: (params) => dashboardApi.getZoneRelatedEmergencies(params),

  // =====================================================================
  // v1.7.0+ P0 ???????Unified Event Center??
  // ---------------------------------------------------------------------
  // ???backend/src/main/java/cn/disasterguard/event/
  //   EventStateMachineConfig.java (7 ???? ? 10 ????)
  //   EventStateController.java   (GET /api/event/active | /stats | /{code} | POST /{code}/transition)
  //   UnifiedEventEntity.java     (?? hazard/warning/emergency ????????)
  //
  // ?????? hazard / warning / emergency ????????????????
  // ?????AI ?? ?? ???? ?? ???? ?? ??????? ?? ????????
  // =====================================================================

  /**
   * ?????????? updated_at DESC ??????
   * @param {Object} params
   *   - type:      ?????HAZARD / WARNING / EMERGENCY ???? ??????????
   *   - state:     ??????????? (DETECTED/CONFIRMING/CONFIRMED/WARNING_ISSUED/EMERGENCY_ACTIVE/CLOSED/REJECTED)
   *   - source:    ?????AI_AUTO / RULE_ENGINE / MANUAL
   *   - keyword:   ????????? eventCode/title
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
   * ??????KPI ????????
   * @returns {Promise<{DETECTED, CONFIRMING, CONFIRMED, WARNING_ISSUED, EMERGENCY_ACTIVE, CLOSED, REJECTED, total, active}>}
   */
  getUnifiedEventStats: async () => {
    if (USE_BACKEND) {
      const data = await request("/event/stats");
      return data ?? {};
    }
    return response(mock.workflow.unifiedEventsStatsMock);
  },

  /**
   * ???????? hazard/warning/emergency ????????
   */
  getUnifiedEventDetail: async (code) => {
    if (USE_BACKEND) {
      return request(`/event/${encodeURIComponent(code)}`);
    }
    return response(mock.workflow.getUnifiedEventDetailMock(code));
  },

  /**
   * ?????????CONFIRM / REJECT / ISSUE_WARNING / TRIGGER_EMERGENCY / CLOSE ????
   * ??????? Spring StateMachine ??????????
   * @param {string} code      ????
   * @param {Object} payload
   *   - trigger:  EventTrigger ?????? (CONFIRM/REJECT/DISMISS/ISSUE_WARNING/TRIGGER_EMERGENCY/CLOSE/REOPEN/MANUAL_REPORT/START_CONFIRM/AI_DETECT)
   *   - operator: ????
   *   - reason:   ??????/??????
   */
  transitionUnifiedEvent: async (code, payload = {}) => {
    requireBackend("???????????");
    return request(`/event/${encodeURIComponent(code)}/transition`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  /**
   * ????????????????????????
   * ?????????????? EventStateMachineConfig ????????????
   * @param {string} code
   */
  getUnifiedEventAvailableTriggers: async (code) => {
    // ???????? ???? ?? detail ?????? state ?????? triggers
    const detail = await dashboardApi.getUnifiedEventDetail(code);
    if (!detail) return [];
    return dashboardApi.computeAvailableTriggers(detail.state);
  },

  /**
   * ?????? EventState ?????? EventTrigger??
   * ?? EventStateMachineConfig.java ??????????
   */
  computeAvailableTriggers: (currentState) => {
    const MAP = {
      DETECTED: [
        { trigger: "AI_DETECT", label: "AI ??", tone: "blue" },
        { trigger: "MANUAL_REPORT", label: "????", tone: "blue" },
        { trigger: "DISMISS", label: "????", tone: "gray" },
      ],
      CONFIRMING: [
        { trigger: "CONFIRM", label: "????", tone: "green" },
        { trigger: "REJECT", label: "??", tone: "red" },
        { trigger: "DISMISS", label: "????", tone: "gray" },
      ],
      CONFIRMED: [
        { trigger: "ISSUE_WARNING", label: "????", tone: "orange" },
        { trigger: "TRIGGER_EMERGENCY", label: "??????", tone: "red" },
        { trigger: "DISMISS", label: "????", tone: "gray" },
      ],
      WARNING_ISSUED: [
        { trigger: "TRIGGER_EMERGENCY", label: "??????", tone: "red" },
        { trigger: "CLOSE", label: "????", tone: "green" },
      ],
      EMERGENCY_ACTIVE: [
        { trigger: "CLOSE", label: "???????", tone: "green" },
      ],
      CLOSED: [
        { trigger: "REOPEN", label: "????", tone: "blue" },
      ],
      REJECTED: [
        { trigger: "REOPEN", label: "????", tone: "blue" },
      ],
    };
    return MAP[currentState] ?? [];
  },

  /**
   * ????????????????? 7 ??????? + ????????
   * ?? EventStateMachineConfig.java ????????
   */
  getStateMachineGraph: () => ({
    states: [
      { id: "DETECTED",          label: "????",      color: "#4f8f99", icon: "Eye" },
      { id: "CONFIRMING",        label: "????",      color: "#e0a050", icon: "ClipboardCheck" },
      { id: "CONFIRMED",         label: "????",      color: "#638fc2", icon: "CheckCircle2" },
      { id: "WARNING_ISSUED",    label: "????",    color: "#d88561", icon: "Bell" },
      { id: "EMERGENCY_ACTIVE",  label: "???????",    color: "#e06060", icon: "Truck" },
      { id: "CLOSED",            label: "????",      color: "#9baead", icon: "Archive" },
      { id: "REJECTED",          label: "????",      color: "#7a8d89", icon: "XCircle" },
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

  // ==================== P1 ???? + ???? ====================

  /**
   * ???????? DB ??????????
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
   * ?????????? DB ??????
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
   * ?????????? hazardCode + DB ????????
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
   * ?????????????? / ???? / ????????
   * GET /api/ai/disaster-assessment/status
   */
  getDisasterAssessmentStatus: async () => {
    if (USE_BACKEND) {
      return request("/ai/disaster-assessment/status");
    }
    return response(mock.ai.disasterAssessmentStatusMock);
  },

  /**
   * ???????????????????? + ???????/????????
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
};
