package cn.disasterguard.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * AI 模型注册表实体，对应 {@code ai_model} 表（7 条真实模型）。
 *
 * <p>此前 {@link cn.disasterguard.ai.AiController} 的 {@code /models*} 系列端点
 * 全部返回写死的两条编造模型（fusion_xgb v1.0.0 / v0.9.1，含虚构准确率 0.923 等），
 * 与库中真实的 7 个模型（xgboost_fusion / lstm_precip / yolo_landslide /
 * comprehensive / device_health / disaster_assess / isolation_forest）无关。
 */
@Entity
@Table(name = "ai_model")
public class AiModel {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "model_code", nullable = false, unique = true)
  private String modelCode;

  @Column(name = "model_name", nullable = false)
  private String modelName;

  @Column(name = "version", nullable = false)
  private String version;

  @Column(name = "model_type", nullable = false)
  private String modelType;

  @Column(name = "status", nullable = false)
  private String status;

  /** 评估指标（JSONB）：f1 / auc / recall / accuracy / precision / r2 / mae / rmse / map 等。 */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metrics", columnDefinition = "jsonb")
  private Map<String, Object> metrics;

  /** 模型配置（JSONB）：features / threshold / window_hours / weights 等。 */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "config", columnDefinition = "jsonb")
  private Map<String, Object> config;

  @Column(name = "description")
  private String description;

  @Column(name = "deployed_at")
  private OffsetDateTime deployedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getModelCode() { return modelCode; }
  public void setModelCode(String modelCode) { this.modelCode = modelCode; }
  public String getModelName() { return modelName; }
  public void setModelName(String modelName) { this.modelName = modelName; }
  public String getVersion() { return version; }
  public void setVersion(String version) { this.version = version; }
  public String getModelType() { return modelType; }
  public void setModelType(String modelType) { this.modelType = modelType; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Map<String, Object> getMetrics() { return metrics; }
  public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }
  public Map<String, Object> getConfig() { return config; }
  public void setConfig(Map<String, Object> config) { this.config = config; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public OffsetDateTime getDeployedAt() { return deployedAt; }
  public void setDeployedAt(OffsetDateTime deployedAt) { this.deployedAt = deployedAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
