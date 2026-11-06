package cn.disasterguard.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;
import java.util.Map;

/**
 * 单台设备 AI 健康评分响应（Python FastAPI 端 /api/ai/device-health/score 返回值）。
 *
 * <p>字段命名 snake_case → camelCase 通过 {@code @JsonAlias} 映射。
 */
public record DeviceHealthResponseDto(
    @JsonAlias("device_code") String deviceCode,
    @JsonAlias("health_score") Double healthScore,
    @JsonAlias("health_level") String healthLevel,
    @JsonAlias("anomaly_score") Double anomalyScore,
    @JsonAlias("forecast_risk") Double forecastRisk,
    @JsonAlias("metric_coverage") Integer metricCoverage,
    @JsonAlias("anomaly_by_metric") Map<String, Double> anomalyByMetric,
    List<String> reasoning,
    @JsonAlias("suggested_actions") List<String> suggestedActions,
    Double confidence,
    String model,
    Boolean degraded,
    @JsonAlias("analyzed_at") String analyzedAt,
    @JsonAlias("last_anomaly_at") String lastAnomalyAt
) {}
