package cn.disasterguard.backup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 恢复记录实体，对应 {@code restore_record} 表。
 *
 * <p>M6 技术债改造：原 Controller 用 ConcurrentHashMap 保存业务数据，重启即丢。
 * 统一方案为 {@code {id, payload JSONB, created_at}} 结构：业务字段全部收敛到
 * {@code payload} JSONB 列，{@code id} 由 Controller 侧计数器手动分配（故不使用
 * {@code @GeneratedValue}）。
 */
@Entity
@Table(name = "restore_record")
public class RestoreRecord {

  @Id
  private Long id;

  /** 业务负载，JSONB 列。时间值一律以 ISO 字符串存储。 */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> payload;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Map<String, Object> getPayload() { return payload; }
  public void setPayload(Map<String, Object> payload) { this.payload = payload; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
