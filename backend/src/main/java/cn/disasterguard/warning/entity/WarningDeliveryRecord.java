package cn.disasterguard.warning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 预警送达记录实体（行表），对应 {@code warning_delivery_record} 表。
 *
 * <p>原内存结构为 {@code Map<预警id, List<记录>>}，落库拆为行表：
 * {@code recordId} 为预警 id（原 Map 键），{@code id} 为原记录内的自增
 * 记录 id（1001 起，应用侧 max+1 分配）；单条记录内容存入 JSONB {@code payload}。
 */
@Entity
@Table(name = "warning_delivery_record")
public class WarningDeliveryRecord {

  @Id
  private Long id;

  /** 预警 id，即原 Map 的分组键。 */
  @Column(name = "record_id", nullable = false)
  private Long recordId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> payload;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  public WarningDeliveryRecord() {
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getRecordId() { return recordId; }
  public void setRecordId(Long recordId) { this.recordId = recordId; }
  public Map<String, Object> getPayload() { return payload; }
  public void setPayload(Map<String, Object> payload) { this.payload = payload; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
