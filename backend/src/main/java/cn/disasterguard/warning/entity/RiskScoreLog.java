package cn.disasterguard.warning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** AI 融合风险评分原始快照（用于模型迭代回溯训练集）。 */
@Entity
@Table(name = "risk_score_log")
public class RiskScoreLog {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "hazard_code", nullable = false)
  private String hazardCode;

  @Column(nullable = false)
  private double score;

  @Column(name = "risk_level", nullable = false)
  private String riskLevel;

  /** 融合因子快照（JSONB → String 存储）。 */
  @Column
  private String factors;

  /** 来源：AI_FUSED（AI 融合评分）/ RULE_ONLY（规则等级，AI 降级）/ MANUAL（人工录入）。 */
  @Column(name = "source")
  private String source;

  @Column(name = "calculated_at", nullable = false)
  private OffsetDateTime calculatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public double getScore() { return score; }
  public void setScore(double score) { this.score = score; }
  public String getRiskLevel() { return riskLevel; }
  public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
  public String getFactors() { return factors; }
  public void setFactors(String factors) { this.factors = factors; }
  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }
  public OffsetDateTime getCalculatedAt() { return calculatedAt; }
  public void setCalculatedAt(OffsetDateTime calculatedAt) { this.calculatedAt = calculatedAt; }
}
