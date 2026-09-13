package cn.geosentry.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * AI /fusion-risk 响应数据（与 ai-service Pydantic FusionResponse 对齐）。
 *
 * <p>ai-service 返回 snake_case（hazard_code / risk_level / risk_score / mock），
 * 用 {@link JsonAlias} 做反序列化别名（仅接收，不影响序列化）；
 * 向前端输出时统一使用 Java 字段名（camelCase：hazardCode / riskLevel / riskScore / degraded），
 * 与前端既有调用约定保持一致。
 *
 * @param hazardCode  灾害点编码（原样回传）
 * @param riskLevel   风险等级（LOW / MEDIUM / HIGH / CRITICAL）
 * @param riskScore   风险评分（0-100）
 * @param confidence  置信度（0.0-1.0）
 * @param model       使用的模型名称
 * @param degraded    true 表示 ai-service 返回的是 mock / 降级结果（Python 端字段名 mock）
 */
public record FusionRiskResult(
    @JsonAlias("hazard_code") String hazardCode,
    @JsonAlias("risk_level") String riskLevel,
    @JsonAlias("risk_score") double riskScore,
    double confidence,
    String model,
    @JsonAlias("mock") boolean degraded
) {
}
