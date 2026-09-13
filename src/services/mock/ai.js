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

// =====================================================================
// AI 综合分析：从 hazardPoints 派生完整列表（v1.3.0+ 落库结构）
// =====================================================================

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

// =====================================================================
// 任务运行时监控：基于 taskId 散列出稳定的阶段与指标
// =====================================================================

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

// =====================================================================
// 任务执行日志：固定模板按 id 派生稳定序列
// =====================================================================

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
      ts: ts.slice(11, 19), // HH:MM:SS
      level: pick.level,
      message: pick.message,
    });
  }
  return {
    entries,
    cursor: new Date().toISOString(),
  };
}

// =====================================================================
// AI 模型族统计离线骨架（在线模式无落库）
// =====================================================================

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
