package cn.disasterguard.warning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "warning_rule")
public class WarningRule {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "rule_code", nullable = false, unique = true)
  private String ruleCode;

  @Column(name = "rule_name", nullable = false)
  private String ruleName;

  @Column(name = "metric_code", nullable = false)
  private String metricCode;

  /** 简单表达式如 "disp_rate > 5"，或 JSON 结构化表达式。 */
  @Column(name = "expression", nullable = false)
  private String expression;

  @Column(name = "warning_level", nullable = false)
  private String warningLevel;

  @Column(nullable = false)
  private Boolean enabled;

  @Column
  private String remark;

  @Column(name = "created_at")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getRuleCode() { return ruleCode; }
  public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
  public String getRuleName() { return ruleName; }
  public void setRuleName(String ruleName) { this.ruleName = ruleName; }
  public String getMetricCode() { return metricCode; }
  public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
  public String getExpression() { return expression; }
  public void setExpression(String expression) { this.expression = expression; }
  public String getWarningLevel() { return warningLevel; }
  public void setWarningLevel(String warningLevel) { this.warningLevel = warningLevel; }
  public Boolean getEnabled() { return enabled; }
  public void setEnabled(Boolean enabled) { this.enabled = enabled; }
  public String getRemark() { return remark; }
  public void setRemark(String remark) { this.remark = remark; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
