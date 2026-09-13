package cn.geosentry.notification.entity;

import jakarta.persistence.*;
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

  @Column(columnDefinition = "JSON")
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
