import { dashboardApi } from "./dashboardApi";
import { getAuthToken } from "../stores/auth";

const listeners = new Set();
let socket = null;
let reconnectTimer = null;
let pollingTimer = null;
let warningTimer = null;
let personnelTimer = null;
let personnelRouteTimer = null;
let wsUrl = "";
let pollingInterval = 15000;
let warningInterval = 10000;
let personnelInterval = 30000;

/**
 * 值守人员独立档案。真实系统中应来自人员管理模块 / 当日排班表，
 * 而非从设备点位生成。设备点位仅能关联"监测责任人"，不能推导动态值守力量。
 */
const PERSONNEL_ROSTER = [
  {
    id: "u-1001",
    name: "王强",
    role: "值班员",
    region: "龙安镇",
    phone: "138****2001",
    team: "综合巡查组",
    shift: "白班",
  },
  {
    id: "u-1002",
    name: "李敏",
    role: "群测群防员",
    region: "石桥镇",
    phone: "139****2032",
    team: "综合巡查组",
    shift: "白班",
  },
  {
    id: "u-1003",
    name: "周洋",
    role: "值班员",
    region: "清河镇",
    phone: "137****2077",
    team: "应急响应组",
    shift: "白班",
  },
  {
    id: "u-1004",
    name: "陈静",
    role: "群测群防员",
    region: "龙安县城区",
    phone: "136****2098",
    team: "应急响应组",
    shift: "白班",
  },
  {
    id: "u-1005",
    name: "黄敏",
    role: "值班员",
    region: "白石镇",
    phone: "135****2016",
    team: "综合巡查组",
    shift: "中班",
  },
  {
    id: "u-1006",
    name: "赵磊",
    role: "群测群防员",
    region: "云台镇",
    phone: "188****2044",
    team: "综合巡查组",
    shift: "中班",
  },
  {
    id: "u-1007",
    name: "刘洋",
    role: "值班员",
    region: "石门镇",
    phone: "189****2065",
    team: "应急响应组",
    shift: "中班",
  },
  {
    id: "u-1008",
    name: "宋倩",
    role: "群测群防员",
    region: "龙安镇",
    phone: "187****2089",
    team: "综合巡查组",
    shift: "中班",
  },
];

/** 人员实时位置：模拟模式为 false 时（生产）人员坐标由移动端上报后端，前端仅展示与轮询。 */
const USE_LIVE_PERSONNEL =
  String(import.meta.env.VITE_USE_PERSONNEL_LIVE ?? "false") === "true";

const state = {
  warnings: [],
  devices: [],
  personnel: [],
};

/** 全局数据版本号：轮询与 WebSocket 消息均携带版本，供订阅方做一致性比对 */
let globalVersion = 0;

function nextVersion() {
  globalVersion += 1;
  return globalVersion;
}

function emit(payload) {
  listeners.forEach((listener) => {
    try {
      listener({ ...payload, version: nextVersion() });
    } catch {
      // ignore listener errors
    }
  });
}

function normalizeWarning(item) {
  return {
    id: item.id,
    bizNo: item.bizNo ?? item.warningNo ?? `W-${String(item.id)}`,
    name: item.title ?? item.name,
    title: item.title ?? item.name,
    desc: item.desc ?? item.message ?? "",
    level: item.level ?? item.riskLevel ?? "预警",
    class:
      item.class ?? (String(item.level).includes("橙") ? "orange" : "blue"),
    point: item.point ?? item.owner ?? "",
    time: item.time ?? item.updatedAt ?? new Date().toISOString(),
    lon: item.lon,
    lat: item.lat,
    status: item.status ?? "待确认",
    closeReason: item.closeReason ?? "",
    closedBy: item.closedBy ?? "",
    incidentRef: item.incidentRef ?? "",
    /** 幂等字段：后端推送携带业务版本号，前端据此丢弃过期/重复消息 */
    version: Number(item.version ?? 0),
    updatedAt: item.updatedAt ?? new Date().toISOString(),
  };
}

function normalizeDevice(item) {
  return {
    id: item.id,
    name: item.name,
    type: item.type,
    state: item.state,
    battery: item.battery,
    signal: item.signal,
    point: item.point,
    hazardCode: item.hazardCode,
    lon: item.lon,
    lat: item.lat,
    version: Number(item.version ?? 0),
    updatedAt: item.updatedAt ?? new Date().toISOString(),
  };
}

