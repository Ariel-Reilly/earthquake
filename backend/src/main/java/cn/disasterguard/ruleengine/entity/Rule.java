package cn.disasterguard.ruleengine.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "rule")
public class Rule {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "rule_code", nullable = false, unique = true)
  private String ruleCode;

  @Column(nullable = false)
  private String name;

  @Column(name = "disaster_type", length = 50)
  private String disasterType;

  @Column(length = 20)
  private String level;

  @Column
  private Integer priority;

  @Column(columnDefinition = "JSON")
  private String conditions;

  @Column(columnDefinition = "JSON")
  private String actions;

  @Column(name = "dsl_text", columnDefinition = "TEXT")
  private String dslText;

  @Column
  private Integer version;

  @Column
  private Boolean enabled;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "created_at")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  public Rule() {
    this.createdAt = OffsetDateTime.now();
    this.updatedAt = OffsetDateTime.now();
    this.enabled = true;
    this.version = 1;
    this.priority = 0;
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getRuleCode() { return ruleCode; }
  public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getDisasterType() { return disasterType; }
  public void setDisasterType(String disasterType) { this.disasterType = disasterType; }
  public String getLevel() { return level; }
  public void setLevel(String level) { this.level = level; }
  public Integer getPriority() { return priority; }
  public void setPriority(Integer priority) { this.priority = priority; }
  public String getConditions() { return conditions; }
  public void setConditions(String conditions) { this.conditions = conditions; }
  public String getActions() { return actions; }
  public void setActions(String actions) { this.actions = actions; }
  public String getDslText() { return dslText; }
  public void setDslText(String dslText) { this.dslText = dslText; }
  public Integer getVersion() { return version; }
  public void setVersion(Integer version) { this.version = version; }
  public Boolean getEnabled() { return enabled; }
  public void setEnabled(Boolean enabled) { this.enabled = enabled; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
