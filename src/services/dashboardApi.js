const API_BASE =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8081/api";
const latency = 240;

import { clearAuthSession, getAuthToken } from "../stores/auth";

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
let seeded = false;

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

function createApiError(message, code = "API_ERROR") {
  const error = new Error(message);
  error.code = code;
  return error;
}

function redirectToLogin() {
  if (
    typeof window !== "undefined" &&
    !window.location.pathname.startsWith("/login")
  ) {
    window.location.replace("/login");
  }
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

async function request(path, options = {}) {
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
    clearAuthSession();
    redirectToLogin();
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

function response(data) {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(clone(data)), latency);
  });
}

function normalizePaged(payload, fallback = []) {
  if (!payload)
    return {
      records: fallback,
      total: fallback.length,
      page: 1,
      size: fallback.length,
      pages: 1,
    };
  if (Array.isArray(payload.records)) return payload;
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
      search.set(key, String(value));
  });
  const suffix = search.toString();
  return suffix ? `?${suffix}` : "";
}

const SEED_BASE = {
  overviewStats: [
    {
      label: "监测灾害点",
      value: "128",
      suffix: "处",
      change: "↑ 4.2% 较上月",
      color: "teal",
    },
    {
      label: "在线监测设备",
      value: "1,486",
      suffix: "台",
      change: "↑ 2.8% 较上月",
      color: "blue",
    },
    {
      label: "当前预警事件",
      value: "05",
      suffix: "起",
      change: "↑ 1 起 较昨日",
      color: "orange",
    },
    {
      label: "今日巡检完成率",
      value: "92.4",
      suffix: "%",
      change: "↑ 6.3% 较昨日",
      color: "purple",
    },
  ],
  hazardPoints: [
    {
      id: 1,
      code: "HZ-001",
      name: "青龙坡滑坡隐患点",
      type: "滑坡",
      level: "高风险",
      distance: "1.2 km",
      status: "位移 +12mm",
      owner: "龙安镇",
      lon: "107.21",
      lat: "30.61",
    },
    {
      id: 2,
      code: "HZ-002",
      name: "大岭村崩塌点",
      type: "崩塌",
      level: "关注",
      distance: "2.8 km",
      status: "裂缝 18mm",
      owner: "石桥镇",
      lon: "107.34",
      lat: "30.48",
    },
    {
      id: 3,
      code: "HZ-003",
      name: "白石沟泥石流",
      type: "泥石流",
      level: "正常",
      distance: "5.6 km",
      status: "雨量 8.2mm",
      owner: "清河镇",
      lon: "107.55",
      lat: "30.42",
    },
    {
      id: 4,
      code: "HZ-004",
      name: "云台山地裂缝",
      type: "地裂缝",
      level: "关注",
      distance: "3.3 km",
      status: "倾角 2.1°",
      owner: "清河镇",
      lon: "107.61",
      lat: "30.67",
    },
    {
      id: 5,
      code: "HZ-005",
      name: "石门沟滑坡点",
      type: "滑坡",
      level: "正常",
      distance: "4.1 km",
      status: "位移稳定",
      owner: "龙安镇",
      lon: "107.14",
      lat: "30.56",
    },
  ],
  tasks: [
    {
      id: 1,
      name: "雨后重点灾害点复查",
      type: "雨后复查",
      owner: "王建国",
      due: "18:00",
      progress: 92,
      status: "执行中",
      hazardId: 1,
      hazardCode: "HZ-001",
      hazardLon: 107.21,
      hazardLat: 30.61,
    },
    {
      id: 2,
      name: "青龙坡高风险点加密巡检",
      type: "应急巡检",
      owner: "周小雨",
      due: "16:30",
      progress: 68,
      status: "待处理",
      hazardId: 1,
      hazardCode: "HZ-001",
      hazardLon: 107.21,
      hazardLat: 30.61,
    },
    {
      id: 3,
      name: "云台山地裂缝专项排查",
      type: "专项排查",
      owner: "陈晓峰",
      due: "20:00",
      progress: 100,
      status: "已完成",
      hazardId: 4,
      hazardCode: "HZ-004",
      hazardLon: 107.61,
      hazardLat: 30.67,
    },
    {
      id: 4,
      name: "石门沟设备巡检与校准",
      type: "常规巡检",
      owner: "李敏",
      due: "17:20",
      progress: 54,
      status: "执行中",
      hazardId: 5,
      hazardCode: "HZ-005",
      hazardLon: 107.14,
      hazardLat: 30.56,
    },
  ],
  devices: [
    {
      id: 1,
      name: "GNSS-001",
      type: "GNSS位移",
      point: "青龙坡",
      hazardId: 1,
      state: "在线",
      battery: 92,
      signal: "强",
      last: "2分钟前",
      lon: 107.21,
      lat: 30.61,
    },
    {
      id: 2,
      name: "CRK-014",
      type: "裂缝计",
      point: "大岭村",
      hazardId: 2,
      state: "在线",
      battery: 88,
      signal: "强",
      last: "1分钟前",
      lon: 107.34,
      lat: 30.48,
    },
    {
      id: 3,
      name: "RAIN-052",
      type: "雨量计",
      point: "白石沟",
      hazardId: 3,
      state: "离线",
      battery: 34,
      signal: "弱",
      last: "18分钟前",
      lon: 107.55,
      lat: 30.42,
    },
    {
      id: 4,
      name: "INC-223",
      type: "倾角计",
      point: "云台山",
      hazardId: 4,
      state: "在线",
      battery: 76,
      signal: "中",
      last: "刚刚",
      lon: 107.61,
      lat: 30.67,
    },
  ],
  warnings: [
    {
      id: 1,
      bizNo: "W-20260813-001",
      level: "橙色预警",
      class: "orange",
      title: "青龙坡滑坡隐患点",
      desc: "位移速率连续 3 小时超过阈值",
      time: "10:42",
      point: "青龙坡 · 龙安镇",
      status: "待确认",
      version: 1,
      updatedAt: "2026-08-13T10:42:00+08:00",
      lon: 107.21,
      lat: 30.61,
      hazardId: 1,
    },
    {
      id: 2,
      bizNo: "W-20260813-002",
      level: "蓝色提醒",
      class: "blue",
      title: "云台山地裂缝",
      desc: "倾角变化达到关注值",
      time: "09:15",
      point: "云台山 · 清河镇",
      status: "待确认",
      version: 1,
      updatedAt: "2026-08-13T09:15:00+08:00",
      lon: 107.61,
      lat: 30.67,
      hazardId: 4,
    },
  ],
  incidents: [
    {
      id: 1,
      title: "青龙坡橙色预警",
      step: "专家复核中",
      time: "10:52",
      owner: "自然资源局值班组",
      warningId: 1,
      level: "橙色预警",
    },
  ],
};

