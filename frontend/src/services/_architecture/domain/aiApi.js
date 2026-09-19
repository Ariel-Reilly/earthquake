/**
 * AI 服务客户端：封装 /api/ai/* 四个端点调用。
 *
 * 前端不直接访问 ai-service，统一走后端网关（后端在 AI 不可用时自动降级）。
 */
import { getAuthToken } from "../../../stores/auth.js";

const API_BASE =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8080/api";

async function request(path, options = {}) {
  const token = getAuthToken();
  const headers = {
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(options.headers || {}),
  };
  const resp = await fetch(`${API_BASE}${path}`, { ...options, headers });
  if (!resp.ok) {
    throw new Error(`AI API ${resp.status}: ${resp.statusText}`);
  }
  const payload = await resp.json().catch(() => null);
  if (payload?.code !== 0) {
    throw new Error(payload?.message || "AI 服务返回异常");
  }
  return payload.data;
}

export const aiApi = {
  /**
   * 图像识别（巡检照片上传）。
   * @param {File} file 图片文件
   * @param {string} [modelType='yolo'] 模型类型，预留扩展
   * @returns {Promise<{detections, model, mock, detection_count}>}
   */
  async detect(file, modelType = "yolo") {
    const token = getAuthToken();
    const form = new FormData();
    form.append("file", file);
    form.append("modelType", modelType);
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const resp = await fetch(`${API_BASE}/ai/detect`, {
      method: "POST",
      headers,
      body: form,
    });
    if (!resp.ok) {throw new Error(`AI detect ${resp.status}`);}
    const payload = await resp.json();
    if (payload?.code !== 0)
      {throw new Error(payload?.message || "AI detect failed");}
    return payload.data;
  },

  /**
   * 时序预测（设备位移/雨量趋势）。
   * @param {string} deviceCode
   * @param {string} metric
   * @param {number} horizon 预测步数
   * @param {number[]} history 历史值序列
   */
  async forecast(deviceCode, metric, horizon = 7, history) {
    return request("/ai/forecast", {
      method: "POST",
      body: JSON.stringify({ deviceCode, metric, horizon, history }),
    });
  },

  /**
   * 异常检测（设备最近数据窗口）。
   * @param {string} deviceCode
   * @param {number[]} data
   */
  async anomaly(deviceCode, data) {
    return request("/ai/anomaly", {
      method: "POST",
      body: JSON.stringify({ deviceCode, data }),
    });
  },

  /**
   * 融合风险评分（预警中心/规则引擎联动）。
   * @param {string} hazardCode
   * @param {Record<string, number>} factors 风险因子字典
   */
  async fusionRisk(hazardCode, factors) {
    return request("/ai/fusion-risk", {
      method: "POST",
      body: JSON.stringify({ hazardCode, factors }),
    });
  },

  /**
   * 融合风险评分（自动从后端拉取真实设备指标 + hazard 关联数据聚合因子）。
   * @param {string} hazardCode 灾害点编码
   */
  async fusionRiskAuto(hazardCode) {
    const token = getAuthToken();
    const resp = await fetch(
      `${API_BASE}/ai/fusion-risk/auto?hazardCode=${encodeURIComponent(hazardCode)}`,
      { headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) } },
    );
    if (!resp.ok) {throw new Error(`AI fusion-risk/auto ${resp.status}`);}
    const payload = await resp.json();
    if (payload?.code !== 0)
      {throw new Error(payload?.message || "AI fusion failed");}
    return payload.data;
  },

  /**
   * 时序预测（自动从后端拉取设备历史数据）。
   * @param {string} deviceCode
   * @param {string} [metric='displacement']
   * @param {number} [horizon=7] 预测步数
   * @param {number} [historyLimit=30] 拉取历史条数
   */
  async forecastAuto(
    deviceCode,
    metric = "displacement",
    horizon = 7,
    historyLimit = 30,
  ) {
    const token = getAuthToken();
    const params = new URLSearchParams({
      deviceCode,
      metric,
      horizon,
      historyLimit,
    });
    const resp = await fetch(`${API_BASE}/ai/forecast/auto?${params}`, {
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    });
    if (!resp.ok) {throw new Error(`AI forecast/auto ${resp.status}`);}
    const payload = await resp.json();
    if (payload?.code !== 0)
      {throw new Error(payload?.message || "AI forecast failed");}
    return payload.data;
  },

  /** 查询 AI 分析历史（针对某个 deviceCode/hazardCode）。 */
  async listRecords(businessType, businessCode) {
    return request(
      `/ai/analysis?businessType=${businessType}&businessCode=${encodeURIComponent(businessCode)}`,
    );
  },

  /**
   * 综合风险分析（自动从 DB 聚合图像/降水/地理/设备四维度因子 + AI 推理）。
   * 适用于"AI 综合分析"页面的单灾害点报告（实时推理，不读落库表）。
   * @param {string} hazardCode 灾害点编码
   * @returns {Promise<{
   *   hazardCode, hazardName, analyzedAt,
   *   overall: { riskLevel, riskScore, confidence, model, mock },
   *   dimensions: { image, precipitation, geographic, device },
   *   reasoning: string[],
   *   suggestedActions: string[],
   *   model, degraded
   * }>}
   */
  async comprehensiveAnalysis(hazardCode) {
    const token = getAuthToken();
    const resp = await fetch(
      `${API_BASE}/ai/comprehensive-analysis?hazardCode=${encodeURIComponent(hazardCode)}`,
      { headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) } },
    );
    if (!resp.ok) {throw new Error(`AI comprehensive-analysis ${resp.status}`);}
    const payload = await resp.json();
    if (payload?.code !== 0)
      {throw new Error(payload?.message || "AI 综合分析失败");}
    return payload.data;
  },

  /**
   * 综合分析排行榜（来自 DB 落库数据，每 5 分钟由 scheduler 刷新）。
   * 适用于"AI 综合分析"页面"一键分析"完成后 + 地脉智巡助手"哪些灾害点最危险"。
   * @returns {Promise<Array<{
   *   hazardCode, riskLevel, riskScore, confidence, model, analyzedAt,
   *   dimensions, reasoning, suggestedActions
   * }>>}
   */
  async comprehensiveAnalysisAll() {
    const token = getAuthToken();
    const resp = await fetch(`${API_BASE}/ai/comprehensive-analysis/all`, {
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    });
    if (!resp.ok) {throw new Error(`AI comprehensive-analysis/all ${resp.status}`);}
    const payload = await resp.json();
    if (payload?.code !== 0)
      {throw new Error(payload?.message || "AI 综合分析列表失败");}
    return payload.data ?? [];
  },

  /** 强制刷新所有 hazard 的综合分析（管理端"一键分析"按钮）。 */
  async comprehensiveAnalysisRefresh() {
    const token = getAuthToken();
    const resp = await fetch(`${API_BASE}/ai/comprehensive-analysis/refresh`, {
      method: "POST",
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    });
    if (!resp.ok) {throw new Error(`AI refresh ${resp.status}`);}
    const payload = await resp.json();
    if (payload?.code !== 0)
      {throw new Error(payload?.message || "AI 刷新失败");}
    return payload.data;
  },

  /**
   * 自然语言聊天入口（给地脉智巡助手用）。
   *
   * demo 阶段后端用关键词分发：
   *   - "分析 GS-001" → 返回该灾害点最新综合分析缓存 + reasoning + 建议
   *   - "哪些灾害点最危险" → 返回 TOP 5 排行榜
   *   - "应急/紧急" → 返回应急派单入口
   * @param {string} message 用户输入
   * @returns {Promise<{ intent, reply, attachment }>}
   */
  async chat(message) {
    const token = getAuthToken();
    const resp = await fetch(`${API_BASE}/ai/chat`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ message }),
    });
    if (!resp.ok) {throw new Error(`AI chat ${resp.status}`);}
    const payload = await resp.json();
    if (payload?.code !== 0)
      {throw new Error(payload?.message || "AI 聊天失败");}
    return payload.data;
  },

  /**
   * 灾害评估 v1.3.0 —— 单 hazard 的 AI 单独测算。
   *
   * <p>基于"当地地理状况 + 当地天气状况"，不强制依赖监测设备；
   * 返回三件套 risk_level + concern_label + disaster_type + reasoning + suggestedActions。
   * 不读 ai_comprehensive_score 落库表——用于"立即重算"按钮的单次推理场景。
   *
   * @param {string} hazardCode 灾害点编码
   */
  async disasterAssessment(hazardCode) {
    const token = getAuthToken();
    const resp = await fetch(
      `${API_BASE}/ai/disaster-assessment?hazardCode=${encodeURIComponent(hazardCode)}`,
      { headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) } },
    );
    if (!resp.ok) {throw new Error(`AI disaster-assessment ${resp.status}`);}
    const payload = await resp.json();
    if (payload?.code !== 0)
      {throw new Error(payload?.message || "AI 灾害评估失败");}
    return payload.data;
  },

  /** 获取灾害评估字典（六大类型 + 关注度标签 + 风险等级） */
  async disasterAssessmentStatus() {
    const token = getAuthToken();
    const resp = await fetch(`${API_BASE}/ai/disaster-assessment/status`, {
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    });
    if (!resp.ok) {throw new Error(`AI disaster-assessment/status ${resp.status}`);}
    const payload = await resp.json();
    return payload.data ?? {};
  },

  /**
   * 提交 AI 反馈（v1.4.0）：告诉后端"AI 判定的实际事件是否发生"。
   *
   * @param {object} payload
   * @param {string} payload.hazardCode
   * @param {string} payload.predictedLevel - LOW / MEDIUM / HIGH / CRITICAL
   * @param {number} payload.predictedScore - AI 当时打的风险分
   * @param {string} payload.predictedAt - ISO 8601
   * @param {string} payload.outcome - true_positive / true_negative / false_positive / false_negative
   * @param {boolean} payload.occurred
   * @param {number} [payload.confidence]
   * @param {string} [payload.feedbackBy]
   * @param {string} [payload.note]
   */
  async submitFeedback(payload) {
    const token = getAuthToken();
    const resp = await fetch(`${API_BASE}/ai/feedback`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(payload),
    });
    if (!resp.ok) {throw new Error(`AI feedback ${resp.status}`);}
    const json = await resp.json();
    if (json?.code !== 0) {throw new Error(json?.message || "AI 反馈提交失败");}
    return json.data;
  },

  /**
   * 获取反馈统计（混淆矩阵 + precision/recall/F1）。
   * @returns {Promise<{total_records, by_predicted_level, confusion_matrix, precision, recall, f1, total_feedback, support, adaptive_normalizer}>}
   */
  async getFeedbackStats() {
    // 后端暂未实现 GET /ai/feedback/stats，直接调 Python 端点
    const AI_BASE =
      import.meta.env.VITE_AI_SERVICE_BASE ||
      import.meta.env.VITE_AI_SERVICE_BASE_URL ||
      "http://localhost:8001/api/ai";
    const resp = await fetch(`${AI_BASE}/feedback/stats`);
    if (!resp.ok) {throw new Error(`AI feedback/stats ${resp.status}`);}
    const json = await resp.json();
    return json.data ?? {};
  },

  /**
   * 查询当前 XGBoost fusion 模型状态：是否加载、训练时间、验证集指标、
   * 特征重要性、历史版本列表。供前端「真实模型/启发式」徽标使用。
   * @returns {Promise<{model_loaded, model_path, version, trained_at, val_metrics, feature_importance, history_files, ...}>}
   */
  async getModelInfo() {
    const token = getAuthToken();
    const resp = await fetch(`${API_BASE}/ai/model/info`, {
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    });
    if (!resp.ok) {throw new Error(`AI model/info ${resp.status}`);}
    const json = await resp.json();
    if (json?.code !== 0) {throw new Error(json?.message || "AI 模型信息查询失败");}
    return json.data ?? {};
  },

  /** 重载磁盘模型（训练后热加载）。 */
  async reloadModel() {
    const token = getAuthToken();
    const resp = await fetch(`${API_BASE}/ai/model/reload`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
    if (!resp.ok) {throw new Error(`AI model/reload ${resp.status}`);}
    const json = await resp.json();
    if (json?.code !== 0) {throw new Error(json?.message || "AI 模型重载失败");}
    return json.data ?? {};
  },
};
