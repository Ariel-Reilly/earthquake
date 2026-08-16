package cn.geosentry.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * AI /anomaly 响应数据（与 ai-service Pydantic AnomalyResponse 对齐）。
 */
public record AnomalyResult(
    @JsonProperty("device_code") String deviceCode,
    @JsonProperty("is_anomaly") boolean isAnomaly,
    @JsonProperty("anomaly_indices") List<Integer> anomalyIndices,
    @JsonProperty("score") double score,
    @JsonProperty("model") String model,
    @JsonProperty("mock") boolean degraded
) {
}