/**
 * 演示数据回退控制：
 * - USE_BACKEND=false 时返回真实 seedState 数据（开发/演示模式）
 * - USE_BACKEND=true 时返回空数组，防止 API 返回 null 时误用 mock 数据
 */
function fallback(key) {
  if (USE_BACKEND) return [];
  return SEED_BASE[key] ?? [];
}

const seedState = SEED_BASE;

const SEED_ATTACHMENTS = {
  hazard: [
    { name: "青龙坡位移监测月报.pdf", size: "2.4 MB" },
    { name: "隐患点现场核查照片.zip", size: "18.6 MB" },
    { name: "群测群防巡查记录表.xlsx", size: "356 KB" },
  ],
  device: [
    { name: "GNSS 安装调试记录.pdf", size: "1.2 MB" },
    { name: "设备校准证书.png", size: "880 KB" },
    { name: "运维工单派发单.pdf", size: "512 KB" },
  ],
  task: [
    { name: "巡检任务书.pdf", size: "960 KB" },
    { name: "现场巡检照片.zip", size: "24.1 MB" },
    { name: "巡检结果签认表.xlsx", size: "428 KB" },
  ],
  warning: [
    { name: "预警处置单.pdf", size: "680 KB" },
    { name: "监测数据导出.xlsx", size: "1.6 MB" },
  ],
};

