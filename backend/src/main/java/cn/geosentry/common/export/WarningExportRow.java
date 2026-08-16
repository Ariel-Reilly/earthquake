package cn.geosentry.common.export;

import com.alibaba.excel.annotation.ExcelProperty;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** 预警事件导出行。 */
public class WarningExportRow {

  @ExcelProperty("预警编号")
  private String warningCode;
  @ExcelProperty("标题")
  private String title;
  @ExcelProperty("关联灾害点")
  private String hazardCode;
  @ExcelProperty("规则")
  private String ruleCode;
  @ExcelProperty("预警等级")
  private String warningLevel;
  @ExcelProperty("触发值")
  private String triggerValue;
  @ExcelProperty("状态")
  private String status;
  @ExcelProperty("触发时间")
  private String triggeredAt;

  public WarningExportRow(String warningCode, String title, String hazardCode, String ruleCode, String warningLevel,
                          String triggerValue, String status, OffsetDateTime triggeredAt) {
    this.warningCode = warningCode;
    this.title = title;
    this.hazardCode = hazardCode;
    this.ruleCode = ruleCode;
    this.warningLevel = warningLevel;
    this.triggerValue = triggerValue;
    this.status = status;
    this.triggeredAt = triggeredAt == null ? "" : triggeredAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
  }

  public String getWarningCode() { return warningCode; }
  public String getTitle() { return title; }
  public String getHazardCode() { return hazardCode; }
  public String getRuleCode() { return ruleCode; }
  public String getWarningLevel() { return warningLevel; }
  public String getTriggerValue() { return triggerValue; }
  public String getStatus() { return status; }
  public String getTriggeredAt() { return triggeredAt; }
}
