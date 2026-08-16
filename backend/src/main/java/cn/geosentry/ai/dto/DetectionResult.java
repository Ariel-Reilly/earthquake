package cn.geosentry.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * AI /detect 响应数据（与 ai-service Pydantic DetectResponse 对齐）。
 */
public record DetectionResult(
    @JsonProperty("detections") List<Detection> detections,
    @JsonProperty("processed_image_url") String processedImageUrl,
    @JsonProperty("model") String model,
    @JsonProperty("mock") boolean degraded,
    @JsonProperty("detection_count") int detectionCount
) {
  public record Detection(
      @JsonProperty("class_name") String className,
      @JsonProperty("confidence") double confidence,
      @JsonProperty("bbox") List<Double> bbox
  ) {
  }
}
