/**
 * AI 相关离线 mock 数据：
 * - AI 综合分析（hazard point 维度评分）
 * - AI 设备健康评分
 * - 任务运行时监控（CPU/GPU/内存/重试）
 * - 任务执行日志（按 taskId 散列出稳定序列）
 *
 * 凡涉及基于 taskId 散列生成确定性 mock 的逻辑都集中到这里，避免散落在 dashboardApi.js。
 */
import { SEED_BASE } from "./seed";

// AI 综合分析：从 hazardPoints 派生完整列表（v1.3.0+ 落库结构）

export function getAiComprehensiveAllMock() {
  const list = SEED_BASE.hazardPoints.map((h, i) => ({
    hazardCode: `GS-${String(i + 1).padStart(3, "0")}`,
    riskLevel: h.level.includes("高")
      ? "HIGH"
      : h.level.includes("关注")
        ? "MEDIUM"
        : "LOW",
    riskScore:
      h.level.includes("高")
        ? 65 + Math.random() * 25
        : 35 + Math.random() * 20,
    confidence: 0.78,
    model: "heuristic-dimension-fusion",
    analyzedAt: new Date(Date.now() - i * 3 * 60 * 1000).toISOString(),
    reasoning: ["降水偏多", "坡度较陡"],
    suggestedActions: ["加密观测", "现场核查"],
    dimensions: {},
  }));
  return list;
}

// 单条综合分析缓存（离线无落库）
export const aiComprehensiveCachedMock = null;

// AI 综合分析强制刷新回执
export const aiComprehensiveRefreshMock = {
  analyzedCount: 0,
  failedCount: 0,
  afterCount: 0,
};

// AI 设备健康列表（离线无数据，让 UI 走骨架）
export const aiDeviceHealthAllMock = [];

// 单设备 AI 健康评分（离线无落库）
export const aiDeviceHealthMock = null;

// AI 设备健康强制刷新回执
export const aiDeviceHealthRefreshMock = {
  analyzedCount: 0,
  failedCount: 0,
  elapsedMs: 0,
};

// 任务运行时监控：基于 taskId 散列出稳定的阶段与指标

/** 把任意 id 转成稳定种子 */
function seedFromId(id) {
  return String(id ?? "")
    .split("")
    .reduce((s, c) => s + c.charCodeAt(0), 0);
}

/** 任务运行阶段桶（RUNNING 权重大，命中率高） */
const TASK_STATUS_BUCKETS = ["RUNNING", "RUNNING", "SUCCESS", "FAILED", "PENDING"];

/**
 * 任务运行时 mock —— 与后端 TaskRuntimeDto 字段对齐
 * @param {string|number} id 任务 ID
 */
export function getTaskRuntimeMock(id) {
  const seed = seedFromId(id);
  const status = TASK_STATUS_BUCKETS[seed % TASK_STATUS_BUCKETS.length];
  const startedAt = new Date(
    Date.now() - (10 + (seed % 47)) * 60 * 1000,
  ).toISOString();
  const isRunning = status === "RUNNING";
  const elapsedSec = isRunning
    ? Math.floor((Date.now() - new Date(startedAt).getTime()) / 1000)
    : 120 + (seed % 360);
  const finishedAt = isRunning
    ? null
    : new Date(new Date(startedAt).getTime() + elapsedSec * 1000).toISOString();
  const baseCpu = 18 + (seed % 33);
  const baseGpu = 12 + ((seed >> 1) % 48);
  return {
    status,
    cpu: status === "FAILED" ? Math.min(99, baseCpu + 12) : baseCpu,
    gpu: status === "RUNNING" ? baseGpu : Math.max(0, baseGpu - 8),
    memMB: 480 + (seed % 920),
    startedAt,
    finishedAt,
    duration: elapsedSec,
    retries: status === "FAILED" ? 1 + (seed % 3) : seed % 2,
    throughput: status === "RUNNING" ? 4 + (seed % 9) : 0,
  };
}

// 任务执行日志：固定模板按 id 派生稳定序列

