/**
 * 图表/分析/对比/区域/巡检报告类离线 mock。
 *
 * 包含：
 * - getAnalyticsMock()         总览分析页图表
 * - getTimeRangeComparisonMock 多时相对比（确定性伪随机）
 * - getZoneSummaryMock         灾种/区域专项大屏聚合
 * - getZoneRiskTrendMock       14 天风险趋势
 * - getInspectionReportMock    AI 巡检报告
 */
import { SEED_BASE } from "./seed";

// =====================================================================
// 总览分析图表
// =====================================================================

export function getAnalyticsMock() {
  return {
    hazardPoints: SEED_BASE.hazardPoints,
    overviewStats: SEED_BASE.overviewStats,
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
  };
}

// =====================================================================
// 多时相对比：FNV-1a + LCG 派生确定性伪随机序列
// 保证同 base+compare+mode 下结果可复现
// =====================================================================

function fnv1aHash(str) {
  let h = 2166136261;
  for (let i = 0; i < str.length; i += 1) {
    h ^= str.charCodeAt(i);
    h = (h * 16777619) >>> 0;
  }
  return h >>> 0;
}

function lcgRng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

function bucketize(seedStr, count, baseVal) {
  const r = lcgRng(fnv1aHash(seedStr));
  const out = [];
  for (let i = 0; i < count; i += 1) {
    const wave = Math.sin((i / count) * Math.PI * 2) * 0.18;
    const noise = (r() - 0.5) * 0.25;
    out.push(Math.max(0, Math.round(baseVal * (1 + wave + noise))));
  }
  return out;
}

/**
 * 多时相对比离线 mock
 * @param {{base: string, compare: string, mode?: 'side'|'overlay'|'delta'}} params
 */
