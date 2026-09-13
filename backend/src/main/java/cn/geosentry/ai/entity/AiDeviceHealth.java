package cn.geosentry.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * AI 设备健康评分（JPA 实体）。
 *
 * <p>由 AiDeviceHealthService 每 5 分钟全量刷新一次，UPSERT 到此表。
 * 前端所有展示设备健康度的页面（DevicesPage / DeviceDetailPage / 概览 / 助手排行）都消费这张表。
 *
 * <p>表结构见 {@code schema.sql} 的 {@code ai_device_health}。
 */
@Entity
@Table(name = "ai_device_health")
public class AiDeviceHealth {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "device_code", nullable = false, unique = true)
  private String deviceCode;

  @Column(name = "health_level", nullable = false)
  private String healthLevel;

  @Column(name = "health_score", nullable = false)
  private Double healthScore;

  @Column(name = "anomaly_score")
  private Double anomalyScore;

  @Column(name = "forecast_risk")
  private Double forecastRisk;

  @Column(name = "metric_coverage", nullable = false)
  private Integer metricCoverage = 0;

  @Column(name = "last_anomaly_at")
  private OffsetDateTime lastAnomalyAt;

  @Column(name = "dimensions_json", columnDefinition = "TEXT")
  private String dimensionsJson;

  @Column(name = "reasoning_json", columnDefinition = "TEXT")
  private String reasoningJson;

  @Column(name = "suggested_actions_json", columnDefinition = "TEXT")
  private String suggestedActionsJson;

  @Column(name = "confidence")
  private Double confidence;

  @Column(name = "model")
  private String model;

  @Column(name = "degraded", nullable = false)
  private boolean degraded = false;

  @Column(name = "analyzed_at", nullable = false)
  private OffsetDateTime analyzedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getDeviceCode() { return deviceCode; }
  public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
  public String getHealthLevel() { return healthLevel; }
  public void setHealthLevel(String healthLevel) { this.healthLevel = healthLevel; }
  public Double getHealthScore() { return healthScore; }
  public void setHealthScore(Double healthScore) { this.healthScore = healthScore; }
  public Double getAnomalyScore() { return anomalyScore; }
  public void setAnomalyScore(Double anomalyScore) { this.anomalyScore = anomalyScore; }
  public Double getForecastRisk() { return forecastRisk; }
  public void setForecastRisk(Double forecastRisk) { this.forecastRisk = forecastRisk; }
  public Integer getMetricCoverage() { return metricCoverage; }
  public void setMetricCoverage(Integer metricCoverage) { this.metricCoverage = metricCoverage; }
  public OffsetDateTime getLastAnomalyAt() { return lastAnomalyAt; }
  public void setLastAnomalyAt(OffsetDateTime lastAnomalyAt) { this.lastAnomalyAt = lastAnomalyAt; }
  public String getDimensionsJson() { return dimensionsJson; }
  public void setDimensionsJson(String dimensionsJson) { this.dimensionsJson = dimensionsJson; }
  public String getReasoningJson() { return reasoningJson; }
  public void setReasoningJson(String reasoningJson) { this.reasoningJson = reasoningJson; }
  public String getSuggestedActionsJson() { return suggestedActionsJson; }
  public void setSuggestedActionsJson(String suggestedActionsJson) { this.suggestedActionsJson = suggestedActionsJson; }
  public Double getConfidence() { return confidence; }
  public void setConfidence(Double confidence) { this.confidence = confidence; }
  public String getModel() { return model; }
  public void setModel(String model) { this.model = model; }
  public boolean isDegraded() { return degraded; }
  public void setDegraded(boolean degraded) { this.degraded = degraded; }
  public OffsetDateTime getAnalyzedAt() { return analyzedAt; }
  public void setAnalyzedAt(OffsetDateTime analyzedAt) { this.analyzedAt = analyzedAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
