package cn.geosentry.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * AI 综合分析结果落库表（每 hazard 一条最新分数 + 历史回溯留 view）。
 *
 * <p>数据流：{@code AiComprehensiveScheduler} 每 5 分钟拉所有 hazard 跑综合分析，
 * UPSERT 到这张表。预警引擎、巡检优先级、数据分析面板都从这里读。
 */
@Entity
@Table(name = "ai_comprehensive_score")
public class AiComprehensiveScore {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "hazard_code", nullable = false, unique = true)
  private String hazardCode;

  /** LOW / MEDIUM / HIGH / CRITICAL */
  @Column(name = "risk_level", nullable = false)
  private String riskLevel;

  @Column(name = "risk_score", nullable = false)
  private Double riskScore;

  /** AI_AUTO / SCHEDULER / MANUAL */
  @Column(name = "source", nullable = false)
  private String source;

  /** 灾害类型：滑坡 / 崩塌 / 泥石流 / 地裂缝 / 地面沉降 / 塌陷 */
  @Column(name = "disaster_type", length = 32)
  private String disasterType;

  /** 中文业务等级：稳定 / 关注 / 预警 / 异常严重 */
  @Column(name = "concern_label", length = 32)
  private String concernLabel;

  /** 灾害类型来源：declared（档案已标）/ inferred（AI 按特征推断）/ fallback（兜底"其他"） */
  @Column(name = "disaster_type_source", length = 32)
  private String disasterTypeSource;

  /** 灾害类型分类置信度 0-1 */
  @Column(name = "disaster_type_confidence")
  private Double disasterTypeConfidence;

  /** 三维度明细 JSON（image / precipitation / geographic / device 四个 map） */
  @Column(name = "dimensions_json", columnDefinition = "TEXT")
  private String dimensionsJson;

  /** 推理链数组 JSON */
  @Column(name = "reasoning_json", columnDefinition = "TEXT")
  private String reasoningJson;

  /** 建议动作数组 JSON */
  @Column(name = "suggested_actions_json", columnDefinition = "TEXT")
  private String suggestedActionsJson;

  @Column(name = "confidence")
  private Double confidence;

  @Column(name = "model")
  private String model;

  @Column(name = "degraded")
  private Boolean degraded;

  /** 是否接入了监测设备（false = AI 仅基于地理+天气测算） */
  @Column(name = "device_available")
  private Boolean deviceAvailable;

  /** 逻辑"快照时间"（AI 推理那一刻），用于前端显示"分析时间"。 */
  @Column(name = "analyzed_at", nullable = false)
  private OffsetDateTime analyzedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public String getRiskLevel() { return riskLevel; }
  public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
  public Double getRiskScore() { return riskScore; }
  public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }
  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }
  public String getDisasterType() { return disasterType; }
  public void setDisasterType(String disasterType) { this.disasterType = disasterType; }
  public String getConcernLabel() { return concernLabel; }
  public void setConcernLabel(String concernLabel) { this.concernLabel = concernLabel; }
  public String getDisasterTypeSource() { return disasterTypeSource; }
  public void setDisasterTypeSource(String disasterTypeSource) { this.disasterTypeSource = disasterTypeSource; }
  public Double getDisasterTypeConfidence() { return disasterTypeConfidence; }
  public void setDisasterTypeConfidence(Double disasterTypeConfidence) { this.disasterTypeConfidence = disasterTypeConfidence; }
  public Boolean getDeviceAvailable() { return deviceAvailable; }
  public void setDeviceAvailable(Boolean deviceAvailable) { this.deviceAvailable = deviceAvailable; }
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
  public Boolean getDegraded() { return degraded; }
  public void setDegraded(Boolean degraded) { this.degraded = degraded; }
  public OffsetDateTime getAnalyzedAt() { return analyzedAt; }
  public void setAnalyzedAt(OffsetDateTime analyzedAt) { this.analyzedAt = analyzedAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
