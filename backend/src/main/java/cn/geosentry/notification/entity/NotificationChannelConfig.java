package cn.geosentry.notification.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "notification_channel_config")
public class NotificationChannelConfig {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "disaster_type", length = 50)
  private String disasterType;

  @Column(name = "level", length = 20)
  private String level;

  @Column(columnDefinition = "JSON")
  private String channels;

  @Column(name = "template_mapping", columnDefinition = "JSON")
  private String templateMapping;

  @Column
  private Boolean enabled = true;

  @Column(name = "priority_channels", columnDefinition = "JSON")
  private String priorityChannels;

  @Column(name = "created_at")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  public NotificationChannelConfig() {
    this.createdAt = OffsetDateTime.now();
    this.updatedAt = OffsetDateTime.now();
    this.enabled = true;
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getDisasterType() { return disasterType; }
  public void setDisasterType(String disasterType) { this.disasterType = disasterType; }
  public String getLevel() { return level; }
  public void setLevel(String level) { this.level = level; }
  public String getChannels() { return channels; }
  public void setChannels(String channels) { this.channels = channels; }
  public String getTemplateMapping() { return templateMapping; }
  public void setTemplateMapping(String templateMapping) { this.templateMapping = templateMapping; }
  public Boolean getEnabled() { return enabled; }
  public void setEnabled(Boolean enabled) { this.enabled = enabled; }
  public String getPriorityChannels() { return priorityChannels; }
  public void setPriorityChannels(String priorityChannels) { this.priorityChannels = priorityChannels; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