const LOG_SAMPLES = [
  { level: "INFO", message: "已接收任务，进入排队" },
  { level: "INFO", message: "开始拉取灾害点最新栅格与传感器数据" },
  { level: "DEBUG", message: "加载 XGBoost fusion 模型，特征维度 18" },
  { level: "INFO", message: "调用 AI 推理服务（融合模型）" },
  { level: "WARN", message: "输入图像 1 张异常（云覆盖率 > 35%）" },
  { level: "INFO", message: "识别完成，候选灾害数 3，已打分" },
  { level: "DEBUG", message: "查询人工确认队列占用，正常" },
  { level: "INFO", message: "推送 1 条结果至 confirmation queue" },
  { level: "INFO", message: "调度器下次巡检将在 5 分钟后自动触发" },
  { level: "WARN", message: "网络抖动，已自动重试 1 次" },
  { level: "INFO", message: "同步本次推理结果到预警中心" },
  { level: "ERR", message: "与气象 overlay 服务握手失败，等待重连" },
  { level: "INFO", message: "推理结束，资源释放完成" },
  { level: "DEBUG", message: "缓存命中：灾害点 HZ-001 近 24h 监测数据" },
  { level: "INFO", message: "完成识别 1 张灾害候选，已上报中心" },
  { level: "WARN", message: "检测阈值收敛中，置信度暂为 0.62" },
  { level: "INFO", message: "队列深度 2，调度顺利" },
  { level: "DEBUG", message: "模型版本 v1.7.0，权重已加载到 GPU:0" },
  { level: "INFO", message: "本轮任务耗时 02:48" },
  { level: "ERR", message: "任务执行超时，已自动终止" },
];

/**
 * 任务执行日志 mock —— 按 id 散列出 8-20 条稳定记录
 * @param {string|number} id
 */
export function getTaskExecutionLogMock(id) {
  const seed = seedFromId(id);
  const count = 8 + (seed % 13); // 8~20
  const baseTs = new Date(Date.now() - count * 30 * 1000).getTime();
  const entries = [];
  for (let i = 0; i < count; i += 1) {
    const pick = LOG_SAMPLES[(seed + i * 3) % LOG_SAMPLES.length];
    const ts = new Date(baseTs + i * 30 * 1000).toISOString();
    entries.push({
      ts: ts.slice(11, 19),
      level: pick.level,
      message: pick.message,
    });
  }
  return {
    entries,
    cursor: new Date().toISOString(),
  };
}

// AI 模型族统计离线骨架（在线模式无落库）

export const modelFamilyStatsMock = {
  onlineCount: 0,
  todayInferences: 0,
  avgLatency: 0,
  综合F1: "—",
};

// AI 训练任务列表（离线空集）
export const trainingJobsMock = [];

// AI 训练日志空壳
export const trainingJobLogMock = { entries: [], cursor: null };

// P1 灾害预测 + 损失评估 mock

/**
 * 时序预测 mock —— 基于 deviceCode + horizon 派生稳定序列
 * 对应后端 ForecastResult
 */
export function getDisasterForecastMock({ deviceCode, metric = 'displacement', horizon = 24 } = {}) {
  const seed = seedFromId(deviceCode);
  // 基础值 + 趋势 + 周期性
  const base = 10 + (seed % 20);
  const trend = 0.05;
  const predictions = [];
  for (let i = 0; i < horizon; i++) {
    predictions.push(
      parseFloat((base + trend * i + Math.sin(i * 0.4 + seed) * 1.5 + (Math.random() - 0.5) * 0.5).toFixed(3)),
    );
  }
  return {
    deviceCode: deviceCode || 'DEV-001',
    metric,
    horizon,
    predictions,
    confidence: parseFloat((0.72 + (seed % 20) / 100).toFixed(3)),
    model: seed % 3 === 0 ? 'lstm-v1' : 'heuristic-moving-average',
    degraded: seed % 3 === 0 ? false : true,
  };
}

/**
 * 融合风险 mock —— 对应后端 FusionRiskResult
 */
export function getFusionRiskMock(hazardCode) {
  const seed = seedFromId(hazardCode);
  const score = 20 + (seed % 60);
  const level = score >= 75 ? 'CRITICAL' : score >= 55 ? 'HIGH' : score >= 35 ? 'MEDIUM' : 'LOW';
  return {
    hazardCode: hazardCode || 'HZ-001',
    riskLevel: level,
    riskScore: parseFloat(score.toFixed(1)),
    confidence: parseFloat((0.70 + (seed % 25) / 100).toFixed(3)),
    model: seed % 2 === 0 ? 'xgboost-fusion-v3' : 'heuristic-geo-weather',
    degraded: seed % 2 === 0 ? false : true,
  };
}

/**
 * 灾害评估 mock —— 对应后端 DisasterAssessmentResponseDto
 */
