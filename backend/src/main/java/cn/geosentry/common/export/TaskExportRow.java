package cn.geosentry.common.export;

import com.alibaba.excel.annotation.ExcelProperty;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** 巡检任务导出行。 */
public class TaskExportRow {

  @ExcelProperty("任务编号")
  private String taskCode;
  @ExcelProperty("任务名称")
  private String name;
  @ExcelProperty("任务类型")
  private String taskType;
  @ExcelProperty("执行人")
  private String executor;
  @ExcelProperty("行政区划")
  private String regionCode;
  @ExcelProperty("状态")
  private String status;
  @ExcelProperty("进度")
  private Integer progress;
  @ExcelProperty("开始时间")
  private String startTime;
  @ExcelProperty("截止时间")
  private String dueTime;

  public TaskExportRow(String taskCode, String name, String taskType, String executor, String regionCode,
                       String status, Integer progress, OffsetDateTime startTime, OffsetDateTime dueTime) {
    this.taskCode = taskCode;
    this.name = name;
    this.taskType = taskType;
    this.executor = executor;
    this.regionCode = regionCode;
    this.status = status;
    this.progress = progress;
    this.startTime = format(startTime);
    this.dueTime = format(dueTime);
  }

  private static String format(OffsetDateTime time) {
    return time == null ? "" : time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
  }

  public String getTaskCode() { return taskCode; }
  public String getName() { return name; }
  public String getTaskType() { return taskType; }
  public String getExecutor() { return executor; }
  public String getRegionCode() { return regionCode; }
  public String getStatus() { return status; }
  public Integer getProgress() { return progress; }
  public String getStartTime() { return startTime; }
  public String getDueTime() { return dueTime; }
}
