/**
 * 运行时派生类离线 mock：
 * - 设备轨迹回放（按 deviceId 散列出 20 点稳定轨迹）
 * - 路径规划（直线 + 抖动生成 fastest / safest 两套方案）
 * - P0 阶段新增：用户偏好/会签/路径避障/区域专项大屏
 */
import { SEED_BASE } from "./seed";

// =====================================================================
// 设备轨迹：从 seed 设备的 lon/lat 中心派生 20 个稳定点
// =====================================================================

export function getDeviceTrackMock(deviceId, { from, to } = {}) {
  const device =
    SEED_BASE.devices.find((d) => String(d.id) === String(deviceId)) ??
    SEED_BASE.devices[0];
  if (!device) {return [];}
  const seed = String(deviceId)
    .split("")
    .reduce((s, c) => s + c.charCodeAt(0), 0);
  const baseLon = device.lon ?? 107.21;
  const baseLat = device.lat ?? 30.61;
  const fromTs = from ? new Date(from).getTime() : Date.now() - 20 * 60 * 1000;
  const toTs = to ? new Date(to).getTime() : Date.now();
  const points = [];
  for (let i = 0; i < 20; i += 1) {
    const t = fromTs + ((toTs - fromTs) * i) / 19;
    const angle = (seed + i * 13) % 360;
    const rad = (angle * Math.PI) / 180;
    const r = 0.003 + ((seed + i) % 10) * 0.0002;
    points.push({
      lon: parseFloat((baseLon + Math.cos(rad) * r).toFixed(6)),
      lat: parseFloat((baseLat + Math.sin(rad) * r * 0.8).toFixed(6)),
      ts: new Date(t).toISOString(),
      speed: 0.5 + ((seed + i) % 5) * 0.2,
    });
  }
  return points;
}

// =====================================================================
// 路径规划：直线距离 + ±10% 抖动生成 2 套方案
// =====================================================================

export function planRouteMock(payload = {}) {
  const { startLon = 0, startLat = 0, endLon = 0, endLat = 0 } = payload;
  const dLon = endLon - startLon;
  const dLat = endLat - startLat;
  const straightKm = Math.sqrt(dLon * dLon + dLat * dLat) * 111;
  const seed = Math.round(straightKm * 10);
  const fastestKm = parseFloat(
    (straightKm * (0.9 + (seed % 10) * 0.01)).toFixed(2),
  );
  const safestKm = parseFloat(
    (straightKm * (1.0 + (seed % 5) * 0.02)).toFixed(2),
  );
  const fastestMin = Math.round((fastestKm / 60) * 60);
  const safestMin = Math.round((safestKm / 40) * 60);
  const fmt = (m) => `${Math.floor(m / 60)}分${m % 60}秒`;

  const makeGeom = (_km) => {
    const pts = [];
    for (let i = 0; i <= 8; i += 1) {
      const t = i / 8;
      const jitter = i % 2 === 0 ? 0 : 0.0003 * (((seed + i) % 3) - 1);
      pts.push([
        parseFloat((startLon + dLon * t + jitter).toFixed(6)),
        parseFloat((startLat + dLat * t + jitter * 0.5).toFixed(6)),
      ]);
    }
    return pts;
  };

  return [
    {
      type: "fastest",
      distance: fastestKm,
      duration: fmt(fastestMin),
      warning: fastestKm > 50 ? "长距离路线，请注意道路状况" : undefined,
      geometry: makeGeom(fastestKm),
    },
    {
      type: "safest",
      distance: safestKm,
      duration: fmt(safestMin),
      warning: "安全路线绕行较多，请合理安排时间",
      geometry: makeGeom(safestKm),
    },
  ];
}

// =====================================================================
// P0-1：路径规划（含避障）
// =====================================================================

export function getObstacleAreasMock() {
  // 与 seed.hazardPoints 中风险等级>=橙的灾害点对应形成避障多边形
  return SEED_BASE.hazardPoints
    .filter((h) => ["橙色", "红色"].includes(h.level))
    .slice(0, 4)
    .map((h, idx) => ({
      id: `OBS-${h.id ?? idx}`,
      type: "polygon",
      hazardId: h.id,
      name: h.name ?? `影响区域 ${idx + 1}`,
      level: h.level,
      coordinates: [
        [h.lon - 0.01, h.lat - 0.008],
        [h.lon + 0.01, h.lat - 0.008],
        [h.lon + 0.012, h.lat + 0.008],
        [h.lon - 0.012, h.lat + 0.008],
        [h.lon - 0.01, h.lat - 0.008],
      ],
    }));
}

/**
 * A* + 避障多边形扩展方案。
 * 简化策略：生成 fastest / safest / shortest 三套路径，分别加入不同程度的绕行。
 * 真实 A* 在后端实现（PathPlanningService），前端只是 mock 降级。
 */