function normalizePersonnel(item) {
  return {
    id: item.id,
    name: item.name,
    role: item.role ?? "巡检员",
    team: item.team ?? "值守组",
    region: item.region ?? "",
    phone: item.phone ?? "",
    shift: item.shift ?? "",
    state: item.state ?? "在线",
    lon: item.lon,
    lat: item.lat,
    speed: item.speed ?? 0,
    heading: item.heading ?? 0,
    route: Array.isArray(item.route) ? item.route : [],
    updatedAt: item.updatedAt ?? new Date().toISOString(),
  };
}

function timestamp(value) {
  const time = Date.parse(value || "");
  return Number.isFinite(time) ? time : 0;
}

/**
 * 幂等合并：传入消息比现存数据"更新"时才覆盖。
 * 更新判定 = 业务版本号优先，其次更新时间戳。后到的旧消息一律忽略，
 * 避免 WebSocket 重连补发导致的重复插入或状态回退（点 4、6）。
 */
function isNewer(existing, incoming) {
  const curVer = Number(existing?.version ?? 0);
  const nextVer = Number(incoming?.version ?? existing?.version ?? 0);
  if (nextVer > curVer) return true;
  if (nextVer < curVer) return false;
  return timestamp(incoming.updatedAt) >= timestamp(existing?.updatedAt);
}

/**
 * 单条 upsert：ID 相同且现有版本更新时保留现有，否则合并；ID 不同则新增。
 */
function upsert(list, nextItem) {
  const index = list.findIndex(
    (item) => String(item.id) === String(nextItem.id),
  );
  if (index === -1) return [nextItem, ...list];
  if (!isNewer(list[index], nextItem)) return list;
  const copy = list.slice();
  copy[index] = { ...copy[index], ...nextItem };
  return copy;
}

/**
 * 全量快照与现有状态合并（轮询兜底）：轮询可能返回比 WS 增量更旧的数据，
 * 逐条做版本比较，只让"更新的"进入状态，防止界面因旧数据回退闪烁（点 6）。
 */
function mergeSnapshot(list, incomingList) {
  let merged = list.slice();
  for (const incoming of incomingList) {
    merged = upsert(merged, incoming);
  }
  return merged;
}

function mapMotion(step, index) {
  return {
    lon: Number(step.lon ?? 107.21) + index * 0.0006,
    lat: Number(step.lat ?? 30.61) + index * 0.00045,
    at: step.at ?? new Date(Date.now() - index * 30000).toISOString(),
  };
}

/**
 * 本地模拟轨迹：仅演示/开发使用。生产环境由移动端每 10~30s 上报
 * GPS 坐标序列，前端通过轮询或 WebSocket 获取，不应做本地推演（点 2）。
 */
function mockRouteFor(staff, index) {
  const baseLon = 107.11 + (staff.id.charCodeAt(4) % 7) * 0.08 + index * 0.008;
  const baseLat = 30.42 + (staff.id.charCodeAt(5) % 6) * 0.05 + index * 0.007;
  const now = Date.now();
  return [
    {
      lon: baseLon - 0.012,
      lat: baseLat - 0.008,
      at: new Date(now - 180000).toISOString(),
    },
    { lon: baseLon, lat: baseLat, at: new Date(now - 90000).toISOString() },
    {
      lon: baseLon + 0.014,
      lat: baseLat + 0.006,
      at: new Date(now - 30000).toISOString(),
    },
    {
      lon: baseLon + 0.006,
      lat: baseLat + 0.012,
      at: new Date().toISOString(),
    },
  ];
}

function bakeMockPersonnel(staff, index) {
  const route = mockRouteFor(staff, index);
  return normalizePersonnel({
    ...staff,
    state: index % 4 === 0 ? "离线" : "在线",
    lon: route[route.length - 1].lon,
    lat: route[route.length - 1].lat,
    speed: 2 + index,
    heading: (index * 45) % 360,
    route,
  });
}

function applySnapshot(snapshot = {}) {
  if (Array.isArray(snapshot.warnings))
    state.warnings = mergeSnapshot(
      state.warnings,
      snapshot.warnings.map(normalizeWarning),
    );
  if (Array.isArray(snapshot.devices))
    state.devices = mergeSnapshot(
      state.devices,
      snapshot.devices.map(normalizeDevice),
    );
  if (Array.isArray(snapshot.personnel))
    state.personnel = mergeSnapshot(
      state.personnel,
      snapshot.personnel.map(normalizePersonnel),
    );
  emit({
    type: "snapshot",
    warnings: state.warnings,
    devices: state.devices,
    personnel: state.personnel,
  });
}

