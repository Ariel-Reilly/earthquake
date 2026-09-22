import { dashboardApi, isBackendRuntime } from "../../dashboardApi.js";
import { getAuthToken } from "../../../stores/auth.js";
import { useRealtimeStore } from "../../../stores/realtime.js";

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
 * WS 在线期间的兜底轮询间隔。WS 静默或后端没推送时，靠这个保底刷新。
 * 默认比正常间隔长 4 倍，避免与 WS 推送打架。
 */
const fallbackPollingInterval = 60000;
const fallbackWarningInterval = 40000;
const fallbackPersonnelInterval = 120000;
/** WS 是否已建立 */
let wsActive = false;
/** WS 最近一次收到任意消息的时间戳（epoch ms）。用于静默检测。 */
let lastWsMessageAt = 0;

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

/**
 * 人员实时位置：
 * - 当 VITE_USE_PERSONNEL_LIVE=true 显式开启，或 VITE_USE_BACKEND=true（真实后端模式）时，
 *   从后端 /api/personnel/positions 拉取真实位置；
 * - 否则使用本地 mock 数据（演示模式）。
 */
const _useBackend =
  String(import.meta.env.VITE_USE_BACKEND ?? "false").toLowerCase() === "true";
const USE_LIVE_PERSONNEL =
  String(import.meta.env.VITE_USE_PERSONNEL_LIVE ?? "false").toLowerCase() ===
    "true" || _useBackend;

const state = {
  warnings: [],
  devices: [],
  personnel: [],
  /** v1.6.0：AI 实时监测的灾害点（WebSocket hazard 事件 + 轮询增量） */
  hazards: [],
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
  // Step 4: 把每次 emit 同步到 Pinia store,UI 可响应式观察
  try {
    useRealtimeStore().setEvent(payload);
  } catch {
    // Pinia 未注册时(早期错误路径)静默忽略
  }
}

