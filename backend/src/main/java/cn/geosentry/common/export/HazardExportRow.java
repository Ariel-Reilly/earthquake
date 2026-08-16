package cn.geosentry.common.export;

import com.alibaba.excel.annotation.ExcelProperty;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** 灾害点导出行。 */
public class HazardExportRow {

  @ExcelProperty("灾害点编号")
  private String hazardCode;
  @ExcelProperty("名称")
  private String name;
  @ExcelProperty("灾害类型")
  private String hazardType;
  @ExcelProperty("风险等级")
  private String riskLevel;
  @ExcelProperty("行政区划")
  private String regionCode;
  @ExcelProperty("责任人")
  private String owner;
  @ExcelProperty("状态")
  private String status;
  @ExcelProperty("经度")
  private Double longitude;
  @ExcelProperty("纬度")
  private Double latitude;
  @ExcelProperty("威胁对象")
  private String threatObject;
  @ExcelProperty("最近巡检")
  private String lastInspectionAt;

  public HazardExportRow(String hazardCode, String name, String hazardType, String riskLevel, String regionCode,
                         String owner, String status, Double longitude, Double latitude, String threatObject, OffsetDateTime lastInspectionAt) {
    this.hazardCode = hazardCode;
    this.name = name;
    this.hazardType = hazardType;
    this.riskLevel = riskLevel;
    this.regionCode = regionCode;
    this.owner = owner;
    this.status = status;
    this.longitude = longitude;
    this.latitude = latitude;
    this.threatObject = threatObject;
    this.lastInspectionAt = lastInspectionAt == null ? "" : lastInspectionAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
  }

  public String getHazardCode() { return hazardCode; }
  public String getName() { return name; }
  public String getHazardType() { return hazardType; }
  public String getRiskLevel() { return riskLevel; }
  public String getRegionCode() { return regionCode; }
  public String getOwner() { return owner; }
  public String getStatus() { return status; }
  public Double getLongitude() { return longitude; }
  public Double getLatitude() { return latitude; }
  public String getThreatObject() { return threatObject; }
  public String getLastInspectionAt() { return lastInspectionAt; }
}
