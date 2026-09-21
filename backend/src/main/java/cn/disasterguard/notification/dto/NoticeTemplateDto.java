package cn.disasterguard.notification.dto;

import java.util.Map;

/**
 * 通知模板管理页（NoticeTemplatePage.vue）的写入入参。
 *
 * <p>字段与前端 {@code upsertNoticeTemplate} 的 payload 一一对应：
 * {@code name / hazardType / level / channels / enabled}。
 * {@code channels} 是「渠道 → 正文模板」映射，键为 sms / app / email / led 等。
 */
public class NoticeTemplateDto {

  private String name;

  private String hazardType;

  private String level;

  /** 渠道 → 正文模板。空值与非空键混存，允许部分渠道留空。 */
  private Map<String, String> channels;

  private Boolean enabled;

  /** 可选：标题模板，未填写时保留原值。 */
  private String titleTemplate;

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getHazardType() { return hazardType; }
  public void setHazardType(String hazardType) { this.hazardType = hazardType; }
  public String getLevel() { return level; }
  public void setLevel(String level) { this.level = level; }
  public Map<String, String> getChannels() { return channels; }
  public void setChannels(Map<String, String> channels) { this.channels = channels; }
  public Boolean getEnabled() { return enabled; }
  public void setEnabled(Boolean enabled) { this.enabled = enabled; }
  public String getTitleTemplate() { return titleTemplate; }
  public void setTitleTemplate(String titleTemplate) { this.titleTemplate = titleTemplate; }
}
