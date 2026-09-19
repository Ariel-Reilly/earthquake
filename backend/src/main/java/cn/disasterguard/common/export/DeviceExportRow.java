package cn.disasterguard.common.export;

import com.alibaba.excel.annotation.ExcelProperty;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** 监测设备导出行。 */
public class DeviceExportRow {

  @ExcelProperty("设备编号")
  private String deviceCode;
  @ExcelProperty("设备名称")
  private String deviceName;
  @ExcelProperty("设备类型")
  private String deviceType;
  @ExcelProperty("关联灾害点")
  private String hazardCode;
  @ExcelProperty("在线状态")
  private String onlineStatus;
  @ExcelProperty("电量")
  private Integer batteryLevel;
  @ExcelProperty("信号")
  private String signalLevel;
  @ExcelProperty("安装位置")
  private String installLocation;
  @ExcelProperty("经度")
  private Double longitude;
  @ExcelProperty("纬度")
  private Double latitude;
  @ExcelProperty("最后上报")
  private String lastReportAt;

  public DeviceExportRow(String deviceCode, String deviceName, String deviceType, String hazardCode, String onlineStatus,
                         Integer batteryLevel, String signalLevel, String installLocation, Double longitude, Double latitude, OffsetDateTime lastReportAt) {
    this.deviceCode = deviceCode;
    this.deviceName = deviceName;
    this.deviceType = deviceType;
    this.hazardCode = hazardCode;
    this.onlineStatus = onlineStatus;
    this.batteryLevel = batteryLevel;
    this.signalLevel = signalLevel;
    this.installLocation = installLocation;
    this.longitude = longitude;
    this.latitude = latitude;
    this.lastReportAt = lastReportAt == null ? "" : lastReportAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
  }

  public String getDeviceCode() { return deviceCode; }
  public String getDeviceName() { return deviceName; }
  public String getDeviceType() { return deviceType; }
  public String getHazardCode() { return hazardCode; }
  public String getOnlineStatus() { return onlineStatus; }
  public Integer getBatteryLevel() { return batteryLevel; }
  public String getSignalLevel() { return signalLevel; }
  public String getInstallLocation() { return installLocation; }
  public Double getLongitude() { return longitude; }
  public Double getLatitude() { return latitude; }
  public String getLastReportAt() { return lastReportAt; }
}
