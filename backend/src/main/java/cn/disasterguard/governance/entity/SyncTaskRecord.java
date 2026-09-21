package cn.disasterguard.governance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 数据同步任务实体，对应 {@code sync_task_record} 表（M6 技术债：原 SyncController 内存 Map 落库）。
 *
 * <p>{@code id} 即原 TASKS 的键（如 "SYNC-001"），业务字段整体存于
 * JSONB {@code payload} 列，保持原响应结构不变。
 */
@Entity
@Table(name = "sync_task_record")
public class SyncTaskRecord {

  @Id
  private String id;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> payload;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public Map<String, Object> getPayload() { return payload; }
  public void setPayload(Map<String, Object> payload) { this.payload = payload; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
