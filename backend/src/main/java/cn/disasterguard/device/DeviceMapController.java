package cn.disasterguard.device;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.device.dto.DeviceMapDtos.DeviceMapResponse;
import cn.disasterguard.device.dto.DeviceMapDtos.TrajectoryPoint;
import cn.disasterguard.device.service.DeviceMapService;
import cn.disasterguard.device.service.DeviceTrajectoryBootstrap;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备地图与轨迹回放 API（v1.9.0 P1）。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET /api/device-map} — 全量设备地图（设备列表 + 当前快照位置 + 统计）</li>
 *   <li>{@code GET /api/device-map/trajectory/{deviceCode}} — 单设备轨迹（按时间范围）</li>
 *   <li>{@code POST /api/device-map/trajectory/regenerate} — 演示用：手动触发 mock 轨迹生成（无数据时使用）</li>
 * </ul>
 *
 * <p>权限：与设备管理同源（devices:view / devices:view）。
 */
@RestController
@RequestMapping("/api/device-map")
public class DeviceMapController {

  private final DeviceMapService deviceMapService;
  private final DeviceTrajectoryBootstrap bootstrap;

  public DeviceMapController(DeviceMapService deviceMapService, DeviceTrajectoryBootstrap bootstrap) {
    this.deviceMapService = deviceMapService;
    this.bootstrap = bootstrap;
  }

  /** 全量设备地图视图。 */
  @GetMapping
  @PreAuthorize("hasAnyAuthority('devices:view','ROLE_ADMIN','ROLE_OPERATOR','ROLE_VIEWER')")
  public ApiResponse<DeviceMapResponse> getDeviceMap() {
    return ApiResponse.success(deviceMapService.buildDeviceMap());
  }

  /** 单设备轨迹查询。 */
  @GetMapping("/trajectory/{deviceCode}")
  @PreAuthorize("hasAnyAuthority('devices:view','ROLE_ADMIN','ROLE_OPERATOR','ROLE_VIEWER')")
  public ApiResponse<Map<String, Object>> getTrajectory(
      @PathVariable String deviceCode,
      @RequestParam(required = false) String start,
      @RequestParam(required = false) String end,
      @RequestParam(defaultValue = "500") int maxPoints) {
    OffsetDateTime startTime = (start == null || start.isBlank())
        ? OffsetDateTime.now().minusHours(24)
        : OffsetDateTime.parse(start);
    OffsetDateTime endTime = (end == null || end.isBlank())
        ? OffsetDateTime.now()
        : OffsetDateTime.parse(end);

    List<TrajectoryPoint> points = deviceMapService.fetchTrajectory(deviceCode, startTime, endTime, maxPoints);
    return ApiResponse.success(Map.of(
        "deviceCode", deviceCode,
        "start", startTime,
        "end", endTime,
        "totalPoints", points.size(),
        "points", points
    ));
  }

  /**
   * 演示用：手动触发轨迹 mock 生成（首次启动后无轨迹时回填）。
   * 调用后返回生成状态。
   */
  @PostMapping("/trajectory/regenerate")
  @PreAuthorize("hasAnyAuthority('devices:view','ROLE_ADMIN','ROLE_OPERATOR')")
  public ApiResponse<Map<String, Object>> regenerateTrajectories() {
    int before = bootstrap.countTrajectories();
    bootstrap.ensureTrajectoriesExist();
    int after = bootstrap.countTrajectories();
    return ApiResponse.success(Map.of(
        "before", before,
        "after", after,
        "generated", after - before
    ));
  }
}
