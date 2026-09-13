package cn.geosentry.device.service;

import cn.geosentry.device.dto.DeviceMapDtos.DeviceMapItem;
import cn.geosentry.device.dto.DeviceMapDtos.DeviceMapResponse;
import cn.geosentry.device.dto.DeviceMapDtos.DeviceMapStats;
import cn.geosentry.device.dto.DeviceMapDtos.TrajectoryPoint;
import cn.geosentry.device.entity.DeviceTrajectory;
import cn.geosentry.device.entity.MonitoringDevice;
import cn.geosentry.device.repository.DeviceTrajectoryRepository;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 设备地图视图 + 轨迹回放服务（v1.9.0 P1）。
 *
 * <p>核心能力：
 * <ul>
 *   <li>{@link #buildDeviceMap()} — 取所有 monitoring_device + 各台设备最近一次上报位置（24h 内），
 *       组成地图视图 DTO。</li>
 *   <li>{@link #fetchTrajectory(String, OffsetDateTime, OffsetDateTime, int)} —
 *       按时间范围取单设备轨迹（最多 maxPoints 个点，超出按时间均匀采样）。</li>
 *   <li>{@link #mobileDeviceCodes()} — 返回需要生成轨迹的可移动设备编码（deviceType 含 drone/vehicle/handheld）。</li>
 * </ul>
 *
 * <p>mock 模式：启动时由 {@link DeviceTrajectoryGenerator} 检测到 trajectory 表为空，
 * 自动为移动设备生成最近 24h 的轨迹，避免空查询。
 */
@Service
public class DeviceMapService {

  private static final Logger log = LoggerFactory.getLogger(DeviceMapService.class);

  private final MonitoringDeviceRepository deviceRepository;
  private final DeviceTrajectoryRepository trajectoryRepository;

  /** 视为可移动的设备类型关键词（deviceType 包含其一即视为移动）。 */
  private static final List<String> MOBILE_KEYWORDS = List.of("drone", "vehicle", "handheld", "mobile", "uav", "无人", "车", "手持");

  public DeviceMapService(MonitoringDeviceRepository deviceRepository,
                          DeviceTrajectoryRepository trajectoryRepository) {
    this.deviceRepository = deviceRepository;
    this.trajectoryRepository = trajectoryRepository;
  }

  /**
   * 构建全量设备地图响应（devices + stats + generatedAt）。
   *
   * <p>数据来源：
   * <ul>
   *   <li>devices 表 → 取所有 monitoring_device</li>
   *   <li>trajectory 表 → 取近 24h 各设备最新一条位置，覆盖到 device 字段</li>
   *   <li>设备自身 longitude/latitude 字段作为兜底</li>
   * </ul>
   */
  public DeviceMapResponse buildDeviceMap() {
    List<MonitoringDevice> devices = deviceRepository.findAll();
    Map<String, Object[]> latestByDevice = new HashMap<>();
    OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusHours(24);
    for (Object[] row : trajectoryRepository.findLatestPositionsSince(since)) {
      latestByDevice.put((String) row[0], row);
    }

    Set<String> codesWithTrajectory = new HashSet<>();
    List<DeviceMapItem> items = new ArrayList<>();
    DeviceMapStats stats = new DeviceMapStats();

    for (MonitoringDevice d : devices) {
      // 跳过没有经纬度且没有最近轨迹的设备（地图上无意义）
      Object[] latest = latestByDevice.get(d.getDeviceCode());
      Double lon = latest != null ? toDouble(latest[1]) : d.getLongitude();
      Double lat = latest != null ? toDouble(latest[2]) : d.getLatitude();
      if (lon == null || lat == null) continue;

      boolean mobile = isMobile(d);
      if (mobile) {
        codesWithTrajectory.add(d.getDeviceCode());
        // 最新位置来自轨迹则补一下
        if (latest != null && d.getBatteryLevel() == null && latest[5] != null) {
          d.setBatteryLevel(toInt(latest[5]));
        }
      }

      DeviceMapItem item = new DeviceMapItem();
      item.setDeviceCode(d.getDeviceCode());
      item.setDeviceName(d.getDeviceName());
      item.setDeviceType(d.getDeviceType());
      item.setMobilityKind(extractMobilityKind(d));
      item.setOnlineStatus(d.getOnlineStatus());
      item.setBatteryLevel(d.getBatteryLevel());
      item.setSignalLevel(d.getSignalLevel());
      item.setHazardCode(d.getHazardCode());
      item.setRegionCode(d.getRegionCode());
      item.setLongitude(lon);
      item.setLatitude(lat);
      if (latest != null) {
        item.setSpeedKmh(toDouble(latest[3]));
        item.setHeadingDeg(toDouble(latest[4]));
      }
      item.setLastReportAt(latest != null && latest[7] != null
          ? ((java.sql.Timestamp) latest[7]).toInstant().atOffset(ZoneOffset.UTC)
          : d.getLastReportAt());
      item.setMobile(mobile);
      items.add(item);

      stats.setTotal(stats.getTotal() + 1);
      if (mobile) stats.setMobile(stats.getMobile() + 1);
      else         stats.setFixed(stats.getFixed() + 1);
      String status = d.getOnlineStatus() == null ? "" : d.getOnlineStatus();
      switch (status) {
        case "在线" -> stats.setOnline(stats.getOnline() + 1);
        case "离线" -> stats.setOffline(stats.getOffline() + 1);
        case "故障" -> stats.setFault(stats.getFault() + 1);
        default -> {}
      }
    }
    stats.setTrajectoryDevices(codesWithTrajectory.size());

    DeviceMapResponse resp = new DeviceMapResponse();
    resp.setDevices(items);
    resp.setStats(stats);
    resp.setGeneratedAt(OffsetDateTime.now(ZoneOffset.UTC));
    return resp;
  }

  /** 按 deviceCode 查询轨迹（按时间升序，最多 maxPoints 个）。 */
  public List<TrajectoryPoint> fetchTrajectory(String deviceCode, OffsetDateTime start, OffsetDateTime end, int maxPoints) {
    if (deviceCode == null || deviceCode.isBlank()) return List.of();
    int cap = Math.max(10, Math.min(2000, maxPoints));
    List<DeviceTrajectory> raw = trajectoryRepository.findTrajectory(deviceCode, start, end, cap);
    return raw.stream().map(t -> {
      TrajectoryPoint p = new TrajectoryPoint();
      p.setLongitude(t.getLongitude());
      p.setLatitude(t.getLatitude());
      p.setRecordedAt(t.getRecordedAt());
      p.setSpeedKmh(t.getSpeedKmh());
      p.setHeadingDeg(t.getHeadingDeg());
      p.setBatteryLevel(t.getBatteryLevel());
      p.setSource(t.getSource());
      return p;
    }).collect(Collectors.toList());
  }

  /** 当前所有"移动"设备的 deviceCode 列表。 */
  public List<String> mobileDeviceCodes() {
    return deviceRepository.findAll().stream()
        .filter(this::isMobile)
        .map(MonitoringDevice::getDeviceCode)
        .collect(Collectors.toList());
  }

  /** 判断设备是否可移动（deviceType 含关键词）。 */
  public boolean isMobile(MonitoringDevice d) {
    if (d == null || d.getDeviceType() == null) return false;
    String t = d.getDeviceType().toLowerCase();
    return MOBILE_KEYWORDS.stream().anyMatch(k -> t.contains(k));
  }

  /** 提取移动子类型（drone / vehicle / handheld）用于前端图标选择。 */
  public String extractMobilityKind(MonitoringDevice d) {
    if (!isMobile(d)) return null;
    String t = d.getDeviceType().toLowerCase();
    if (t.contains("drone") || t.contains("uav") || t.contains("无人")) return "drone";
    if (t.contains("vehicle") || t.contains("车")) return "vehicle";
    if (t.contains("handheld") || t.contains("手持")) return "handheld";
    return "mobile";
  }

  private static Double toDouble(Object v) {
    if (v == null) return null;
    if (v instanceof Number n) return n.doubleValue();
    return null;
  }

  private static Integer toInt(Object v) {
    if (v == null) return null;
    if (v instanceof Number n) return n.intValue();
    return null;
  }
}