export function planRoutesWithObstaclesMock({ start, end, obstacles = [] }) {
  const startLon = start?.lng ?? start?.lon ?? 120.15;
  const startLat = start?.lat ?? 30.28;
  const endLon = end?.lng ?? end?.lon ?? 120.35;
  const endLat = end?.lat ?? end?.lat ?? 30.45;
  const dLon = endLon - startLon;
  const dLat = endLat - startLat;
  const straightKm = +(Math.sqrt(dLon * dLon + dLat * dLat) * 111).toFixed(2);
  const id = (m) => `route-${m}-${Math.floor(straightKm * 100)}`;
  const fmtDur = (m) => `${Math.floor(m / 60)}分${m % 60}秒`;
  const makeLine = (jitterBase, jitterY, steps = 12, amp = 0.003) => {
    const pts = [];
    for (let i = 0; i <= steps; i += 1) {
      const t = i / steps;
      const wave = Math.sin(t * Math.PI * 3) * amp;
      const jx = (i % 2 === 0 ? 1 : -1) * jitterBase * 0.0008;
      const jy = jitterY * 0.0005;
      pts.push([
        +(startLon + dLon * t + wave + jx).toFixed(6),
        +(startLat + dLat * t + wave * 0.5 + jy).toFixed(6),
      ]);
    }
    return pts;
  };
  return [
    {
      id: id("fastest"),
      mode: "fastest",
      distance: +(straightKm * 1.05).toFixed(0),
      duration: Math.round((straightKm * 1.05) / 60 * 60),
      riskLevel: "medium",
      obstacleCount: obstacles.length,
      geometry: makeLine(0.2, 0.1, 12, 0.002),
      waypoints: [
        { name: "起点", lng: startLon, lat: startLat, type: "start" },
        { name: "中途补给点", lng: +((startLon + endLon) / 2).toFixed(4), lat: +((startLat + endLat) / 2 + 0.005).toFixed(4), type: "rest" },
        { name: "灾区", lng: endLon, lat: endLat, type: "end" },
      ],
    },
    {
      id: id("safest"),
      mode: "safest",
      distance: +(straightKm * 1.32).toFixed(0),
      duration: Math.round((straightKm * 1.32) / 40 * 60),
      riskLevel: "low",
      obstacleCount: obstacles.length,
      geometry: makeLine(0.8, 0.4, 16, 0.008),
      waypoints: [
        { name: "起点", lng: startLon, lat: startLat, type: "start" },
        { name: "安全绕行点 A", lng: +(startLon + 0.02).toFixed(4), lat: +(startLat + 0.015).toFixed(4), type: "waypoint" },
        { name: "安全绕行点 B", lng: +(startLon + 0.04).toFixed(4), lat: +(startLat + 0.025).toFixed(4), type: "waypoint" },
        { name: "灾区", lng: endLon, lat: endLat, type: "end" },
      ],
    },
    {
      id: id("shortest"),
      mode: "shortest",
      distance: +(straightKm * 0.98).toFixed(0),
      duration: Math.round((straightKm * 0.98) / 65 * 60),
      riskLevel: "high",
      obstacleCount: obstacles.length,
      geometry: makeLine(0.05, 0.02, 8, 0.0005),
      waypoints: [
        { name: "起点", lng: startLon, lat: startLat, type: "start" },
        { name: "灾区", lng: endLon, lat: endLat, type: "end" },
      ],
    },
  ].map((r) => ({ ...r, duration: fmtDur(r.duration) }));
}

export function exportRouteGeoJsonMock(route) {
  return {
    type: "Feature",
    properties: {
      name: route.id,
      mode: route.mode,
      distance: route.distance,
      duration: route.duration,
      riskLevel: route.riskLevel,
    },
    geometry: {
      type: "LineString",
      coordinates: route.geometry ?? [],
    },
  };
}

// =====================================================================
// P0-2：预警解除通知预览
// =====================================================================

const RESOLVE_TEMPLATES = [
  "经现场核查，监测数据已恢复正常，特解除本预警。",
  "应急响应已结束，本次预警风险已消除，现予以解除。",
  "现场复核确认威胁消除，预警予以解除，请继续关注后续监测。",
];

export function previewWarningResolveMock(warning) {
  const reason = warning?.closeReason ?? "现场核查位移速率恢复正常，风险已消除";
  return {
    title: `【解除预警】${warning?.title ?? "灾害预警"} 已解除`,
    content: `${RESOLVE_TEMPLATES[0]}\n解除时间：${new Date().toLocaleString("zh-CN", { hour12: false })}\n解除原因：${reason}\n解除范围：${warning?.scope ?? "全部"}\n请相关人员知悉。`,
    channels: ["SMS", "APP", "SCREEN"],
    templates: RESOLVE_TEMPLATES,
  };
}

