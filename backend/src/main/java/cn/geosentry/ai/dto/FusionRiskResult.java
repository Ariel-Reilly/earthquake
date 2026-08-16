package cn.geosentry.ai.dto;

/** AI /fusion-risk 响应数据。 */
public record FusionRiskResult(
    String riskLevel,
    double probability,
    String model
) {
}
