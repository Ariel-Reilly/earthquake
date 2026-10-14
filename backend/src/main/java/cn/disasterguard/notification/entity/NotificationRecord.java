package cn.disasterguard.notification.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Entity
@Table(name = "notification_record")
public class NotificationRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "biz_type", length = 50)
  private String bizType;

  @Column(name = "biz_id", length = 64)
  private String bizId;

  @Column(name = "disaster_type", length = 50)
  private String disasterType;

  @Column(name = "level", length = 20)
  private String level;

  @Column(length = 255)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String content;

  /**
   * 渠道列表（JSON 数组字符串）。
   *
   * <p>DB 列类型为 {@code jsonb}，需 {@code @JdbcTypeCode(SqlTypes.JSON)} +
   * {@code columnDefinition = "jsonb"} 双重声明：
   * <ul>
   *   <li>{@code @JdbcTypeCode(SqlTypes.JSON)} — 决定 JDBC 参数绑定类型。缺少它时
   *       Hibernate 会以 {@code varchar} 下发参数，PostgreSQL 拒绝隐式转换并抛出
   *       {@code column "channels" is of type jsonb but expression is of type character varying}；</li>
   *   <li>{@code columnDefinition} — 决定 DDL 生成类型。</li>
   * </ul>
   * 二者缺一，通知记录的所有写入（发送、单条标已读、全部已读）都会 500
   * （对外表现为"系统繁忙，请稍后重试"），红点因此永远消不掉。
   * 写法与项目内 {@code AiModel} / {@code AiComprehensiveScore} 等 jsonb 实体一致。
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String channels;

  @Column(length = 20)
  private String status;

  @Column(name = "created_at")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @Column(name = "created_by")
  private String createdBy;

  public NotificationRecord() {
    this.createdAt = OffsetDateTime.now();
    this.updatedAt = OffsetDateTime.now();
    this.status = "PENDING";
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getBizType() { return bizType; }
  public void setBizType(String bizType) { this.bizType = bizType; }
  public String getBizId() { return bizId; }
  public void setBizId(String bizId) { this.bizId = bizId; }
  public String getDisasterType() { return disasterType; }
  public void setDisasterType(String disasterType) { this.disasterType = disasterType; }
  public String getLevel() { return level; }
  public void setLevel(String level) { this.level = level; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }
  public String getChannels() { return channels; }
  public void setChannels(String channels) { this.channels = channels; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
