package cn.disasterguard.device;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.device.entity.DeviceTrajectory;
import cn.disasterguard.device.repository.DeviceTrajectoryRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备历史轨迹回放（{@code device_trajectory} 表）。
 *
 * <p>v1.8.0 起改为读取真实库：此前本端点用 {@code new Random()} 生成随机游走轨迹，
 * 基准点写死为福建坐标 {@code (26.08, 119.28)}，与库中 170 条重庆真实轨迹
 * （106.4x/29.5x）无关，且每次请求结果都不同。
 *
 * <p>现在复用 {@link DeviceTrajectoryRepository#findTrajectory}（{@code DeviceMapService}
 * 已在使用的同一条 SQL），按 device_code + 时间范围查真实采样点；
 * 库中无该设备数据时如实返回空 points。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceTrackController {

  private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  /** 轨迹点上限（防止一次性拉爆内存）。 */
  private static final int MAX_LIMIT = 10000;

  /** 默认回看窗口（小时）。 */
  private static final int DEFAULT_WINDOW_HOURS = 24;

  private final DeviceTrajectoryRepository trajectoryRepository;

  public DeviceTrackController(DeviceTrajectoryRepository trajectoryRepository) {
    this.trajectoryRepository = trajectoryRepository;
  }

  @GetMapping("/{deviceId}/track")
  @PreAuthorize("hasAuthority('devices:view')")
  public ApiResponse<Map<String, Object>> getTrack(
      @PathVariable String deviceId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "1000") int limit) {

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime fromTime = parseOrNull(from);
    OffsetDateTime toTime = parseOrNull(to);
    if (fromTime == null && toTime == null) {
      fromTime = now.minusHours(DEFAULT_WINDOW_HOURS);
      toTime = now;
    }

    int maxPoints = limit <= 0 ? 1000 : Math.min(limit, MAX_LIMIT);

    // 设备编码统一规范：入参可能是 "004" / "dv-004" / "DV-004"，库里统一为大写 DV-xxx
    String deviceCode = normalizeDeviceCode(deviceId);

    List<DeviceTrajectory> rows =
        trajectoryRepository.findTrajectory(deviceCode, fromTime, toTime, maxPoints);

    List<Map<String, Object>> points = new ArrayList<>(rows.size());
    for (DeviceTrajectory t : rows) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("timestamp", t.getRecordedAt() == null ? null : t.getRecordedAt().toString());
      p.put("lat", t.getLatitude());
      p.put("lon", t.getLongitude());
      p.put("speed", t.getSpeedKmh());
      p.put("heading", t.getHeadingDeg());
      p.put("altitude", t.getAltitudeM());
      p.put("battery", t.getBatteryLevel());
      p.put("source", t.getSource());
      points.add(p);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("deviceId", deviceId);
    result.put("deviceCode", deviceCode);
    result.put("from", fromTime == null ? null : fromTime.toString());
    result.put("to", toTime == null ? null : toTime.toString());
    result.put("total", points.size());
    result.put("points", points);
    result.put("dataSource", "device_trajectory（真实库）");
    return ApiResponse.success(result);
  }

  /** 统一设备编码：{@code 004} / {@code dv-004} -> {@code DV-004}。 */
  private String normalizeDeviceCode(String raw) {
    if (raw == null || raw.isBlank()) {
      return raw;
    }
    String s = raw.trim().toUpperCase();
    if (s.startsWith("DV-")) {
      return "DV-" + normalizeNumericPart(s.substring(3));
    }
    // 纯数字或含数字后缀
    return "DV-" + normalizeNumericPart(s);
  }

  private String normalizeNumericPart(String s) {
    String digits = s.replaceAll("[^0-9]", "");
    return digits.isEmpty() ? s : String.format("%03d", Integer.parseInt(digits));
  }

  private OffsetDateTime parseOrNull(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(iso, ISO_FORMATTER);
    } catch (Exception ex) {
      return null;
    }
  }
}
