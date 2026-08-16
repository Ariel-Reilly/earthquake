package cn.geosentry.ai.dto;

/** AI /anomaly 响应数据。 */
public record AnomalyResult(
    boolean isAnomaly,
    double anomalyScore,
    String reason,
    String model
) {
}
