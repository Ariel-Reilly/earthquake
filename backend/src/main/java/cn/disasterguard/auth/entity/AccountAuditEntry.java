package cn.disasterguard.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 账户审计日志条目实体，对应 {@code account_audit_entry} 表。
 *
 * <p>原内存 Map 的键为自增 id（1001 起，非 userId），故 {@code id} 沿用
 * Long 自增语义（应用侧 max+1 分配）；业务数据整体存入 JSONB {@code payload}。
 */
@Entity
@Table(name = "account_audit_entry")
public class AccountAuditEntry {

  @Id
  private Long id;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> payload;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  public AccountAuditEntry() {
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Map<String, Object> getPayload() { return payload; }
  public void setPayload(Map<String, Object> payload) { this.payload = payload; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
