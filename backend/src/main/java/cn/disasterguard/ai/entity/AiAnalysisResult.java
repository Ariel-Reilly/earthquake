package cn.disasterguard.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_analysis_result")
public class AiAnalysisResult {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "business_type", nullable = false, length = 30)
  private String businessType;

  @Column(name = "business_code", nullable = false, length = 32)
  private String businessCode;

  @Column(name = "model_name", length = 50)
  private String modelName;

  /** AI 返回结果（JSON 文本）。 */
  @Column(name = "result_json", nullable = false, columnDefinition = "text")
  private String resultJson;

  @Column(name = "confidence")
  private Double confidence;

  @Column(name = "created_at")
  private OffsetDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getBusinessType() { return businessType; }
  public void setBusinessType(String businessType) { this.businessType = businessType; }
  public String getBusinessCode() { return businessCode; }
  public void setBusinessCode(String businessCode) { this.businessCode = businessCode; }
  public String getModelName() { return modelName; }
  public void setModelName(String modelName) { this.modelName = modelName; }
  public String getResultJson() { return resultJson; }
  public void setResultJson(String resultJson) { this.resultJson = resultJson; }
  public Double getConfidence() { return confidence; }
  public void setConfidence(Double confidence) { this.confidence = confidence; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