// =====================================================================
// P0-3：用户偏好默认
// =====================================================================

export const DEFAULT_USER_PREFERENCE = {
  channels: { sms: true, email: false, app: true, inApp: true, phone: false },
  levels: {
    red: ["sms", "app", "inApp", "phone"],
    orange: ["sms", "app", "inApp"],
    yellow: ["app", "inApp"],
    blue: ["inApp"],
  },
  language: "zh-CN",
  theme: "light",
  landingPage: "/dashboard",
  compactMode: false,
  quietHoursEnabled: false,
  quietStart: "22:00",
  quietEnd: "08:00",
};

export function getUserPreferenceMock(userId = "u-admin") {
  const raw = typeof window !== "undefined" ? window.localStorage.getItem(`geosentry_pref_${userId}`) : null;
  if (raw) {
    try { return JSON.parse(raw); } catch { /* ignore */ }
  }
  return { ...DEFAULT_USER_PREFERENCE };
}

export function saveUserPreferenceMock(userId = "u-admin", pref = {}) {
  if (typeof window === "undefined") return { ok: true };
  window.localStorage.setItem(`geosentry_pref_${userId}`, JSON.stringify(pref));
  return { ok: true, savedAt: new Date().toISOString() };
}

// =====================================================================
// P0-4：会签
// =====================================================================

export const SIGN_MODE_OPTIONS = [
  { value: "PARALLEL", label: "并行会签", desc: "所有人同时收到待办，全部确认后汇总" },
  { value: "SEQUENTIAL", label: "串行会签", desc: "按顺序逐个确认，前一人未确认则后一人不会签" },
];

export const TIMEOUT_ACTION_OPTIONS = [
  { value: "ESCALATE", label: "自动升级", desc: "超时后转交高级别负责人" },
  { value: "REJECT", label: "自动驳回", desc: "超时后视为未通过本次会签" },
];

export const EXPERT_POOL = [
  { id: "expertA", name: "刘国梁", specialty: "地质灾害", org: "应急管理部地质所" },
  { id: "expertB", name: "周晓彤", specialty: "气象水文", org: "国家气象局" },
  { id: "expertC", name: "高志远", specialty: "应急救援", org: "武警救援学院" },
  { id: "expertD", name: "韩雪清", specialty: "岩土工程", org: "中科院地质所" },
  { id: "expertE", name: "杨思齐", specialty: "遥感监测", org: "中国地震局" },
];

const SIGN_STORE_KEY = "geosentry_sign_records";

function loadSignStore() {
  if (typeof window === "undefined") return [];
  try {
    return JSON.parse(window.localStorage.getItem(SIGN_STORE_KEY) ?? "[]");
  } catch {
    return [];
  }
}

function saveSignStore(list) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(SIGN_STORE_KEY, JSON.stringify(list));
}

export function listSignRecordsMock(eventId) {
  const all = loadSignStore();
  return eventId ? all.filter((r) => r.eventId === eventId) : all;
}

export function createSignMock({ eventId, signers, mode, timeout, timeoutAction, comment, initiator }) {
  const list = loadSignStore();
  const id = `sign-${Date.now()}`;
  const record = {
    id,
    eventId,
    mode: mode ?? "PARALLEL",
    timeout: Number(timeout ?? 24),
    timeoutAction: timeoutAction ?? "ESCALATE",
    comment,
    initiator: initiator ?? "system",
    createdAt: new Date().toISOString(),
    status: "RUNNING",
    signers: signers.map((sid) => {
      const exp = EXPERT_POOL.find((e) => e.id === sid);
      return {
        signerId: sid,
        signerName: exp?.name ?? sid,
        specialty: exp?.specialty ?? "",
        status: "PENDING",
        signedAt: null,
        opinion: "",
        order: mode === "SEQUENTIAL" ? signers.indexOf(sid) : 0,
      };
    }),
  };
  list.unshift(record);
  saveSignStore(list);
  return record;
}

export function signConfirmMock(signId, signerId, opinion = "") {
  const list = loadSignStore();
  const idx = list.findIndex((r) => r.id === signId);
  if (idx < 0) return null;
  const sign = list[idx];
  const s = sign.signers.find((x) => x.signerId === signerId);
  if (!s) return sign;
  s.status = "CONFIRMED";
  s.opinion = opinion;
  s.signedAt = new Date().toISOString();
  if (sign.signers.every((x) => x.status === "CONFIRMED")) {
    sign.status = "COMPLETED";
    sign.completedAt = new Date().toISOString();
  }
  list[idx] = sign;
  saveSignStore(list);
  return sign;
}

