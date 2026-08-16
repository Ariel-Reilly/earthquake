package cn.geosentry.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * AI /forecast 响应数据（与 ai-service Pydantic ForecastResponse 对齐）。
 * ai-service 返回 predictions:List[float]（纯数值预测）+ confidence，
 * 不含日期或置信区间——后端在展示时自行生成日期序列。
 */
public record ForecastResult(
    @JsonProperty("device_code") String deviceCode,
    @JsonProperty("metric") String metric,
    @JsonProperty("horizon") int horizon,
    @JsonProperty("predictions") List<Double> predictions,
    @JsonProperty("confidence") double confidence,
    @JsonProperty("model") String model,
    @JsonProperty("mock") boolean degraded
) {
}