export function getTimeRangeComparisonMock({ base, compare, mode = "side" } = {}) {
  const safeBase = base || "24h";
  const safeCompare = compare || "previous";
  const safeMode = ["side", "overlay", "delta"].includes(mode) ? mode : "side";

  const incidents = Array.isArray(SEED_BASE.incidents) ? SEED_BASE.incidents : [];
  const warnings = Array.isArray(SEED_BASE.warnings) ? SEED_BASE.warnings : [];
  const hazards = Array.isArray(SEED_BASE.hazardPoints) ? SEED_BASE.hazardPoints : [];
  const devices = Array.isArray(SEED_BASE.devices) ? SEED_BASE.devices : [];

  const seedKey = `${safeBase}|${safeCompare}|${safeMode}`;
  const bucket = 24; // 与"24h"基准等长的折线桶数
  const baseIncidents = Math.max(incidents.length, 8);
  const baseWarnings = Math.max(warnings.length, 6);
  const baseHazards = Math.max(hazards.length, 12);

  const kpis = [
    {
      key: "incidentTotal",
      label: "事件总数",
      unit: "起",
      base: baseIncidents,
      compare: Math.max(
        2,
        Math.round(
          baseIncidents * (0.7 + (fnv1aHash(`${seedKey}|incidentTotal`) % 80) / 100),
        ),
      ),
      higherIsBetter: false,
    },
    {
      key: "disposalRate",
      label: "处置率",
      unit: "%",
      base: 82 + (fnv1aHash(`${seedKey}|disposalRate`) % 15),
      compare: 78 + (fnv1aHash(`${seedKey}|disposalRate|c`) % 18),
      higherIsBetter: true,
    },
    {
      key: "avgHandleMinutes",
      label: "平均处置时长",
      unit: "分钟",
      base: 32 + (fnv1aHash(`${seedKey}|avgHandle`) % 25),
      compare: 30 + (fnv1aHash(`${seedKey}|avgHandle|c`) % 30),
      higherIsBetter: false,
    },
    {
      key: "onlineRate",
      label: "在线设备率",
      unit: "%",
      base: 88 + (fnv1aHash(`${seedKey}|onlineRate`) % 11),
      compare: 84 + (fnv1aHash(`${seedKey}|onlineRate|c`) % 14),
      higherIsBetter: true,
    },
    {
      key: "falseAlarmRate",
      label: "误报率",
      unit: "%",
      base: 3 + (fnv1aHash(`${seedKey}|falseAlarm`) % 6),
      compare: 2 + (fnv1aHash(`${seedKey}|falseAlarm|c`) % 7),
      higherIsBetter: false,
    },
    {
      key: "activeUsers",
      label: "用户活跃",
      unit: "人",
      base: 220 + (fnv1aHash(`${seedKey}|activeUsers`) % 180),
      compare: 200 + (fnv1aHash(`${seedKey}|activeUsers|c`) % 200),
      higherIsBetter: true,
    },
  ];

  const categories = [
    { key: "滑坡", label: "滑坡" },
    { key: "崩塌", label: "崩塌" },
    { key: "泥石流", label: "泥石流" },
    { key: "地裂缝", label: "地裂缝" },
    { key: "沉降", label: "沉降" },
  ].map((c) => ({
    ...c,
    base: Math.max(
      2,
      Math.round(
        baseHazards * (0.1 + (fnv1aHash(`${seedKey}|cat|${c.key}`) % 30) / 100),
      ),
    ),
    compare: Math.max(
      1,
      Math.round(
        baseHazards *
          (0.08 + (fnv1aHash(`${seedKey}|cat|${c.key}|c`) % 32) / 100),
      ),
    ),
  }));

  const regions = [
    "龙安镇",
    "石桥镇",
    "清河镇",
    "云台镇",
    "白石乡",
  ].map((name, idx) => ({
    name,
    base: Math.max(
      3,
      Math.round(
        baseIncidents * (0.08 + (fnv1aHash(`${seedKey}|reg|${name}`) % 22) / 100) +
          idx,
      ),
    ),
    compare: Math.max(
      2,
      Math.round(
        baseIncidents *
          (0.07 + (fnv1aHash(`${seedKey}|reg|${name}|c`) % 24) / 100) +
          idx,
      ),
    ),
  }));

  const series = {
    base: bucketize(`${seedKey}|series|base`, bucket, baseIncidents),
    compare: bucketize(
      `${seedKey}|series|compare`,
      bucket,
      Math.max(2, Math.round(baseIncidents * 0.82)),
    ),
  };

  const spark = {
    base: bucketize(`${seedKey}|spark|base`, 16, baseWarnings),
    compare: bucketize(
      `${seedKey}|spark|compare`,
      16,
      Math.max(2, Math.round(baseWarnings * 0.78)),
    ),
  };

  const summary = {
    incidentsTotal: incidents.length,
    warningsTotal: warnings.length,
    hazardsTotal: hazards.length,
    devicesTotal: devices.length,
    sampleBase: safeBase,
    sampleCompare: safeCompare,
  };

  return {
    base: { key: safeBase, label: safeBase },
    compare: { key: safeCompare, label: safeCompare },
    mode: safeMode,
    kpis,
    categories,
    regions,
    series,
    spark,
    summary,
  };
}

// =====================================================================
// 灾种 / 区域专项大屏：聚合 SEED_BASE
// =====================================================================

export function getZoneSummaryMock({ kind, region } = {}) {
  const hazards = SEED_BASE.hazardPoints.filter((p) => {
    const kindOk = !kind || kind === "all" || p.type === kind;
    const regionOk = !region || region === "all" || p.owner === region;
    return kindOk && regionOk;
  });
  const warningIds = new Set();
  SEED_BASE.warnings.forEach((w) => {
    const hazard = SEED_BASE.hazardPoints.find(
      (h) => String(h.id) === String(w.hazardId),
    );
    if (!hazard) {return;}
    if (
      (!kind || kind === "all" || hazard.type === kind) &&
      (!region || region === "all" || hazard.owner === region)
    ) {
      warningIds.add(w.id);
    }
  });
  const incidentIds = new Set();
  SEED_BASE.incidents.forEach((i) => {
    if (warningIds.has(i.warningId)) {incidentIds.add(i.id);}
  });
  const devices = SEED_BASE.devices.filter((d) => {
    const hazard = SEED_BASE.hazardPoints.find(
      (h) => String(h.id) === String(d.hazardId),
    );
    if (!hazard) {return false;}
    return (
      (!kind || kind === "all" || hazard.type === kind) &&
      (!region || region === "all" || hazard.owner === region)
    );
  });
  const onlineDevices = devices.filter((d) => d.state === "在线").length;
  const pending = SEED_BASE.tasks.filter((t) => t.status !== "已完成").length;
  return {
    kind: kind ?? "all",
    region: region ?? "all",
    hazardCount: hazards.length,
    onlineDeviceCount: onlineDevices,
    warningCount: warningIds.size,
    pendingCount: pending,
    topHazards: hazards.slice(0, 5),
    relatedWarnings: SEED_BASE.warnings.filter((w) => warningIds.has(w.id)),
    relatedIncidents: SEED_BASE.incidents.filter((i) => incidentIds.has(i.id)),
  };
}