export function getDisasterAssessmentMock(hazardCode) {
  const seed = seedFromId(hazardCode);
  const score = 15 + (seed % 75);
  const riskLevel = score >= 75 ? 'CRITICAL' : score >= 55 ? 'HIGH' : score >= 35 ? 'MEDIUM' : 'LOW';
  const concernLabel = { LOW: '稳定', MEDIUM: '关注', HIGH: '预警', CRITICAL: '异常严重' }[riskLevel];
  const disasterTypes = ['滑坡', '崩塌', '泥石流', '地裂缝', '地面沉降', '塌陷'];
  const type = disasterTypes[seed % disasterTypes.length];
  const reasoning = [
    `近 7 日累计降雨 ${(20 + seed % 80).toFixed(1)} mm，土壤含水量偏高`,
    `坡度 ${(25 + seed % 45).toFixed(0)}°，属于${seed % 2 === 0 ? '中等' : '高易发'}坡度`,
    `${type}历史记录 ${seed % 3 + 1} 次，区域风险累积`,
  ];
  const suggestedActions = [
    '立即启动应急预案',
    '加密地表位移监测频次',
    '通知受威胁群众做好转移准备',
    '调度救援队伍进入待命状态',
  ];
  return {
    hazardCode: hazardCode || 'HZ-001',
    overall: {
      riskLevel,
      riskScore: parseFloat(score.toFixed(1)),
      confidence: parseFloat((0.68 + (seed % 28) / 100).toFixed(3)),
      concernLabel,
      model: seed % 3 === 0 ? 'xgb-seven-dimension-v2' : 'heuristic-seven-dimension',
      mock: seed % 3 !== 0,
      deviceAvailable: seed % 2 === 0,
    },
    disasterType: {
      disasterType: type,
      source: seed % 2 === 0 ? 'declared' : 'inferred',
      confidence: parseFloat((0.65 + (seed % 30) / 100).toFixed(3)),
      reason: `基于历史灾害记录与实时监测数据综合判定`,
    },
    dimensions: {
      geographic: { score: parseFloat((30 + seed % 35).toFixed(1)), weight: 0.30 },
      precipitation: { score: parseFloat((20 + seed % 50).toFixed(1)), weight: 0.25 },
      sensor: { score: parseFloat((15 + seed % 60).toFixed(1)), weight: 0.20 },
      historical: { score: parseFloat((25 + seed % 45).toFixed(1)), weight: 0.15 },
      image: { score: parseFloat((10 + seed % 55).toFixed(1)), weight: 0.10 },
    },
    reasoning,
    suggestedActions: suggestedActions.slice(0, 2 + (seed % 3)),
    model: seed % 3 === 0 ? 'xgb-seven-dimension-v2' : 'heuristic-seven-dimension',
    mock: seed % 3 !== 0,
    deviceAvailable: seed % 2 === 0,
  };
}

/**
 * 灾害评估字典自检 mock —— 对应后端 status 字典
 */
export const disasterAssessmentStatusMock = {
  disasterTypes: ['滑坡', '崩塌', '泥石流', '地裂缝', '地面沉降', '塌陷'],
  concernLabels: { LOW: '稳定', MEDIUM: '关注', HIGH: '预警', CRITICAL: '异常严重' },
  riskLevels: ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'],
};

/**
 * 全国独立测算 mock —— 对应后端 IndependentAssessmentResponseDto
 */
export function runIndependentAssessmentMock(payload = {}) {
  const points = payload?.points ?? [];
  const results = points.map((pt) => {
    const seed = seedFromId(`${pt.lon}-${pt.lat}`);
    const score = 10 + (seed % 80);
    const riskLevel = score >= 75 ? 'CRITICAL' : score >= 55 ? 'HIGH' : score >= 35 ? 'MEDIUM' : 'LOW';
    return {
      lon: pt.lon,
      lat: pt.lat,
      regionCode: pt.regionCode || null,
      regionName: pt.regionName || null,
      riskLevel,
      riskScore: parseFloat(score.toFixed(1)),
      concernLabel: { LOW: '稳定', MEDIUM: '关注', HIGH: '预警', CRITICAL: '异常严重' }[riskLevel],
      disasterType: ['滑坡', '崩塌', '泥石流', '地裂缝', '待定'][seed % 5],
      confidence: parseFloat((0.60 + (seed % 35) / 100).toFixed(3)),
      geographicScore: parseFloat((20 + seed % 55).toFixed(1)),
      precipitationScore: parseFloat((15 + (seed >> 1) % 60).toFixed(1)),
      deviceAvailable: seed % 2 === 0,
      reasoning: [
        `坐标 [${pt.lon.toFixed(3)}, ${pt.lat.toFixed(3)}] 综合风险评分 ${score.toFixed(1)}`,
        `地形因子得分 ${(20 + seed % 55).toFixed(1)}，降水因子得分 ${(15 + (seed >> 1) % 60).toFixed(1)}`,
      ],
      suggestedActions: score >= 55
        ? ['启动预警发布流程', '通知应急指挥']
        : ['持续监测'],
      model: seed % 4 === 0 ? 'xgb-seven-dimension-v2' : 'heuristic-seven-dimension',
      mockWeatherUsed: payload?.mockWeather !== false,
    };
  });
  return {
    totalPoints: points.length,
    detectedCount: results.filter((r) => r.riskLevel !== 'LOW').length,
    results,
    executionMs: 50 + points.length * 8,
  };
}
