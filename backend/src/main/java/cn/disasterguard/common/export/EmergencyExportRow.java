package cn.disasterguard.common.export;

import com.alibaba.excel.annotation.ExcelProperty;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** 应急事件导出行。 */
public class EmergencyExportRow {

  @ExcelProperty("应急编号")
  private String eventCode;
  @ExcelProperty("关联预警")
  private String warningCode;
  @ExcelProperty("灾害点")
  private String hazardCode;
  @ExcelProperty("标题")
  private String title;
  @ExcelProperty("状态")
  private String status;
  @ExcelProperty("指挥人员")
  private String commander;
  @ExcelProperty("处置阶段")
  private String stepName;
  @ExcelProperty("处置结果")
  private String resultSummary;
  @ExcelProperty("开始时间")
  private String startedAt;
  @ExcelProperty("闭环时间")
  private String closedAt;

  public EmergencyExportRow(String eventCode, String warningCode, String hazardCode, String title, String status,
                            String commander, String stepName, String resultSummary,
                            OffsetDateTime startedAt, OffsetDateTime closedAt) {
    this.eventCode = eventCode;
    this.warningCode = warningCode;
    this.hazardCode = hazardCode;
    this.title = title;
    this.status = status;
    this.commander = commander;
    this.stepName = stepName;
    this.resultSummary = resultSummary;
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    this.startedAt = startedAt == null ? "" : startedAt.format(fmt);
    this.closedAt = closedAt == null ? "" : closedAt.format(fmt);
  }

  public String getEventCode() { return eventCode; }
  public String getWarningCode() { return warningCode; }
  public String getHazardCode() { return hazardCode; }
  public String getTitle() { return title; }
  public String getStatus() { return status; }
  public String getCommander() { return commander; }
  public String getStepName() { return stepName; }
  public String getResultSummary() { return resultSummary; }
  public String getStartedAt() { return startedAt; }
  public String getClosedAt() { return closedAt; }
}