function findHazardById(id) {
  return (
    seedState.hazardPoints.find((entry) => String(entry.id) === String(id)) ??
    null
  );
}

function findTaskById(id) {
  return (
    seedState.tasks.find((entry) => String(entry.id) === String(id)) ?? null
  );
}

function findDeviceById(id) {
  return (
    seedState.devices.find((entry) => String(entry.id) === String(id)) ?? null
  );
}

function findWarningById(id) {
  return (
    seedState.warnings.find((entry) => String(entry.id) === String(id)) ?? null
  );
}

function tasksOfHazard(hazardId) {
  return seedState.tasks.filter(
    (task) => String(task.hazardId) === String(hazardId),
  );
}

function devicesOfHazard(hazardId) {
  return seedState.devices.filter(
    (device) => String(device.hazardId) === String(hazardId),
  );
}

function warningsOfHazard(hazardId) {
  return seedState.warnings.filter(
    (warning) => String(warning.hazardId) === String(hazardId),
  );
}

function incidentsOfWarning(warningId) {
  return seedState.incidents.filter(
    (incident) => String(incident.warningId) === String(warningId),
  );
}

function historyOfHazard(hazardId) {
  const history = [];
  const hazard = findHazardById(hazardId);
  if (hazard) {
    history.push({
      title: "隐患点建档",
      desc: `${hazard.type}隐患点纳入监测台账，风险等级 ${hazard.level}`,
      time: "2026-06-18",
    });
  }
  for (const task of tasksOfHazard(hazardId)) {
    history.push({
      title: `巡检任务「${task.name}」`,
      desc: `${task.status}，当前进度 ${task.progress}%`,
      time: "今天",
    });
  }
  for (const warning of warningsOfHazard(hazardId)) {
    history.push({
      title: `预警触发「${warning.level}」`,
      desc: warning.desc,
      time: warning.time,
    });
  }
  return history;
}

function linkedOfHazard(hazardId) {
  return [
    ...devicesOfHazard(hazardId).map((device) => ({
      type: "监测设备",
      label: device.name,
      path: `/devices/${device.id}`,
    })),
    ...tasksOfHazard(hazardId).map((task) => ({
      type: "巡检任务",
      label: task.name,
      path: `/tasks/${task.id}`,
    })),
    ...warningsOfHazard(hazardId).map((warning) => ({
      type: "预警事件",
      label: `${warning.level} · ${warning.title}`,
      path: `/warnings/${warning.id}`,
    })),
  ];
}

function historyOfDevice(deviceId) {
  const device = findDeviceById(deviceId);
  const history = [];
  if (device) {
    history.push({
      title: "设备接入",
      desc: `${device.name}（${device.type}）接入监测网络`,
      time: "2026-06-01",
    });
    history.push({
      title: "数据上报",
      desc: `${device.state === "离线" ? "离线，最后上报于" : "最近上报于"} ${device.last}，信号 ${device.signal}`,
      time: device.last,
    });
  }
  const hazard = device ? findHazardById(device.hazardId) : null;
  if (hazard) {
    history.push({
      title: `绑定灾害点「${hazard.name}」`,
      desc: `监测对象风险等级 ${hazard.level}`,
      time: "建档",
    });
  }
  return history;
}

