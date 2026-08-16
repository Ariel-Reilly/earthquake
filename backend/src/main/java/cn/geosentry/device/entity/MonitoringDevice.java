package cn.geosentry.device.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "monitoring_device")
public class MonitoringDevice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "device_code", nullable = false, unique = true)
  private String deviceCode;

  @Column(name = "device_name", nullable = false)
  private String deviceName;

  @Column(name = "device_type", nullable = false)
  private String deviceType;

  @Column(name = "hazard_code")
  private String hazardCode;

  @Column(name = "region_code")
  private String regionCode;

  @Column(name = "online_status", nullable = false)
  private String onlineStatus;

  @Column(name = "battery_level")
  private Integer batteryLevel;

  @Column(name = "signal_level")
  private String signalLevel;

  @Column(name = "last_report_at")
  private OffsetDateTime lastReportAt;

  @Column(name = "install_location")
  private String installLocation;

  @Column(name = "longitude")
  private Double longitude;

  @Column(name = "latitude")
  private Double latitude;

  @Column(name = "api_key", length = 128)
  private String apiKey;

  /** EPSG:4326 空间点，由应用层从 longitude/latitude 生成（GeometryFactory） */
  @Column(name = "geom", columnDefinition = "geometry(Point,4326)")
  private Point geom;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getDeviceCode() { return deviceCode; }
  public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
  public String getDeviceName() { return deviceName; }
  public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
  public String getDeviceType() { return deviceType; }
  public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public String getRegionCode() { return regionCode; }
  public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
  public String getOnlineStatus() { return onlineStatus; }
  public void setOnlineStatus(String onlineStatus) { this.onlineStatus = onlineStatus; }
  public Integer getBatteryLevel() { return batteryLevel; }
  public void setBatteryLevel(Integer batteryLevel) { this.batteryLevel = batteryLevel; }
  public String getSignalLevel() { return signalLevel; }
  public void setSignalLevel(String signalLevel) { this.signalLevel = signalLevel; }
  public OffsetDateTime getLastReportAt() { return lastReportAt; }
  public void setLastReportAt(OffsetDateTime lastReportAt) { this.lastReportAt = lastReportAt; }
  public String getInstallLocation() { return installLocation; }
  public void setInstallLocation(String installLocation) { this.installLocation = installLocation; }
  public Double getLongitude() { return longitude; }
  public void setLongitude(Double longitude) { this.longitude = longitude; }
  public Double getLatitude() { return latitude; }
  public void setLatitude(Double latitude) { this.latitude = latitude; }
  public String getApiKey() { return apiKey; }
  public void setApiKey(String apiKey) { this.apiKey = apiKey; }
  public Point getGeom() { return geom; }
  public void setGeom(Point geom) { this.geom = geom; }
}