export function signRejectMock(signId, signerId, opinion = "") {
  const list = loadSignStore();
  const idx = list.findIndex((r) => r.id === signId);
  if (idx < 0) return null;
  const sign = list[idx];
  const s = sign.signers.find((x) => x.signerId === signerId);
  if (!s) return sign;
  s.status = "REJECTED";
  s.opinion = opinion;
  s.signedAt = new Date().toISOString();
  sign.status = "REJECTED";
  sign.completedAt = new Date().toISOString();
  list[idx] = sign;
  saveSignStore(list);
  return sign;
}

export function transferEventMock(eventId, toUser, reason, operator) {
  const list = loadSignStore();
  const idx = list.findIndex((r) => r.eventId === eventId);
  const transfer = {
    id: `transfer-${Date.now()}`,
    eventId,
    toUser,
    reason,
    operator: operator ?? "system",
    transferredAt: new Date().toISOString(),
  };
  if (idx >= 0) {
    list[idx].transfers = list[idx].transfers ?? [];
    list[idx].transfers.push(transfer);
    list[idx].assignee = toUser;
    saveSignStore(list);
  }
  return transfer;
}

// =====================================================================
// P0-5：区域专项大屏
// =====================================================================

export function getZoneKpiMock({ region = "all", kind = "all", range = "7d" } = {}) {
  const factor = (region === "all" ? 1 : 0.4) * (kind === "all" ? 1 : 0.5);
  const rangeFactor = range === "24h" ? 0.2 : range === "7d" ? 1 : range === "30d" ? 2 : 3;
  const hazards = Math.round(60 * factor + 80);
  const warnings = Math.round(8 * factor + 6);
  const onlineDevices = Math.round(110 * factor + 18);
  const aiAccuracy = +(0.86 + (rangeFactor % 5) * 0.015).toFixed(2);
  return {
    hazardCount: hazards,
    warningCount: warnings,
    onlineDeviceCount: Math.min(onlineDevices, 168),
    aiAccuracy,
    lastUpdated: new Date().toISOString(),
  };
}

export function getZoneTrendMock({ range = "7d" } = {}) {
  const days = range === "24h" ? 24 : range === "7d" ? 7 : range === "30d" ? 30 : 60;
  const points = [];
  const base = 60;
  for (let i = 0; i < days; i += 1) {
    const date = new Date(Date.now() - (days - i - 1) * 24 * 3600 * 1000);
    const wave = Math.sin(i / 4) * 12 + (i % 3) * 2;
    points.push({
      date: date.toISOString().slice(0, 10),
      value: Math.max(10, Math.round(base + wave + (i % 5) * 3)),
    });
  }
  return { points };
}

export function getZoneTopHazardsMock({ region = "all", kind = "all" } = {}) {
  const items = SEED_BASE.hazardPoints.slice(0, 5).map((h, i) => ({
    rank: i + 1,
    hazardCode: h.hazardCode ?? `H${i + 1}`,
    name: h.name ?? `灾害点 ${i + 1}`,
    type: h.disasterType ?? "滑坡",
    level: h.level ?? "橙色",
    riskScore: +(0.95 - i * 0.07).toFixed(2),
    displacement: `${(5 + i * 0.6).toFixed(1)}mm/d`,
    rainfall: `${40 + i * 5}mm`,
    region: h.regionName ?? "龙安镇",
    lon: h.lon,
    lat: h.lat,
  }));
  if (kind !== "all") {
    items.forEach((it) => (it.type = kind));
  }
  if (region !== "all") {
    items.forEach((it) => (it.region = region));
  }
  return items;
}

export function getZoneRelatedWarningsMock({ region = "all", kind = "all", range = "7d" } = {}) {
  return SEED_BASE.warnings.slice(0, 5).map((w, i) => ({
    code: w.warningCode ?? `W${i + 1}`,
    title: w.title ?? "区域关联预警",
    level: w.warningLevel ?? "橙色",
    status: w.status ?? "已发布",
    region: w.region ?? (region === "all" ? "全域" : region),
    triggeredAt: w.triggeredAt ?? new Date().toISOString(),
  }));
}

export function getZoneRelatedEmergenciesMock({ region = "all" } = {}) {
  return SEED_BASE.incidents.slice(0, 4).map((e, i) => ({
    code: e.code ?? `E${i + 1}`,
    title: e.name ?? `应急事件 ${i + 1}`,
    level: e.level ?? "三级响应",
    status: e.status ?? "处置中",
    region: region === "all" ? "全域" : region,
    commander: e.commander ?? "指挥组",
    startedAt: e.startedAt ?? new Date().toISOString(),
  }));
}