function linkedOfDevice(deviceId) {
  const device = findDeviceById(deviceId);
  if (!device) return [];
  const links = [];
  const hazard = findHazardById(device.hazardId);
  if (hazard)
    links.push({
      type: "绑定灾害点",
      label: hazard.name,
      path: `/hazards/${hazard.id}`,
    });
  for (const warning of warningsOfHazard(device.hazardId)) {
    links.push({
      type: "关联预警",
      label: `${warning.level} · ${warning.title}`,
      path: `/warnings/${warning.id}`,
    });
  }
  for (const task of tasksOfHazard(device.hazardId)) {
    links.push({
      type: "关联任务",
      label: task.name,
      path: `/tasks/${task.id}`,
    });
  }
  return links;
}

function historyOfTask(taskId) {
  const task = findTaskById(taskId);
  if (!task) return [];
  return [
    {
      title: "任务创建",
      desc: `${task.name}（${task.type}）已创建，负责人 ${task.owner}`,
      time: "今天 08:00",
    },
    {
      title: "GPS 围栏校验",
      desc: `巡检员已到达任务点位附近，围栏校验通过`,
      time: "今天",
    },
    {
      title: "任务执行",
      desc: `${task.status}，完成进度 ${task.progress}%`,
      time: "实时",
    },
  ];
}

function linkedOfTask(taskId) {
  const task = findTaskById(taskId);
  if (!task) return [];
  const links = [];
  const hazard = findHazardById(task.hazardId);
  if (hazard)
    links.push({
      type: "关联灾害点",
      label: hazard.name,
      path: `/hazards/${hazard.id}`,
    });
  for (const warning of warningsOfHazard(task.hazardId)) {
    links.push({
      type: "关联预警",
      label: `${warning.level} · ${warning.title}`,
      path: `/warnings/${warning.id}`,
    });
  }
  for (const device of devicesOfHazard(task.hazardId)) {
    links.push({
      type: "监测设备",
      label: device.name,
      path: `/devices/${device.id}`,
    });
  }
  return links;
}

function historyOfWarning(warningId) {
  const warning = findWarningById(warningId);
  if (!warning) return [];
  const history = [
    { title: "预警触发", desc: warning.desc, time: warning.time },
  ];
  if (warning.status !== "待确认")
    history.push({
      title: `预警${warning.status === "已忽略" ? "忽略（误报）" : warning.status === "已解除" ? "解除（自动）" : warning.status}`,
      desc: warning.closeReason || "处置流程正常推进",
      time: "实时",
    });
  return history;
}

function linkedOfWarning(warningId) {
  const warning = findWarningById(warningId);
  if (!warning) return [];
  const links = [];
  const hazard = findHazardById(warning.hazardId);
  if (hazard)
    links.push({
      type: "触发灾害点",
      label: hazard.name,
      path: `/hazards/${hazard.id}`,
    });
  for (const device of devicesOfHazard(warning.hazardId)) {
    links.push({
      type: "监测设备",
      label: device.name,
      path: `/devices/${device.id}`,
    });
  }
  for (const task of tasksOfHazard(warning.hazardId)) {
    links.push({
      type: "关联任务",
      label: task.name,
      path: `/tasks/${task.id}`,
    });
  }
  for (const incident of incidentsOfWarning(warningId)) {
    links.push({ type: "应急事件", label: incident.title, path: `/emergency` });
  }
  return links;
}

const WARNING_LEVEL_RULES = [
  {
    match: (level) => /橙|红/.test(level),
    escalate: true,
    action: "自动启动应急响应",
  },
  {
    match: (level) => /黄|蓝/.test(level),
    escalate: false,
    action: "加密巡查，按需手动启动应急",
  },
  { match: () => true, escalate: false, action: "常规处置" },
];

function resolveWarningLevelRule(level = "") {
  return (
    WARNING_LEVEL_RULES.find((rule) => rule.match(level)) ??
    WARNING_LEVEL_RULES[WARNING_LEVEL_RULES.length - 1]
  );
}

