package cn.disasterguard.ruleengine.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "rule_execution_log")
public class RuleExecutionLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "rule_id")
  private Long ruleId;

  @Column
  private Boolean triggered;

  @Column(name = "input_data", columnDefinition = "JSON")
  private String inputData;

  @Column(columnDefinition = "JSON")
  private String result;

  @Column(name = "executed_at")
  private OffsetDateTime executedAt;

  public RuleExecutionLog() {
    this.executedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getRuleId() { return ruleId; }
  public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
  public Boolean getTriggered() { return triggered; }
  public void setTriggered(Boolean triggered) { this.triggered = triggered; }
  public String getInputData() { return inputData; }
  public void setInputData(String inputData) { this.inputData = inputData; }
  public String getResult() { return result; }
  public void setResult(String result) { this.result = result; }
  public OffsetDateTime getExecutedAt() { return executedAt; }
  public void setExecutedAt(OffsetDateTime executedAt) { this.executedAt = executedAt; }
}
