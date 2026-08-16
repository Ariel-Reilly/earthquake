package cn.geosentry.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AI /fusion-risk 响应数据（与 ai-service Pydantic FusionResponse 对齐）。
 *
 * @param hazardCode  灾害点编码（原样回传）
 * @param riskLevel   风险等级（LOW / MEDIUM / HIGH / CRITICAL）
 * @param riskScore   风险评分（0-100，来自 ai-service risk_score）
 * @param confidence  置信度（0.0-1.0）
 * @param model       使用的模型名称
 * @param degraded    true 表示 ai-service 返回的是 mock / 降级结果
 */
public record FusionRiskResult(
    @JsonProperty("hazard_code") String hazardCode,
    @JsonProperty("risk_level") String riskLevel,
    @JsonProperty("risk_score") double riskScore,
    @JsonProperty("confidence") double confidence,
    @JsonProperty("model") String model,
    @JsonProperty("mock") boolean degraded
) {
}