function handleWarningTermination(id, reason) {
  if (!USE_BACKEND) {
    return closeWarningImplPath(id, reason);
  }
  return request(`/warnings/${id}/close`, {
    method: "POST",
    body: JSON.stringify({ reason }),
  });
}

function closeWarningImplPath(id, reason) {
  const current = seedState.warnings.find(
    (item) => String(item.id) === String(id),
  );
  const status =
    reason === "误报" ? "已忽略" : reason === "自动解除" ? "已解除" : "已关闭";
  const item = {
    ...(current ?? { id }),
    status,
    closeReason: reason,
    closedBy: "值班台",
    version: (current?.version ?? 0) + 1,
    updatedAt: new Date().toISOString(),
  };
  if (current) Object.assign(current, item);
  return response({ item });
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
    throw createApiError("请输入账号和密码");
  }

  return response({
    token: `token_${btoa(`${username}:${Date.now()}`)}`,
    user: {
      name: "李晨曦",
      role,
      username,
    },
  });
}

export async function logoutRequest() {
  if (USE_BACKEND) {
    return request("/auth/logout", { method: "POST" }).catch(() => null);
  }
  return response({ success: true });
}

export const dashboardApi = {
  getOverview: async () => {
    if (!USE_BACKEND) requireBackend("加载总览数据");
    return request("/dashboard/overview");
  },
  getHazards: async (params = {}) => {
    if (!USE_BACKEND) requireBackend("加载灾害点列表");
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
  getHazardsByViewport: async ({
    bbox,
    zoom,
    level,
    keyword,
    regionCode,
    type,
  } = {}) => {
    const params = { bbox, zoom, level, keyword, regionCode, type };
    if (!USE_BACKEND) requireBackend("加载灾害点视口数据");
    const data = await request(`/hazards/viewport${withQuery(params)}`);
    return Array.isArray(data)
      ? data
      : normalizePaged(data, fallback("hazardPoints")).records;
  },
  getHazardDetail: async (id) => {
    if (!USE_BACKEND) requireBackend("查看灾害点详情");
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
    if (USE_BACKEND) return request(`/tasks/${id}`);
    const item = findTaskById(id) ?? seedState.tasks[0];
    return response({
      item,
      history: historyOfTask(item.id),
      attachments: SEED_ATTACHMENTS.task,
      linked: linkedOfTask(item.id),
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
    if (USE_BACKEND) return request(`/devices/${id}`);
    const item = findDeviceById(id) ?? seedState.devices[0];
    return response({
      item,
      history: historyOfDevice(item.id),
      attachments: SEED_ATTACHMENTS.device,
      linked: linkedOfDevice(item.id),
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
    if (USE_BACKEND) return request(`/warnings/${id}`);
    const item = findWarningById(id) ?? seedState.warnings[0];
    return response({
      item,
      history: historyOfWarning(item.id),
      attachments: SEED_ATTACHMENTS.warning,
      linked: linkedOfWarning(item.id),
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
    return response({ incidents: seedState.incidents });
  },
  getPersonnel: async () => {
    // 生产模式：值守人员实时位置由移动端上报后端，此处仅透传（点 1、2）
    if (USE_BACKEND) {
      const data = await request(`/personnel/positions`);
      return Array.isArray(data) ? { list: data } : normalizePaged(data, []);
    }
    return response({ list: [] });
  },
  closeIncident: async (id) => {
    requireBackend("关闭应急事件");
    return request(`/emergency/${id}`, { method: "DELETE" });
  },
  exportOverviewReport: async () => {
    requireBackend("导出总览报表");
    return request("/dashboard/overview/export", { method: "POST" });
  },
  exportHazardReport: async () => {
    requireBackend("导出灾害点报表");
    return request("/hazards/export", { method: "POST" });
  },
  exportTaskReport: async () => {
    requireBackend("导出巡检任务报表");
    return request("/tasks/export", { method: "POST" });
  },
  exportDeviceReport: async () => {
    requireBackend("导出设备报表");
    return request("/devices/export", { method: "POST" });
  },
  exportWarningReport: async () => {
    requireBackend("导出预警报表");
    return request("/warnings/export", { method: "POST" });
  },
  exportEmergencyReport: async () => {
    requireBackend("导出应急报表");
    return request("/emergency/export", { method: "POST" });
  },
  getAnalytics: async () => {
    if (USE_BACKEND) return request("/dashboard/analytics");
    return response({
      hazardPoints: seedState.hazardPoints,
      overviewStats: seedState.overviewStats,
      charts: {
        riskTrend: [62, 68, 66, 71, 74, 79, 78],
        monthlyInspection: [54, 62, 67, 73, 81, 85, 88, 91, 93, 92, 94, 96],
        warningType: [
          { label: "位移异常", value: 42, color: "#e27d5b" },
          { label: "裂缝扩展", value: 27, color: "#d7a546" },
          { label: "雨量阈值", value: 18, color: "#5db486" },
          { label: "设备离线", value: 13, color: "#658fc1" },
        ],
        deviceTrend: [88, 89, 90, 92, 91, 93, 95, 94, 96, 97, 98, 98],
        heatmap: [
          { region: "龙安镇", intensity: 92, risk: "高" },
          { region: "石桥镇", intensity: 68, risk: "中" },
          { region: "清河镇", intensity: 54, risk: "中" },
          { region: "白石乡", intensity: 31, risk: "低" },
        ],
        riskZones: [
          {
            name: "龙安东片区",
            level: "高",
            count: 4,
            x: 18,
            y: 22,
            width: 28,
            height: 20,
            color: "#e27d5b",
          },
          {
            name: "石桥中片区",
            level: "中",
            count: 3,
            x: 48,
            y: 38,
            width: 24,
            height: 18,
            color: "#d7a546",
          },
          {
            name: "清河南片区",
            level: "中",
            count: 2,
            x: 72,
            y: 18,
            width: 20,
            height: 24,
            color: "#5db486",
          },
          {
            name: "白石北片区",
            level: "低",
            count: 1,
            x: 30,
            y: 60,
            width: 18,
            height: 18,
            color: "#658fc1",
          },
        ],
      },
    });
  },
  getModuleMeta: async () =>
    USE_BACKEND
      ? request("/dashboard/summary")
      : response({ item: { title: "地脉智巡", desc: "本地模拟模式" } }),
  uploadAttachment: async ({ businessType, businessCode, file, uploader }) => {
    if (!USE_BACKEND) {
      return response({
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
      });
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
      throw createApiError(payload?.message || "上传失败");
    return payload.data;
  },
  getAttachments: async ({ businessType, businessCode }) => {
    if (USE_BACKEND)
      return request(
        `/attachments?businessType=${encodeURIComponent(businessType)}&businessCode=${encodeURIComponent(businessCode)}`,
      );
    return response([]);
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
    return response({
      taskCode,
      hazardCode,
      distanceM: 32.5,
      allowedRadius: allowedRadius ?? 100,
      withinFence: true,
      fenceStatus: "已进入围栏",
      lon,
      lat,
      checkedAt: new Date().toISOString(),
    });
  },
  submitInspection: async (payload) => {
    if (USE_BACKEND) {
      return request("/inspection/submit", {
        method: "POST",
        body: JSON.stringify(payload),
      });
    }
    return response({
      id: Date.now(),
      recordCode: `IR-${Date.now()}`,
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
    });
  },
  getInspectionByTask: async (taskCode) => {
    if (USE_BACKEND)
      return request(`/inspection/task/${encodeURIComponent(taskCode)}`);
    return response([]);
  },
  getInspectionByHazard: async (hazardCode) => {
    if (USE_BACKEND)
      return request(`/inspection/hazard/${encodeURIComponent(hazardCode)}`);
    return response([]);
  },
};
