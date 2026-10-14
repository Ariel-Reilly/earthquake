package cn.disasterguard.governance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 数据源注册表实体，对应 {@code data_source} 表。
 *
 * <p>此前 {@link cn.disasterguard.governance.DataSourceController} 直接返回内存
 * mock 注册表（USGS 地震目录 / 中国地震台网 / GPS CORS 等），与库中真实的
 * 6 条数据源（Sentinel-1/2、ERA5、GPM、本地 MQTT、SRTM）完全无关，
 * 且 {@code /test} 端点用 {@code Math.random()} 伪造延迟。
 */
@Entity
@Table(name = "data_source")
public class DataSource {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "ds_code", nullable = false, unique = true)
  private String dsCode;

  @Column(name = "ds_name", nullable = false)
  private String dsName;

  @Column(name = "ds_type", nullable = false)
  private String dsType;

  @Column(name = "endpoint")
  private String endpoint;

  @Column(name = "auth_type")
  private String authType;

  /** 认证配置（JSONB）。不对外输出，避免泄露密钥。 */
  @Column(name = "auth_config", columnDefinition = "jsonb")
  private String authConfig;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "last_sync_at")
  private OffsetDateTime lastSyncAt;

  @Column(name = "sync_interval")
  private Integer syncInterval;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getDsCode() { return dsCode; }
  public void setDsCode(String dsCode) { this.dsCode = dsCode; }
  public String getDsName() { return dsName; }
  public void setDsName(String dsName) { this.dsName = dsName; }
  public String getDsType() { return dsType; }
  public void setDsType(String dsType) { this.dsType = dsType; }
  public String getEndpoint() { return endpoint; }
  public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
  public String getAuthType() { return authType; }
  public void setAuthType(String authType) { this.authType = authType; }
  public String getAuthConfig() { return authConfig; }
  public void setAuthConfig(String authConfig) { this.authConfig = authConfig; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public OffsetDateTime getLastSyncAt() { return lastSyncAt; }
  public void setLastSyncAt(OffsetDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
  public Integer getSyncInterval() { return syncInterval; }
  public void setSyncInterval(Integer syncInterval) { this.syncInterval = syncInterval; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
