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
 * 活跃会话实体，对应 {@code login_session} 表。
 *
 * <p>{@code id} 即原内存 Map 的键（会话 token 字符串），业务数据整体
 * 存入 JSONB {@code payload}（sessionId/device/ip/location/loggedInAt/lastActive）。
 */
@Entity
@Table(name = "login_session")
public class LoginSession {

  @Id
  private String id;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> payload;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  public LoginSession() {
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public Map<String, Object> getPayload() { return payload; }
  public void setPayload(Map<String, Object> payload) { this.payload = payload; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
