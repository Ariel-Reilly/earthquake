package cn.disasterguard.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

/**
 * AI /anomaly 响应数据（与 ai-service Pydantic AnomalyResponse 对齐）。
 *
 * <p>ai-service 返回 snake_case（device_code / is_anomaly / anomaly_indices），
 * 用 {@link JsonAlias} 做反序列化别名；序列化输出统一 camelCase。
 */
public record AnomalyResult(
    @JsonAlias("device_code") String deviceCode,
    @JsonAlias("is_anomaly") boolean isAnomaly,
    @JsonAlias("anomaly_indices") List<Integer> anomalyIndices,
    double score,
    String model,
    @JsonAlias("mock") boolean degraded
) {
}
