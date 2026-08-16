package cn.geosentry.ai.dto;

import java.util.List;

/** AI /detect 响应数据。 */
public record DetectionResult(
    List<Detection> detections,
    String processedImageUrl,
    String model
) {
  public record Detection(String className, double confidence, List<Double> bbox) {
  }
}
