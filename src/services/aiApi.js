/**
 * AI 服务客户端：封装 /api/ai/* 四个端点调用。
 *
 * 前端不直接访问 ai-service，统一走后端网关（后端在 AI 不可用时自动降级）。
 */
import { getAuthToken } from "../stores/auth";

const API_BASE =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8081/api";

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
    if (!resp.ok) throw new Error(`AI detect ${resp.status}`);
    const payload = await resp.json();
    if (payload?.code !== 0) throw new Error(payload?.message || "AI detect failed");
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

  /** 查询 AI 分析历史（针对某个 deviceCode/hazardCode）。 */
  async listRecords(businessType, businessCode) {
    return request(`/ai/records?businessType=${businessType}&businessCode=${encodeURIComponent(businessCode)}`);
  },
};
