package cn.geosentry.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

/**
 * AI /detect 响应数据（与 ai-service Pydantic DetectResponse 对齐）。
 *
 * <p>ai-service 返回 snake_case（processed_image_url / detection_count / class_name / bbox），
 * 用 {@link JsonAlias} 做反序列化别名；序列化输出统一 camelCase。
 */
public record DetectionResult(
    List<Detection> detections,
    @JsonAlias("processed_image_url") String processedImageUrl,
    String model,
    @JsonAlias("mock") boolean degraded,
    @JsonAlias("detection_count") int detectionCount
) {
  public record Detection(
      @JsonAlias("class_name") String className,
      double confidence,
      List<Double> bbox
  ) {
  }
}
