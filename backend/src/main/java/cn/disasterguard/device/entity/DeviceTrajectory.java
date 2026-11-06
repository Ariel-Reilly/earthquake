package cn.disasterguard.device.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.locationtech.jts.geom.Point;

/**
 * 设备历史位置采样（设备地图 / 轨迹回放）。
 *
 * <p>每条记录代表设备在某一时刻上报的 GPS 位置，按 {@code recorded_at} 升序排列即可还原完整轨迹。
 * 设计上不强制外键到 {@code monitoring_device}：新增设备也能立即拥有轨迹；
 * 上层按需通过 {@code device_code} 关联。
 *
 * <p>空间索引：{@code geom} 为 EPSG:4326 空间点，由应用层 GeometryFactory 生成。
 */
@Entity
@Table(name = "device_trajectory")
public class DeviceTrajectory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "device_code", nullable = false, length = 64)
  private String deviceCode;

  @Column(name = "longitude", nullable = false)
  private Double longitude;

  @Column(name = "latitude", nullable = false)
  private Double latitude;

  @Column(name = "geom", columnDefinition = "geometry(Point,4326)")
  private Point geom;

  @Column(name = "altitude_m")
  private Double altitudeM;

  @Column(name = "speed_kmh")
  private Double speedKmh;

  @Column(name = "heading_deg")
  private Double headingDeg;

  @Column(name = "battery_level")
  private Integer batteryLevel;

  @Column(name = "source", nullable = false, length = 32)
  private String source = "MQTT";

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getDeviceCode() { return deviceCode; }
  public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
  public Double getLongitude() { return longitude; }
  public void setLongitude(Double longitude) { this.longitude = longitude; }
  public Double getLatitude() { return latitude; }
  public void setLatitude(Double latitude) { this.latitude = latitude; }
  public Point getGeom() { return geom; }
  public void setGeom(Point geom) { this.geom = geom; }
  public Double getAltitudeM() { return altitudeM; }
  public void setAltitudeM(Double altitudeM) { this.altitudeM = altitudeM; }
  public Double getSpeedKmh() { return speedKmh; }
  public void setSpeedKmh(Double speedKmh) { this.speedKmh = speedKmh; }
  public Double getHeadingDeg() { return headingDeg; }
  public void setHeadingDeg(Double headingDeg) { this.headingDeg = headingDeg; }
  public Integer getBatteryLevel() { return batteryLevel; }
  public void setBatteryLevel(Integer batteryLevel) { this.batteryLevel = batteryLevel; }
  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
  public void setRecordedAt(OffsetDateTime recordedAt) { this.recordedAt = recordedAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