function normalizeWarning(item) {
  return {
    id: item.id,
    bizNo: item.bizNo ?? item.warningNo ?? `W-${String(item.id)}`,
    name: item.title ?? item.name,
    title: item.title ?? item.name,
    desc: item.desc ?? item.message ?? "",
    level: item.level ?? item.riskLevel ?? "预警",
    // R3 P1-12：后端 WarningDto 的字段名是 cssClass，不是 class。
    // 保留 ?? item.class 以兼容旧 mock 数据，并在两者都缺失时按等级兜底。
    cssClass:
      item.cssClass ?? item.class ?? (String(item.level).includes("橙") ? "orange" : "blue"),
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

function normalizeHazard(item) {
  // 后端 HazardDto 字段全部展开到前端通用格式
  return {
    id: item.id,
    hazardCode: item.hazardCode,
    name: item.name,
    type: item.type,
    level: item.level,
    regionCode: item.regionCode,
    owner: item.owner,
    status: item.status,
    lon: item.lon,
    lat: item.lat,
    threatObject: item.threatObject,
    source: item.source ?? "MANUAL",
    discoveredAt: item.discoveredAt,
    detectionConfidence: item.detectionConfidence,
    // AI 综合分（v1.3.0）
    aiLevel: item.aiLevel,
    aiLevelLabel: item.aiLevelLabel,
    aiScore: item.aiScore,
    aiAnalyzedAt: item.aiAnalyzedAt,
    aiDisasterType: item.aiDisasterType,
    aiConcernLabel: item.aiConcernLabel,
    aiDeviceAvailable: item.aiDeviceAvailable,
    version: Number(item.version ?? Date.now()),
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
  if (nextVer > curVer) {return true;}
  if (nextVer < curVer) {return false;}
  return timestamp(incoming.updatedAt) >= timestamp(existing?.updatedAt);
}

/**
 * 单条 upsert：ID 相同且现有版本更新时保留现有，否则合并；ID 不同则新增。
 */
function upsert(list, nextItem) {
  const index = list.findIndex(
    (item) => String(item.id) === String(nextItem.id),
  );
  if (index === -1) {return [nextItem, ...list];}
  if (!isNewer(list[index], nextItem)) {return list;}
  const copy = list.slice();
  copy[index] = { ...copy[index], ...nextItem };
  return copy;
}

/**
 * 全量快照替换（轮询兜底）：后端返回的就是当前真实状态。
 *
 * 语义：incoming 为权威，已不在其中的项视为"后端已剔除"。
 * 但要保留 state 中 version > 0 的项（WS 推送的较新增量，可能在
 * 同一时刻被 poll 漏掉，避免回退闪烁）。
 *
 * 与 mergeSnapshot 的区别：本函数会删项，mergeSnapshot 仅 upsert。
 * 旧实现一直 merge 导致后端已删除的预警/设备/灾害永远在前端显示。
 */
function replaceFromSnapshot(stateList, incomingList) {
  const incomingIds = new Set(incomingList.map((item) => String(item.id)));
  const fresherFromState = stateList.filter(
    (item) =>
      !incomingIds.has(String(item.id)) &&
      Number(item.version ?? 0) > 0,
  );
  return [...incomingList, ...fresherFromState];
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
  // 启动时的初始快照：用后端返回覆盖本地（本地此时为空，相当于赋值）
  if (Array.isArray(snapshot.warnings))
    {state.warnings = replaceFromSnapshot(
      state.warnings,
      snapshot.warnings.map(normalizeWarning),
    );}
  if (Array.isArray(snapshot.devices))
    {state.devices = replaceFromSnapshot(
      state.devices,
      snapshot.devices.map(normalizeDevice),
    );}
  if (Array.isArray(snapshot.personnel))
    {state.personnel = replaceFromSnapshot(
      state.personnel,
      snapshot.personnel.map(normalizePersonnel),
    );}
  if (Array.isArray(snapshot.hazards))
    {state.hazards = replaceFromSnapshot(
      state.hazards,
      snapshot.hazards.map(normalizeHazard),
    );}
  emit({
    type: "snapshot",
    warnings: state.warnings,
    devices: state.devices,
    personnel: state.personnel,
    hazards: state.hazards,
  });
}

/**
 * 从 API base URL 自动推导 WebSocket URL：
 *   http://localhost:8081/api  → ws://localhost:8081/ws
 *   https://api.example.com    → wss://api.example.com/ws
 *   /api (相对路径)             → 基于 window.location.origin 推导
 */
function deriveWsUrl() {
  const base = import.meta.env.VITE_API_BASE_URL;
  try {
    if (!base) {
      // 无配置：用当前页面 origin 兜底
      return `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/ws`;
    }
    // base 可能是相对路径（如 "/api"），先拼接 origin
    const absolute = new URL(base, window.location.origin);
    const wsProto = absolute.protocol === "https:" ? "wss:" : "ws:";
    const pathname = absolute.pathname
      .replace(/\/api\/?$/, "")
      .replace(/\/$/, "");
    return `${wsProto}//${absolute.host}${pathname}/ws`;
  } catch {
    // 任何异常都退回 window.location.origin（绝对兜底）
    try {
      return `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/ws`;
    } catch {
      return "";
    }
  }
}

/** 优先用一次性 ticket 连接，失败时 fallback 到 raw JWT。 */
async function connectSocket(url) {
  if (!url || typeof WebSocket === "undefined") {return null;}
  wsUrl = url;
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
  socket.onopen = () => {
    wsActive = true;
    lastWsMessageAt = Date.now();
    emit({ type: "socket-open" });
    try { useRealtimeStore().setStatus("ws") } catch {}
    // 不再彻底停止轮询！改为降频兜底，防止后端 WS 静默（不推送）时地图永远不刷新
    demotePollingToFallback();
  };
  socket.onclose = () => {
    wsActive = false;
    emit({ type: "socket-close" });
    try { useRealtimeStore().setStatus("polling") } catch {}
    resumePolling();
    if (!reconnectTimer) {
      reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null;
        connectSocket(wsUrl);
      }, 5000);
    }
  };
  socket.onerror = () => {
    emit({ type: "socket-error" });
    try { useRealtimeStore().setStatus("polling") } catch {}
  };
  socket.onmessage = (event) => {
    lastWsMessageAt = Date.now();
    try {
      const message = JSON.parse(event.data);
      if (message.type === "warning" && message.data)
        {state.warnings = upsert(state.warnings, normalizeWarning(message.data));}
      if (message.type === "device" && message.data)
        {state.devices = upsert(state.devices, normalizeDevice(message.data));}
      if (message.type === "personnel" && message.data)
        {state.personnel = upsert(
          state.personnel,
          normalizePersonnel(message.data),
        );}
      if (message.type === "hazard" && message.data) {
        state.hazards = upsert(state.hazards, normalizeHazard(message.data));
      }
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
  const [warnings, devices, hazards] = await Promise.all([
    dashboardApi.getWarnings(),
    dashboardApi.getDevices(),
    dashboardApi.getHazards({ size: 50 }),
  ]);
  // 全量快照：后端返回即权威。已删除/失效的项会被剔除，
  // 仅保留 state 中由 WS 推送的 version > 0 的较新增量（防止回退闪烁）。
  state.warnings = replaceFromSnapshot(
    state.warnings,
    (warnings.list ?? warnings.records ?? []).map(normalizeWarning),
  );
  state.devices = replaceFromSnapshot(
    state.devices,
    (devices.list ?? devices.records ?? []).map(normalizeDevice),
  );
  state.hazards = replaceFromSnapshot(
    state.hazards,
    (hazards.list ?? hazards.records ?? []).map(normalizeHazard),
  );
  emit({
    type: "poll",
    warnings: state.warnings,
    devices: state.devices,
    personnel: state.personnel,
    hazards: state.hazards,
  });
}

/**
 * 预警高频推送：预警是核心业务，频率高于设备/汇总（点 8）。
 * 列表数据由轮询兜底，地图/总览点位则依赖 WebSocket 增量。
 */
async function pollWarningsPreview() {
  const result = await dashboardApi.getWarningsPreview();
  const list = (result.list ?? result.records ?? []).map(normalizeWarning);
  // 同样：全量替换，后端已关闭的预警从前端消失
  state.warnings = replaceFromSnapshot(state.warnings, list);
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
    // 全量快照：后端返回的人员列表即权威。已离队 / 临时下线的值守人员
    // 会从前端消失；保留 state 中 version>0 的 WS 较新项避免回退。
    state.personnel = replaceFromSnapshot(
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
  if (USE_LIVE_PERSONNEL) {return;}
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

/** WS 静默 watchdog 句柄 */
let wsSilenceTimer = null;
/** WS 静默多少毫秒视为假活（连上但无推送）。默认 90 秒。 */
const WS_SILENCE_THRESHOLD_MS = 90_000;

/**
 * 启动 WS 静默 watchdog：
 * 每 WS_SILENCE_THRESHOLD_MS / 3 检查一次，若 WS 仍标记为 active 但
 * 最近 WS_SILENCE_THRESHOLD_MS 毫秒内没收到任何消息，视为"假活"——
 * 主动关闭 socket 触发重连，并切回全速轮询，避免地图永远不刷新。
 */
function startWsSilenceWatchdog() {
  stopWsSilenceWatchdog();
  wsSilenceTimer = window.setInterval(() => {
    if (!wsActive) {return;}
    if (!lastWsMessageAt) {return;}
    const silent = Date.now() - lastWsMessageAt;
    if (silent >= WS_SILENCE_THRESHOLD_MS) {
      console.warn(
        `[realtimeService] WS 已 ${Math.round(silent / 1000)}s 未推送消息，按假活处理：关闭 socket 并切回全速轮询`,
      );
      try {
        socket?.close();
      } catch {
        // ignore
      }
      wsActive = false;
      resumePolling();
      // 5 秒后重连（与 onclose 里的 5s 行为对齐）
      if (!reconnectTimer) {
        reconnectTimer = window.setTimeout(() => {
          reconnectTimer = null;
          connectSocket(wsUrl);
        }, 5000);
      }
    }
  }, Math.max(15000, Math.floor(WS_SILENCE_THRESHOLD_MS / 3)));
}

function stopWsSilenceWatchdog() {
  if (wsSilenceTimer) {
    window.clearInterval(wsSilenceTimer);
    wsSilenceTimer = null;
  }
}

export function startRealtimeSync(options = {}) {
  try { useRealtimeStore().setStatus("connecting") } catch {}
  const explicitWs = options.wsUrl ?? import.meta.env.VITE_WS_URL;
  // 未显式配置时，从 VITE_API_BASE_URL 自动推导：http://host:port/api → ws://host:port/ws
  // 后端不可达（演示/mock 模式）时不发起 WebSocket，仅依赖轮询兜底，避免无意义的连接失败
  if (explicitWs) {
    wsUrl = explicitWs;
  } else if (!isBackendRuntime()) {
    wsUrl = "";
    console.warn(
      "[realtimeService] 后端不可达（演示模式），跳过 WebSocket，实时数据由轮询兜底。",
    );
  } else {
    wsUrl = deriveWsUrl();
  }
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
  if (options.snapshot) {applySnapshot(options.snapshot);}
  if (wsUrl) {connectSocket(wsUrl);}

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
  // 仅在演示模式（!USE_LIVE_PERSONNEL）下启用本地轨迹动画定时器
  if (!USE_LIVE_PERSONNEL) {
    personnelRouteTimer = window.setInterval(() => {
      animatePersonnel();
    }, 10000);
  }

  pollSnapshot().catch(() => {});
  pollWarningsPreview().catch(() => {});
  pollPersonnel().catch(() => {});
  // 启动 WS 静默 watchdog（只在准备连 WS 时才有意义）
  if (wsUrl) {startWsSilenceWatchdog();}
}

/**
 * 清除所有轮询定时器（保留监听器）。demotePollingToFallback 专用。
 */
function clearAllPollingTimers() {
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
}

/**
 * WS 已建立时不再彻底停止轮询，改为降频兜底（默认 4 倍间隔）。
 * 这样后端 WS 即使静默不推送，地图也能在兜底周期内刷新。
 */
function demotePollingToFallback() {
  clearAllPollingTimers();
  pollingTimer = window.setInterval(
    () => {
      pollSnapshot().catch(() => {});
    },
    Math.max(15000, fallbackPollingInterval),
  );
  warningTimer = window.setInterval(
    () => {
      pollWarningsPreview().catch(() => {});
    },
    Math.max(5000, fallbackWarningInterval),
  );
  personnelTimer = window.setInterval(
    () => {
      pollPersonnel().catch(() => {});
    },
    Math.max(10000, fallbackPersonnelInterval),
  );
}

/**
 * WebSocket 断开时恢复正常频率轮询兜底。
 */
function resumePolling() {
  if (pollingTimer) {return;}
  // R3 P1-24：此前只判断 pollingTimer 就无条件重建下面两个定时器，
  // 若它们仍存活，旧句柄会被新句柄覆盖丢失、再也无法 clearInterval。
  // 重建前先清理，避免产生孤儿定时器。
  if (warningTimer) {
    window.clearInterval(warningTimer);
    warningTimer = null;
  }
  if (personnelTimer) {
    window.clearInterval(personnelTimer);
    personnelTimer = null;
  }
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
  stopWsSilenceWatchdog();
  wsActive = false;
  lastWsMessageAt = 0;
  // R3 P0-5：必须先解绑全部事件再 close()。
  // close() 是异步的，若只 close() 而不解绑，onclose 仍会被异步派发 →
  // 调 resumePolling() 重建三个轮询定时器，并 5s 后 connectSocket() 重连。
  // 登出时（DashboardLayout 调用本函数）表现为：退出登录后仍在轮询鉴权接口
  // → 401 → httpClient 的 redirectToLogin 与 router.replace('/login') 打架，
  // 登录页反复抖动/重定向。
  if (socket) {
    try {
      socket.onopen = null;
      socket.onmessage = null;
      socket.onerror = null;
      socket.onclose = null;
      socket.close();
    } catch {
      // ignore close failure
    }
  }
  socket = null;
  if (!keepListeners) {listeners.clear();}
  try { useRealtimeStore().setStatus("idle") } catch {}
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
    hazards: state.hazards.slice(),
    version: globalVersion,
  };
}
