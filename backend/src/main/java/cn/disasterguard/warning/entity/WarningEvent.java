package cn.disasterguard.warning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "warning_event")
public class WarningEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "warning_code", nullable = false, unique = true)
  private String warningCode;

  @Column(name = "rule_code")
  private String ruleCode;

  @Column(name = "hazard_code", nullable = false)
  private String hazardCode;

  @Column(nullable = false)
  private String title;

  @Column(name = "warning_level", nullable = false)
  private String warningLevel;

  @Column(name = "trigger_value")
  private String triggerValue;

  @Column(nullable = false)
  private String status;

  @Column(name = "triggered_at", nullable = false)
  private OffsetDateTime triggeredAt;

  @Column(name = "confirmed_at")
  private OffsetDateTime confirmedAt;

  @Column(name = "dispatched_at")
  private OffsetDateTime dispatchedAt;

  @Column(name = "closed_at")
  private OffsetDateTime closedAt;

  @Column(name = "close_reason")
  private String closeReason;

  @Column(name = "closed_by")
  private String closedBy;

  /** P0-2：解除范围（ALL / PART）。 */
  @Column(name = "resolve_scope")
  private String resolveScope;

  /** P0-2：部分解除时的具体区域。 */
  @Column(name = "resolve_area")
  private String resolveArea;

  @Column(name = "rule_snapshot")
  private String ruleSnapshot;

  /** 预警来源：RULE_ENGINE（规则引擎）/ MANUAL（人工上报）/ AI_AUTO（AI 自动识别）。 */
  @Column(length = 32)
  private String source;

  /**
   * 灾害类型，与 {@code hazard_point.hazard_type} / {@code unified_event.hazard_type}
   * 保持同一套英文码（landslide / collapse / debris_flow / subsidence / earthquake）。
   * 人工上报页传的是中文标签，入库前由 {@code WarningService} 归一化。
   */
  @Column(name = "hazard_type", length = 64)
  private String hazardType;

  /** 人工上报向导的扩展字段（区域 / 经纬度 / 影响半径 / 发布渠道 / 正文 / 有效期 / 上报人），JSON 文本。 */
  @Column(name = "manual_payload")
  private String manualPayload;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getWarningCode() { return warningCode; }
  public void setWarningCode(String warningCode) { this.warningCode = warningCode; }
  public String getRuleCode() { return ruleCode; }
  public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getWarningLevel() { return warningLevel; }
  public void setWarningLevel(String warningLevel) { this.warningLevel = warningLevel; }
  public String getTriggerValue() { return triggerValue; }
  public void setTriggerValue(String triggerValue) { this.triggerValue = triggerValue; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public OffsetDateTime getTriggeredAt() { return triggeredAt; }
  public void setTriggeredAt(OffsetDateTime triggeredAt) { this.triggeredAt = triggeredAt; }
  public OffsetDateTime getConfirmedAt() { return confirmedAt; }
  public void setConfirmedAt(OffsetDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
  public OffsetDateTime getDispatchedAt() { return dispatchedAt; }
  public void setDispatchedAt(OffsetDateTime dispatchedAt) { this.dispatchedAt = dispatchedAt; }
  public OffsetDateTime getClosedAt() { return closedAt; }
  public void setClosedAt(OffsetDateTime closedAt) { this.closedAt = closedAt; }
  public String getCloseReason() { return closeReason; }
  public void setCloseReason(String closeReason) { this.closeReason = closeReason; }
  public String getClosedBy() { return closedBy; }
  public void setClosedBy(String closedBy) { this.closedBy = closedBy; }
  public String getResolveScope() { return resolveScope; }
  public void setResolveScope(String resolveScope) { this.resolveScope = resolveScope; }
  public String getResolveArea() { return resolveArea; }
  public void setResolveArea(String resolveArea) { this.resolveArea = resolveArea; }
  public String getRuleSnapshot() { return ruleSnapshot; }
  public void setRuleSnapshot(String ruleSnapshot) { this.ruleSnapshot = ruleSnapshot; }
  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }
  public String getHazardType() { return hazardType; }
  public void setHazardType(String hazardType) { this.hazardType = hazardType; }
  public String getManualPayload() { return manualPayload; }
  public void setManualPayload(String manualPayload) { this.manualPayload = manualPayload; }
}
