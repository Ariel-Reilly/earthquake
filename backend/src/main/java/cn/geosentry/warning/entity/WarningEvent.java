package cn.geosentry.warning.entity;

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

  @Column(name = "rule_snapshot")
  private String ruleSnapshot;

  /** 预警来源：RULE_ENGINE（规则引擎）/ MANUAL（人工上报）/ AI_AUTO（AI 自动识别）。 */
  @Column(length = 32)
  private String source;

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
  public String getRuleSnapshot() { return ruleSnapshot; }
  public void setRuleSnapshot(String ruleSnapshot) { this.ruleSnapshot = ruleSnapshot; }
  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }
}
