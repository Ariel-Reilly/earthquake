package cn.geosentry.device.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 设备地图视图聚合 DTO（v1.9.0 P1）。
 *
 * <p>把"当前设备列表"+"各自最近位置"组合成视图对象，前端一次性拿到
 * 设备和对应的位置，避免两次请求。
 */
public class DeviceMapDtos {

  /** 地图上单台设备的当前位置快照。 */
  public static class DeviceMapItem {
    /** 设备编码（必填） */
    private String deviceCode;
    /** 设备名称（必填） */
    private String deviceName;
    /** 设备类型：固定/移动（决定是否参与轨迹回放） */
    private String deviceType;
    /** 移动类型：drone / vehicle / handheld / null（仅 deviceType=移动时使用） */
    private String mobilityKind;
    /** 在线状态：在线 / 离线 / 故障 */
    private String onlineStatus;
    /** 电量 0..100 */
    private Integer batteryLevel;
    /** 信号强度：强 / 中 / 弱 / 无 */
    private String signalLevel;
    /** 关联灾害点编码 */
    private String hazardCode;
    /** 行政区划编码 */
    private String regionCode;
    /** 设备当前位置经度（必填） */
    private Double longitude;
    /** 设备当前位置纬度（必填） */
    private Double latitude;
    /** 最近一次上报速度 km/h */
    private Double speedKmh;
    /** 最近一次上报方向角 0..359 */
    private Double headingDeg;
    /** 最近上报时间 ISO 8601 */
    private OffsetDateTime lastReportAt;
    /** 是否为可移动设备（前端用于在地图上是否显示轨迹图标） */
    private Boolean mobile;

    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getMobilityKind() { return mobilityKind; }
    public void setMobilityKind(String mobilityKind) { this.mobilityKind = mobilityKind; }
    public String getOnlineStatus() { return onlineStatus; }
    public void setOnlineStatus(String onlineStatus) { this.onlineStatus = onlineStatus; }
    public Integer getBatteryLevel() { return batteryLevel; }
    public void setBatteryLevel(Integer batteryLevel) { this.batteryLevel = batteryLevel; }
    public String getSignalLevel() { return signalLevel; }
    public void setSignalLevel(String signalLevel) { this.signalLevel = signalLevel; }
    public String getHazardCode() { return hazardCode; }
    public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getSpeedKmh() { return speedKmh; }
    public void setSpeedKmh(Double speedKmh) { this.speedKmh = speedKmh; }
    public Double getHeadingDeg() { return headingDeg; }
    public void setHeadingDeg(Double headingDeg) { this.headingDeg = headingDeg; }
    public OffsetDateTime getLastReportAt() { return lastReportAt; }
    public void setLastReportAt(OffsetDateTime lastReportAt) { this.lastReportAt = lastReportAt; }
    public Boolean getMobile() { return mobile; }
    public void setMobile(Boolean mobile) { this.mobile = mobile; }
  }

  /** 设备地图统计：在线/离线/移动/总数等。 */
  public static class DeviceMapStats {
    private long total;
    private long online;
    private long offline;
    private long fault;
    private long mobile;
    private long fixed;
    private long trajectoryDevices;

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public long getOnline() { return online; }
    public void setOnline(long online) { this.online = online; }
    public long getOffline() { return offline; }
    public void setOffline(long offline) { this.offline = offline; }
    public long getFault() { return fault; }
    public void setFault(long fault) { this.fault = fault; }
    public long getMobile() { return mobile; }
    public void setMobile(long mobile) { this.mobile = mobile; }
    public long getFixed() { return fixed; }
    public void setFixed(long fixed) { this.fixed = fixed; }
    public long getTrajectoryDevices() { return trajectoryDevices; }
    public void setTrajectoryDevices(long trajectoryDevices) { this.trajectoryDevices = trajectoryDevices; }
  }

  /** 完整设备地图响应。 */
  public static class DeviceMapResponse {
    private List<DeviceMapItem> devices;
    private DeviceMapStats stats;
    private OffsetDateTime generatedAt;

    public List<DeviceMapItem> getDevices() { return devices; }
    public void setDevices(List<DeviceMapItem> devices) { this.devices = devices; }
    public DeviceMapStats getStats() { return stats; }
    public void setStats(DeviceMapStats stats) { this.stats = stats; }
    public OffsetDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(OffsetDateTime generatedAt) { this.generatedAt = generatedAt; }
  }

  /** 轨迹点 DTO。 */
  public static class TrajectoryPoint {
    private Double longitude;
    private Double latitude;
    private OffsetDateTime recordedAt;
    private Double speedKmh;
    private Double headingDeg;
    private Integer batteryLevel;
    private String source;

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public OffsetDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(OffsetDateTime recordedAt) { this.recordedAt = recordedAt; }
    public Double getSpeedKmh() { return speedKmh; }
    public void setSpeedKmh(Double speedKmh) { this.speedKmh = speedKmh; }
    public Double getHeadingDeg() { return headingDeg; }
    public void setHeadingDeg(Double headingDeg) { this.headingDeg = headingDeg; }
    public Integer getBatteryLevel() { return batteryLevel; }
    public void setBatteryLevel(Integer batteryLevel) { this.batteryLevel = batteryLevel; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
  }
}
