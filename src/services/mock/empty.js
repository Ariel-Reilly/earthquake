/**
 * 零值 / 空集 占位返回 —— 离线模式下写操作或未对接后端的接口的安全回退。
 *
 * 设计原则：
 * 1. 列表类查询：返回 { records: [], total, page, size, pages } 标准分页壳
 * 2. 写操作：返回 { ok: true }（mock 永远不真正失败）
 * 3. 单条详情：返回 null（让 UI 走"暂无数据"分支）
 * 4. 计数类：返回 { unread/pending/... } 显式 0 状态
 *
 * 这些常量是为了让 dashboardApi.js 业务方法只写一句 `return empty.xxx()`，
 * 把"离线回退骨架"集中到本文件统一维护。
 */

// ---------------------- 标准分页壳 ----------------------

export const emptyPaged = {
  records: [],
  total: 0,
  page: 1,
  size: 20,
  pages: 1,
};

export function paged(size = 20) {
  return { records: [], total: 0, page: 1, size, pages: 1 };
}

export function emptyList() {
  return [];
}

// ---------------------- 写操作成功壳 ----------------------

export const ok = { ok: true };

// ---------------------- 单条详情（null 占位） ----------------------

export const emptyDetail = null;

// ---------------------- 任务执行日志空集 ----------------------

export const emptyTaskLog = { entries: [], cursor: null };

// ---------------------- 附件空集 ----------------------

export const emptyAttachments = [];

// ---------------------- 待办 / 审批 / 备份 / 报表等 占位 ----------------------

export const emptyTodoSummary = {
  confirmation: 0,
  approval: 0,
  pendingWarnings: 0,
  openEmergency: 0,
};

export const emptyApprovalStats = {
  total: 0,
  audited: 0,
  passRate: 0,
  avgCostMinutes: 0,
  dailySeries: [],
};

export const emptyCatalogSummary = {
  resourceTotal: 0,
  tableCount: 0,
  fieldCount: 0,
  updatedAt: null,
};

export const emptyDeliverySummary = (warningId) => ({
  warningId,
  shouldDeliver: 0,
  delivered: 0,
  read: 0,
  confirmed: 0,
  channels: [],
});

// ---------------------- 上传附件回执骨架 ----------------------

export function uploadReceipt({ businessType, businessCode, file, uploader }) {
  return {
    item: {
      id: Date.now(),
      businessType,
      businessCode,
      fileName: file?.name ?? "file",
      fileSize: file?.size ?? 0,
      fileType: file?.type ?? "",
      storagePath: "",
      fileUrl: "",
      uploader,
    },
  };
}

// ---------------------- GPS 围栏校验 demo 结果 ----------------------

export function gpsCheckFallback({ taskCode, hazardCode, lon, lat, allowedRadius }) {
  return {
    taskCode,
    hazardCode,
    distanceM: 32.5,
    allowedRadius: allowedRadius ?? 100,
    withinFence: true,
    fenceStatus: "已进入围栏",
    lon,
    lat,
    checkedAt: new Date().toISOString(),
  };
}

// ---------------------- 巡检结果回执 ----------------------

export function submitInspectionFallback(payload = {}) {
  const now = Date.now();
  return {
    id: now,
    recordCode: `IR-${now}`,
    taskCode: payload.taskCode,
    hazardCode: payload.hazardCode,
    inspector: payload.inspector,
    inspectAt: new Date().toISOString(),
    signInLon: payload.lon,
    signInLat: payload.lat,
    distanceM: 32.5,
    abnormalFlag: Boolean(payload.abnormalFlag),
    note: payload.note,
    photoIds: payload.photoIds ?? [],
    formData: payload.formData ?? {},
  };
}

// ---------------------- 巡检接口示例 payload ----------------------

export function inspectionExampleFallback() {
  return {
    description: "巡检上报接口示例 payload（离线 mock），用于前端联调参考",
    submitUrl: "/api/inspection/submit",
    method: "POST",
    sample: {
      taskCode: "TK-DEMO-001",
      hazardCode: "HZ-LA-001",
      lon: 118.5421,
      lat: 30.1284,
      allowedRadius: 200,
      inspector: "周小雨",
      abnormalFlag: false,
      note: "山体表面无异常，监测设备运行正常",
      photoIds: ["photo-001", "photo-002"],
      formData: {
        displacement_mm: 2.3,
        crack_width_mm: 0,
        rainfall_mm: 5.2,
      },
    },
  };
}

// ---------------------- 登录 demo 回执 ----------------------

export function loginMockUser(username, role = "admin") {
  return {
    token: `token_${btoa(`${username}:${Date.now()}`)}`,
    user: {
      name: "李晨曦",
      role,
      username,
    },
  };
}

// ---------------------- 登出回执 ----------------------

export const logoutOk = { success: true };
