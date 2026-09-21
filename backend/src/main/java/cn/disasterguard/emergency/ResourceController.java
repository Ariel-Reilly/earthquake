package cn.disasterguard.emergency;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.exception.AppException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

  /**
   * 与 {@link Map#of(Object, Object)} 等价的工厂，但允许值为 {@code null}。
   *
   * <p>原因：{@code Map.of} / {@code Map.ofEntries} 的底层实现（{@code ImmutableCollections.MapN}）
   * 对任何 null 键或 null 值都会抛 {@link NullPointerException}，而本类的静态 mock 数据中
   * 部分资源未指派任务，{@code currentTask} 字段需要为 {@code null}。
   */
  private static Map<String, Object> mockEntry(Object... kv) {
    if (kv.length % 2 != 0) {
      throw new IllegalArgumentException("mockEntry 需要成对的 key/value");
    }
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static final List<Map<String, Object>> MOCK_RESOURCES = List.of(
      mockEntry(
          "resourceId", 1L,
          "name", "应急救援队A组",
          "type", "PERSONNEL",
          "status", "AVAILABLE",
          "location", "南昌基地",
          "contact", "张队长 13800138001",
          "capacity", 15,
          "currentTask", null
      ),
      mockEntry(
          "resourceId", 2L,
          "name", "应急救援车001",
          "type", "VEHICLE",
          "status", "BUSY",
          "location", "G105国道",
          "contact", "李司机 13800138002",
          "capacity", 8,
          "currentTask", "隐患点#H001巡检"
      ),
      mockEntry(
          "resourceId", 3L,
          "name", "监测无人机M1",
          "type", "EQUIPMENT",
          "status", "AVAILABLE",
          "location", "南昌基地",
          "contact", "王操作员 13800138003",
          "capacity", 1,
          "currentTask", null
      ),
      mockEntry(
          "resourceId", 4L,
          "name", "应急救援队B组",
          "type", "PERSONNEL",
          "status", "OFFLINE",
          "location", "休息中",
          "contact", "赵队长 13800138004",
          "capacity", 12,
          "currentTask", null
      ),
      mockEntry(
          "resourceId", 5L,
          "name", "应急救援车002",
          "type", "VEHICLE",
          "status", "AVAILABLE",
          "location", "赣州基地",
          "contact", "刘司机 13800138005",
          "capacity", 8,
          "currentTask", null
      )
  );

  @GetMapping
  @PreAuthorize("hasAuthority('resource:view')")
  public ApiResponse<PageResponse<Map<String, Object>>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword
  ) {
    List<Map<String, Object>> filtered = MOCK_RESOURCES.stream()
        .filter(r -> type == null || type.isBlank() || r.get("type").equals(type))
        .filter(r -> status == null || status.isBlank() || r.get("status").equals(status))
        .filter(r -> keyword == null || keyword.isBlank() ||
            ((String) r.get("name")).contains(keyword) ||
            ((String) r.get("location")).contains(keyword))
        .toList();

    int total = filtered.size();
    int pages = (int) Math.ceil((double) total / size);
    int fromIndex = (page - 1) * size;
    int toIndex = Math.min(fromIndex + size, total);

    List<Map<String, Object>> items = fromIndex < total
        ? filtered.subList(fromIndex, toIndex)
        : new ArrayList<>();

    PageResponse<Map<String, Object>> response = new PageResponse<>(items, total, page, size, pages);
    return ApiResponse.success(response);
  }

  /**
   * 资源指派。
   *
   * <p>请求体：{@code { resourceId: number, taskCode: string }}
   *
   * <p>说明：保持向后兼容，前端 {@code assignResource()} 调用此接口。
   */
  @PostMapping("/assign")
  @PreAuthorize("hasAuthority('resource:view')")
  public ApiResponse<Map<String, Object>> assign(@RequestBody Map<String, Object> request) {
    if (request == null) {
      throw new AppException(400, "请求体不能为空");
    }
    Object rawId = request.get("resourceId");
    if (!(rawId instanceof Number)) {
      throw new AppException(400, "缺少参数 resourceId");
    }
    Object rawTask = request.get("taskCode");
    if (!(rawTask instanceof String) || ((String) rawTask).isBlank()) {
      throw new AppException(400, "缺少参数 taskCode");
    }
    Long resourceId = ((Number) rawId).longValue();
    String taskCode = (String) rawTask;

    // 可选：校验资源是否真实存在
    boolean exists = MOCK_RESOURCES.stream()
        .anyMatch(r -> ((Number) r.get("resourceId")).longValue() == resourceId);
    if (!exists) {
      throw new AppException(404, "资源不存在: " + resourceId);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("resourceId", resourceId);
    result.put("assignedTo", taskCode);
    result.put("assignedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
    return ApiResponse.success("资源分配成功", result);
  }

  /**
   * 路径规划（旧接口，与 {@code /api/path-planning/calculate} 并存）。
   *
   * <p>请求体：{@code { startLon, startLat, endLon, endLat, avoidZones?: number[][] }}
   *
   * <p>响应：{@code Array<{ type, distance, duration, warning?, geometry: [[lon,lat],...] }>}
   *
   * <p>说明：当前为占位实现，返回 fastest + safest 两条确定性伪路径，避免旧版前端因 404 报错。
   */
  @PostMapping("/route")
  @PreAuthorize("hasAuthority('resource:view')")
  public ApiResponse<List<Map<String, Object>>> planRoute(@RequestBody Map<String, Object> request) {
    if (request == null) {
      throw new AppException(400, "请求体不能为空");
    }
    double startLon = readDouble(request, "startLon", true);
    double startLat = readDouble(request, "startLat", true);
    double endLon = readDouble(request, "endLon", true);
    double endLat = readDouble(request, "endLat", true);

    double distance = haversineKm(startLat, startLon, endLat, endLon);
    long fastestSec = Math.max(60, Math.round(distance * 90));   // ~40km/h
    long safestSec = Math.max(60, Math.round(distance * 140));   // ~26km/h

    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    int wp = 5;
    List<Map<String, Object>> fastestGeom = buildSyntheticGeometry(
        startLon, startLat, endLon, endLat, wp, rnd.nextInt(10000)
    );
    List<Map<String, Object>> safestGeom = buildSyntheticGeometry(
        startLon, startLat, endLon, endLat, wp + 2, rnd.nextInt(10000)
    );

    Map<String, Object> fastest = new LinkedHashMap<>();
    fastest.put("type", "fastest");
    fastest.put("distance", round2(distance));
    fastest.put("duration", fastestSec + "s");
    fastest.put("geometry", fastestGeom);

    Map<String, Object> safest = new LinkedHashMap<>();
    safest.put("type", "safest");
    safest.put("distance", round2(distance * 1.12));
    safest.put("duration", safestSec + "s");
    if (rnd.nextDouble() < 0.2) {
      safest.put("warning", "沿途存在 1 个高风险隐患点，建议规避");
    }
    safest.put("geometry", safestGeom);

    return ApiResponse.success(List.of(fastest, safest));
  }

  // 工具方法

  private static double readDouble(Map<String, Object> map, String key, boolean required) {
    Object v = map.get(key);
    if (v == null) {
      if (required) {
        throw new AppException(400, "缺少参数 " + key);
      }
      return 0d;
    }
    if (v instanceof Number) {
      return ((Number) v).doubleValue();
    }
    if (v instanceof String) {
      try {
        return Double.parseDouble((String) v);
      } catch (NumberFormatException ignore) {
        // fall through
      }
    }
    throw new AppException(400, "参数 " + key + " 类型错误");
  }

  private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
    double R = 6371.0088;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c;
  }

  private static double round2(double v) {
    return Math.round(v * 100) / 100.0;
  }

  /** 构造一条起点到终点的确定性折线（占位几何）。 */
  private static List<Map<String, Object>> buildSyntheticGeometry(
      double startLon, double startLat, double endLon, double endLat, int points, long seed
  ) {
    List<Map<String, Object>> geometry = new ArrayList<>(points);
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    for (int i = 0; i < points; i++) {
      double t = (double) i / (points - 1);
      double lon = startLon + (endLon - startLon) * t + ((rnd.nextDouble() - 0.5) * 0.001 * (seed % 5 + 1));
      double lat = startLat + (endLat - startLat) * t + ((rnd.nextDouble() - 0.5) * 0.001 * (seed % 3 + 1));
      Map<String, Object> pt = new LinkedHashMap<>();
      pt.put("lon", round6(lon));
      pt.put("lat", round6(lat));
      geometry.add(pt);
    }
    return geometry;
  }

  private static double round6(double v) {
    return Math.round(v * 1_000_000) / 1_000_000.0;
  }

  // 保留 UUID 导入便于后续接入真实任务流
  @SuppressWarnings("unused")
  private static String newCorrelationId() {
    return UUID.randomUUID().toString();
  }
}
