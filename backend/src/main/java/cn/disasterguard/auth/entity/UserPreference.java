package cn.disasterguard.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * P0-3：用户通知偏好（独立表 user_preference）。
 * channels / levels / language / theme / quietHours 等偏好按 JSON 字符串存储，
 * 兼容多种数据库（PostgreSQL JSON / MySQL JSON / SQLite TEXT）。
 */
@Entity
@Table(name = "user_preference")
public class UserPreference {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, unique = true)
  private Long userId;

  /** JSON 字符串：{ sms: bool, email: bool, ... } */
  @Column(name = "channels", columnDefinition = "TEXT")
  private String channels;

  /** JSON 字符串：{ red: [..], orange: [..], ... } */
  @Column(name = "levels", columnDefinition = "TEXT")
  private String levels;

  @Column(length = 10)
  private String language = "zh-CN";

  @Column(length = 10)
  private String theme = "light";

  @Column(name = "compact_mode")
  private Boolean compactMode = false;

  @Column(name = "quiet_hours_enabled")
  private Boolean quietHoursEnabled = false;

  @Column(name = "quiet_start", length = 8)
  private String quietStart = "22:00";

  @Column(name = "quiet_end", length = 8)
  private String quietEnd = "08:00";

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getUserId() { return userId; }
  public void setUserId(Long userId) { this.userId = userId; }
  public String getChannels() { return channels; }
  public void setChannels(String channels) { this.channels = channels; }
  public String getLevels() { return levels; }
  public void setLevels(String levels) { this.levels = levels; }
  public String getLanguage() { return language; }
  public void setLanguage(String language) { this.language = language; }
  public String getTheme() { return theme; }
  public void setTheme(String theme) { this.theme = theme; }
  public Boolean getCompactMode() { return compactMode; }
  public void setCompactMode(Boolean compactMode) { this.compactMode = compactMode; }
  public Boolean getQuietHoursEnabled() { return quietHoursEnabled; }
  public void setQuietHoursEnabled(Boolean quietHoursEnabled) { this.quietHoursEnabled = quietHoursEnabled; }
  public String getQuietStart() { return quietStart; }
  public void setQuietStart(String quietStart) { this.quietStart = quietStart; }
  public String getQuietEnd() { return quietEnd; }
  public void setQuietEnd(String quietEnd) { this.quietEnd = quietEnd; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
