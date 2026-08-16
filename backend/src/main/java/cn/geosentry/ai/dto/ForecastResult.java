package cn.geosentry.ai.dto;

import java.util.List;

/** AI /forecast 响应数据。 */
public record ForecastResult(
    String deviceCode,
    String metric,
    int horizon,
    List<ForecastPoint> forecast,
    String model
) {
  public record ForecastPoint(String date, double value, double lower, double upper) {
  }
}
