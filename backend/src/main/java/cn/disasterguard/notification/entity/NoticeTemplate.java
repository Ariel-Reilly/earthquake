package cn.disasterguard.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 通知模板实体，对应 {@code notice_template} 表。
 *
 * <p>字段说明见 schema.sql 8.9 节。要点：
 * <ul>
 *   <li>{@code templateCode} 业务唯一编码（NT-xxx），新建时由 Controller 生成。</li>
 *   <li>{@code channel} / {@code contentTemplate} 是单渠道时代的遗留列，
 *       仍被种子数据使用，保留以保证旧数据可读。</li>
 *   <li>{@code channelContents} 为 v2.2 追加的 JSONB 列，承载
 *       {@code {"sms":"...","email":"..."}} 多渠道正文映射，是新写入的主字段。</li>
 * </ul>
 */
@Entity
@Table(name = "notice_template")
public class NoticeTemplate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "template_code", length = 64, unique = true)
  private String templateCode;

  @Column(name = "template_name", nullable = false, length = 128)
  private String templateName;

  @Column(name = "disaster_type", length = 32)
  private String disasterType;

  @Column(name = "level", length = 16)
  private String level;

  @Column(name = "channel", length = 32)
  private String channel;

  @Column(name = "title_template", length = 256)
  private String titleTemplate;

  @Column(name = "content_template", columnDefinition = "TEXT")
  private String contentTemplate;

  /** 渠道 → 正文模板 的 JSONB 映射。旧行可能为 null，读取时需回退。 */
  @Column(name = "channel_contents", columnDefinition = "JSONB")
  private String channelContents;

  /** 模板可用占位变量说明，形如 {"region":"string","level":"string"}。 */
  @Column(name = "variables", columnDefinition = "JSONB")
  private String variables;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled = true;

  @Column(name = "created_by", length = 64)
  private String createdBy;

  @Column(name = "created_at")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  public NoticeTemplate() {
    OffsetDateTime now = OffsetDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;
    this.enabled = true;
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getTemplateCode() { return templateCode; }
  public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
  public String getTemplateName() { return templateName; }
  public void setTemplateName(String templateName) { this.templateName = templateName; }
  public String getDisasterType() { return disasterType; }
  public void setDisasterType(String disasterType) { this.disasterType = disasterType; }
  public String getLevel() { return level; }
  public void setLevel(String level) { this.level = level; }
  public String getChannel() { return channel; }
  public void setChannel(String channel) { this.channel = channel; }
  public String getTitleTemplate() { return titleTemplate; }
  public void setTitleTemplate(String titleTemplate) { this.titleTemplate = titleTemplate; }
  public String getContentTemplate() { return contentTemplate; }
  public void setContentTemplate(String contentTemplate) { this.contentTemplate = contentTemplate; }
  public String getChannelContents() { return channelContents; }
  public void setChannelContents(String channelContents) { this.channelContents = channelContents; }
  public String getVariables() { return variables; }
  public void setVariables(String variables) { this.variables = variables; }
  public Boolean getEnabled() { return enabled; }
  public void setEnabled(Boolean enabled) { this.enabled = enabled; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