/**
 * 从 API base URL 自动推导 WebSocket URL：
 *   http://localhost:8081/api  → ws://localhost:8081/ws
 *   https://api.example.com    → wss://api.example.com/ws
 */
function deriveWsUrl() {
  const base = import.meta.env.VITE_API_BASE_URL;
  if (!base) return "";
  try {
    const url = new URL(base);
    // 替换协议
    const wsProto = url.protocol === "https:" ? "wss:" : "ws:";
    // 去掉 /api 后缀，拼 /ws
    const pathname = url.pathname.replace(/\/api\/?$/, "").replace(/\/$/, "");
    return `${wsProto}//${url.host}${pathname}/ws`;
  } catch {
    return "";
  }
}

/** 优先用一次性 ticket 连接，失败时 fallback 到 raw JWT。 */
async function connectSocket(url) {
  if (!url || typeof WebSocket === "undefined") return null;
  wsUrl = url; // 保存 base url 用于重连
  let connectUrl;
  const token = getAuthToken();
  if (token) {
    try {
      // 1. 尝试获取一次性 ticket（更安全，JWT 不出现在服务器日志）
      const ticketResp = await fetch("/api/auth/ws-ticket", {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (ticketResp.ok) {
        const payload = await ticketResp.json();
        const ticket = payload?.data?.ticket;
        if (ticket) {
          const sep = url.includes("?") ? "&" : "?";
          connectUrl = `${url}${sep}ticket=${encodeURIComponent(ticket)}`;
          tryOpen(connectUrl);
          return;
        }
      }
    } catch {
      // ticket 获取失败 → fallback
    }
    // 2. Fallback：直接把 JWT 放 query（兼容旧客户端，注意 token 会暴露在服务器日志）
    const sep = url.includes("?") ? "&" : "?";
    connectUrl = `${url}${sep}token=${encodeURIComponent(token)}`;
  } else {
    connectUrl = url;
  }
  tryOpen(connectUrl);
}

function tryOpen(fullUrl) {
  try {
    socket?.close();
  } catch {
    // ignore close failure
  }
  socket = new WebSocket(fullUrl);
  socket.onopen = () => emit({ type: "socket-open" });
  socket.onclose = () => {
    emit({ type: "socket-close" });
    if (!reconnectTimer) {
      reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null;
        connectSocket(wsUrl); // 重连时重新取 token / ticket（处理过期）
      }, 5000);
    }
  };
  socket.onerror = () => emit({ type: "socket-error" });
  socket.onmessage = (event) => {
    try {
      const message = JSON.parse(event.data);
      if (message.type === "warning" && message.data)
        state.warnings = upsert(state.warnings, normalizeWarning(message.data));
      if (message.type === "device" && message.data)
        state.devices = upsert(state.devices, normalizeDevice(message.data));
      if (message.type === "personnel" && message.data)
        state.personnel = upsert(
          state.personnel,
          normalizePersonnel(message.data),
        );
      emit(message);
    } catch {
      emit({ type: "socket-message", raw: event.data });
    }
  };
}

/**
 * 设备/灾害点位全量轮询（基础数据，低频兜底）。
 * 设备离线判定由后端按"最后上报时间超阈值"完成，前端只读状态字段（点 7）。
 */
async function pollSnapshot() {
  const [warnings, devices] = await Promise.all([
    dashboardApi.getWarnings(),
    dashboardApi.getDevices(),
  ]);
  state.warnings = mergeSnapshot(
    state.warnings,
    (warnings.list ?? warnings.records ?? []).map(normalizeWarning),
  );
  state.devices = mergeSnapshot(
    state.devices,
    (devices.list ?? devices.records ?? []).map(normalizeDevice),
  );
  emit({
    type: "poll",
    warnings: state.warnings,
    devices: state.devices,
    personnel: state.personnel,
  });
}

/**
 * 预警高频推送：预警是核心业务，频率高于设备/汇总（点 8）。
 * 列表数据由轮询兜底，地图/总览点位则依赖 WebSocket 增量。
 */
async function pollWarningsPreview() {
  const result = await dashboardApi.getWarningsPreview();
  const list = (result.list ?? result.records ?? []).map(normalizeWarning);
  state.warnings = mergeSnapshot(state.warnings, list);
  emit({
    type: "warning-poll",
    warnings: state.warnings,
  });
}

