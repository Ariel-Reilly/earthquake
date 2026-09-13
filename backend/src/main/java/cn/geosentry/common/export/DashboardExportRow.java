package cn.geosentry.common.export;

import com.alibaba.excel.annotation.ExcelProperty;

/** 总览指标导出行。 */
public class DashboardExportRow {

  @ExcelProperty("指标")
  private String label;
  @ExcelProperty("数值")
  private String value;
  @ExcelProperty("单位")
  private String suffix;
  @ExcelProperty("说明")
  private String change;

  public DashboardExportRow(String label, String value, String suffix, String change) {
    this.label = label;
    this.value = value;
    this.suffix = suffix;
    this.change = change;
  }

  public String getLabel() { return label; }
  public String getValue() { return value; }
  public String getSuffix() { return suffix; }
  public String getChange() { return change; }
}