/**
 * 灾种 / 区域专项的 14 天风险趋势 —— 按 kind+region 派生稳定伪序列
 * @param {{kind?: string, region?: string}} params
 */
export function getZoneRiskTrendMock({ kind, region } = {}) {
  const seedKey = `${kind ?? "all"}|${region ?? "all"}`;
  const seed = seedKey.split("").reduce((s, c) => s + c.charCodeAt(0), 0);
  const days = 14;
  const now = Date.now();
  const points = [];
  for (let i = days - 1; i >= 0; i -= 1) {
    const wave = Math.sin((i + (seed % 5)) / 1.8) * 6;
    const noise = ((seed >> i) % 7) - 3;
    const base = 50 + (seed % 18);
    const value = Math.max(10, Math.min(95, Math.round(base + wave + noise)));
    const ts = new Date(now - i * 24 * 60 * 60 * 1000);
    points.push({
      date: `${ts.getMonth() + 1}/${ts.getDate()}`,
      value,
    });
  }
  return {
    kind: kind ?? "all",
    region: region ?? "all",
    days,
    points,
  };
}

// =====================================================================
// AI 巡检报告：覆盖率/发现数/误报/漏检 + 模型 F1 + 混淆矩阵
// =====================================================================

export function getInspectionReportMock(params = {}) {
  const days = params.days ?? 7;
  const seed = days;

  const coverageTrend = Array.from({ length: Math.min(days, 30) }, (_, i) => {
    const wave = Math.sin((i / (days || 7)) * Math.PI) * 15;
    return Math.min(
      100,
      Math.max(
        40,
        Math.round(55 + (seed % 10) + (i / (days || 7)) * 35 + wave),
      ),
    );
  });

  const stackedBars = Array.from({ length: 6 }, (_, i) => ({
    fp: Math.max(1, 8 - i - (seed % 3)),
    miss: Math.max(1, 5 - i - (seed % 2)),
  }));

  const modelF1 = {
    xgboost: Array.from(
      { length: 8 },
      (_, i) => parseFloat((0.78 + (seed % 5) * 0.01 + i * 0.01).toFixed(3)),
    ),
    lstm: Array.from(
      { length: 8 },
      (_, i) => parseFloat((0.71 + (seed % 4) * 0.01 + i * 0.012).toFixed(3)),
    ),
    yolo: Array.from(
      { length: 8 },
      (_, i) => parseFloat((0.82 + (seed % 6) * 0.01 + i * 0.007).toFixed(3)),
    ),
  };

  const mSeed = seed % 20;
  const matrix = [
    [90 + (mSeed % 8), 6 + (mSeed % 4), 4],
    [4 + (mSeed % 3), 86 + (mSeed % 10), 10 - (mSeed % 5)],
    [2, 7 - (mSeed % 5), 91 + (mSeed % 7)],
  ];

  return {
    coverage: Math.round(70 + (seed % 20)),
    discoveredEvents: 12 + (seed % 30),
    falsePositiveRate: parseFloat((8 - (seed % 5)).toFixed(1)),
    missRate: parseFloat((3 + (seed % 4)).toFixed(1)),
    coverageTrend,
    stackedBars,
    modelF1,
    matrix,
  };
}

// =====================================================================
// 模块元信息（顶部 BrandMascot 用）
// =====================================================================

export const moduleMetaMock = {
  item: { title: "地脉智巡", desc: "本地模拟模式" },
};

// =====================================================================
// Sentinel 卫星合成图层离线回退
// =====================================================================

export const sentinelCoverageMock = {
  tiles: [],
  capturedAt: new Date().toISOString(),
};