/**
 * 值守人员轮询：仅当生产模式（真实移动端上报）时请求后端人员实时位置；
 * 演示模式从独立档案生成（点 1、2）。
 */
async function pollPersonnel() {
  if (USE_LIVE_PERSONNEL) {
    const result = await dashboardApi.getPersonnel();
    state.personnel = mergeSnapshot(
      state.personnel,
      (result.list ?? result.records ?? result.personnel ?? []).map(
        normalizePersonnel,
      ),
    );
    emit({ type: "personnel-poll", personnel: state.personnel });
    return;
  }
  state.personnel = PERSONNEL_ROSTER.map(bakeMockPersonnel);
  emit({ type: "personnel-poll", personnel: state.personnel });
}

/** 本地轨迹动画：演示专用。真实系统以移动端上报频率驱动，无需定时推演（点 2、8）。 */
function animatePersonnel() {
  if (USE_LIVE_PERSONNEL) return; // 生产模式：位置来自上报，不做本地推演
  state.personnel = state.personnel.map((person, index) => {
    const route = person.route?.length
      ? person.route
      : mockRouteFor(
          PERSONNEL_ROSTER[index % PERSONNEL_ROSTER.length] ?? person,
          index,
        );
    const nextPoint =
      route[(index + Math.floor(Date.now() / 10000)) % route.length] ??
      route[route.length - 1];
    return {
      ...person,
      lon: nextPoint.lon,
      lat: nextPoint.lat,
      route,
      updatedAt: new Date().toISOString(),
    };
  });
  emit({ type: "personnel-motion", personnel: state.personnel });
}

export function startRealtimeSync(options = {}) {
  const explicitWs = options.wsUrl ?? import.meta.env.VITE_WS_URL;
  // 未显式配置时，从 VITE_API_BASE_URL 自动推导：http://host:port/api → ws://host:port/ws
  wsUrl = explicitWs || deriveWsUrl();
  if (!wsUrl) {
    console.warn(
      "[realtimeService] WebSocket URL 未配置，VITE_WS_URL 未设置且 VITE_API_BASE_URL 无法推导。" +
        "实时数据将仅依赖轮询兜底。建议设置其中之一。",
    );
  }
  pollingInterval = Number(
    options.pollingInterval ??
      import.meta.env.VITE_REALTIME_POLL_INTERVAL ??
      15000,
  );
  warningInterval = Number(
    options.warningInterval ??
      import.meta.env.VITE_WARNING_POLL_INTERVAL ??
      10000,
  );
  personnelInterval = Number(
    options.personnelInterval ??
      import.meta.env.VITE_PERSONNEL_POLL_INTERVAL ??
      30000,
  );

  stopRealtimeSync({ keepListeners: true });
  if (options.snapshot) applySnapshot(options.snapshot);
  if (wsUrl) connectSocket(wsUrl);

  pollingTimer = window.setInterval(
    () => {
      pollSnapshot().catch(() => {});
    },
    Math.max(15000, pollingInterval),
  );
  warningTimer = window.setInterval(
    () => {
      pollWarningsPreview().catch(() => {});
    },
    Math.max(5000, warningInterval),
  );
  personnelTimer = window.setInterval(
    () => {
      pollPersonnel().catch(() => {});
    },
    Math.max(10000, personnelInterval),
  );
  personnelRouteTimer = window.setInterval(() => {
    animatePersonnel();
  }, 10000);

  pollSnapshot().catch(() => {});
  pollWarningsPreview().catch(() => {});
  pollPersonnel().catch(() => {});
}

export function stopRealtimeSync({ keepListeners = false } = {}) {
  if (pollingTimer) {
    window.clearInterval(pollingTimer);
    pollingTimer = null;
  }
  if (warningTimer) {
    window.clearInterval(warningTimer);
    warningTimer = null;
  }
  if (personnelTimer) {
    window.clearInterval(personnelTimer);
    personnelTimer = null;
  }
  if (personnelRouteTimer) {
    window.clearInterval(personnelRouteTimer);
    personnelRouteTimer = null;
  }
  if (reconnectTimer) {
    window.clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  try {
    socket?.close();
  } catch {
    // ignore close failure
  }
  socket = null;
  if (!keepListeners) listeners.clear();
}

export function onRealtimeUpdate(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getRealtimeState() {
  return {
    warnings: state.warnings.slice(),
    devices: state.devices.slice(),
    personnel: state.personnel.slice(),
    version: globalVersion,
  };
}
