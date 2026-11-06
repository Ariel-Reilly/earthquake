package cn.disasterguard.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * AI /forecast 响应数据（与 ai-service Pydantic ForecastResponse 对齐）。
 *
 * <p>ai-service 返回 snake_case（device_code / predictions），
 * 用 {@link JsonAlias} 做反序列化别名；序列化输出统一 camelCase。
 *
 * <p>此类为可变类（setDegraded），供 Java 控制器在合成数据场景下注入 degraded 标志。
 */
public class ForecastResult {

  @JsonAlias("device_code")
  private String deviceCode;

  private String metric;
  private int horizon;
  private java.util.List<Double> predictions;
  private double confidence;
  private String model;

  @JsonAlias("mock")
  private boolean degraded;

  public ForecastResult() {}

  public ForecastResult(String deviceCode, String metric, int horizon,
                       java.util.List<Double> predictions, double confidence,
                       String model, boolean degraded) {
    this.deviceCode = deviceCode;
    this.metric = metric;
    this.horizon = horizon;
    this.predictions = predictions;
    this.confidence = confidence;
    this.model = model;
    this.degraded = degraded;
  }

  public String deviceCode() { return deviceCode; }
  public String metric() { return metric; }
  public int horizon() { return horizon; }
  public java.util.List<Double> predictions() { return predictions; }
  public double confidence() { return confidence; }
  public String model() { return model; }
  public boolean degraded() { return degraded; }

  public void setDeviceCode(String v) { this.deviceCode = v; }
  public void setMetric(String v) { this.metric = v; }
  public void setHorizon(int v) { this.horizon = v; }
  public void setPredictions(java.util.List<Double> v) { this.predictions = v; }
  public void setConfidence(double v) { this.confidence = v; }
  public void setModel(String v) { this.model = v; }
  public void setDegraded(boolean v) { this.degraded = v; }
}
