package cn.geosentry.personnel;

import cn.geosentry.common.api.ApiResponse;
import cn.geosentry.hazard.entity.HazardPoint;
import cn.geosentry.hazard.repository.HazardPointRepository;
import cn.geosentry.inspection.entity.InspectionRecord;
import cn.geosentry.inspection.repository.InspectionRecordRepository;
import cn.geosentry.realtime.RealtimeBroadcastService;
import cn.geosentry.task.entity.InspectionTask;
import cn.geosentry.task.repository.InspectionTaskRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 值守人员位置接口：
 * - GET /positions：优先从 Redis 读取移动端实时上报的位置，fallback 到巡检签到坐标 / 任务关联灾害点坐标
 * - POST /positions/report：移动端每 10~30s 上报一次，写入 Redis 并广播到 WebSocket
 */
@RestController
@RequestMapping("/api/personnel")
public class PersonnelController {

  private static final String REDIS_POS_PREFIX = "personnel:pos:";
  private static final long REDIS_POS_TTL_SECONDS = 120; // 2 分钟未更新则视为离线

  private final InspectionRecordRepository recordRepository;
  private final InspectionTaskRepository taskRepository;
  private final HazardPointRepository hazardRepository;
  private final RedisTemplate<String, Object> redisTemplate;
  private final RealtimeBroadcastService broadcastService;

  public PersonnelController(InspectionRecordRepository recordRepository,
                             InspectionTaskRepository taskRepository,
                             HazardPointRepository hazardRepository,
                             RedisTemplate<String, Object> redisTemplate,
                             RealtimeBroadcastService broadcastService) {
    this.recordRepository = recordRepository;
    this.taskRepository = taskRepository;
    this.hazardRepository = hazardRepository;
    this.redisTemplate = redisTemplate;
    this.broadcastService = broadcastService;
  }

  /**
   * 移动端实时位置上报。幂等（同一用户 2 分钟内重复上报会刷新 TTL）。
   * 若 Redis 不可用则优雅降级，不阻塞移动端点。
   */
  @PostMapping("/positions/report")
  public ApiResponse<Map<String, Object>> report(@RequestBody PositionReportRequest request) {
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("id", request.userId());
    position.put("name", request.name() == null ? request.userId() : request.name());
    position.put("lon", request.lon());
    position.put("lat", request.lat());
    position.put("status", "在线");
    position.put("lastSeen", OffsetDateTime.now().toString());
    if (request.taskCode() != null) position.put("taskCode", request.taskCode());
    if (request.hazardCode() != null) position.put("hazardCode", request.hazardCode());
    if (request.speed() != null) position.put("speed", request.speed());
    if (request.heading() != null) position.put("heading", request.heading());

    boolean cached = false;
    try {
      redisTemplate.opsForValue().set(
          REDIS_POS_PREFIX + request.userId(), position,
          REDIS_POS_TTL_SECONDS, TimeUnit.SECONDS);
      cached = true;
    } catch (Exception e) {
      // Redis 不可用时不阻塞上报，但仍广播给当前连接的前端
    }

    // 广播给 WebSocket 连接的前端
    if (broadcastService != null) {
      broadcastService.broadcastPersonnel(position);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("accepted", true);
    result.put("cached", cached);
    result.put("recordedAt", OffsetDateTime.now().toString());
    return ApiResponse.success(result);
  }

  @GetMapping("/positions")
  public ApiResponse<List<Map<String, Object>>> positions() {
    // 优先从 Redis 读取移动端上报的实时位置
    List<Map<String, Object>> redisPositions = new ArrayList<>();
    try {
      Set<String> keys = redisTemplate.keys(REDIS_POS_PREFIX + "*");
      if (keys != null) {
        for (String key : keys) {
          @SuppressWarnings("unchecked")
          Map<String, Object> pos = (Map<String, Object>) redisTemplate.opsForValue().get(key);
          if (pos != null) {
            redisPositions.add(pos);
          }
        }
      }
    } catch (Exception e) {
      // Redis 不可用时忽略，回退 DB 模式
    }

    if (!redisPositions.isEmpty()) {
      // Redis 有实时数据，仍追加 DB 中的值守人员（作为补充）
      List<Map<String, Object>> dbPositions = buildDbPositions();
      Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
      for (Map<String, Object> p : dbPositions) {
        merged.put(String.valueOf(p.get("id")), p);
      }
      for (Map<String, Object> p : redisPositions) {
        merged.put(String.valueOf(p.get("id")), p); // Redis 覆盖 DB
      }
      return ApiResponse.success(new ArrayList<>(merged.values()));
    }

    // Redis 无数据，走 DB fallback
    return ApiResponse.success(buildDbPositions());
  }

  /** 从 inspection_record + inspection_task 生成值守人员列表（无 Redis 时 fallback）。 */
  private List<Map<String, Object>> buildDbPositions() {
    List<Map<String, Object>> result = new ArrayList<>();

    Map<String, InspectionTask> taskByExecutor = new LinkedHashMap<>();
    for (InspectionTask task : taskRepository.findAll()) {
      String executor = task.getExecutor();
      if (executor != null && !executor.isBlank() && !taskByExecutor.containsKey(executor)) {
        taskByExecutor.put(executor, task);
      }
    }

    Map<String, InspectionRecord> latestByInspector = new HashMap<>();
    for (InspectionRecord rec : recordRepository.findAll()) {
      String inspector = rec.getInspector();
      if (inspector == null || inspector.isBlank()) continue;
      InspectionRecord prev = latestByInspector.get(inspector);
      if (prev == null || (rec.getInspectAt() != null && (prev.getInspectAt() == null || rec.getInspectAt().isAfter(prev.getInspectAt())))) {
        latestByInspector.put(inspector, rec);
      }
    }

    for (Map.Entry<String, InspectionTask> entry : taskByExecutor.entrySet()) {
      String name = entry.getKey();
      InspectionTask task = entry.getValue();
      Double lon = null;
      Double lat = null;
      String status = "待命";
      OffsetDateTime lastSeen = null;

      InspectionRecord latest = latestByInspector.get(name);
      if (latest != null && latest.getSignInLon() != null && latest.getSignInLat() != null) {
        lon = latest.getSignInLon().doubleValue();
        lat = latest.getSignInLat().doubleValue();
        status = "巡检中";
        lastSeen = latest.getInspectAt();
      } else if (task.getHazardCode() != null) {
        Optional<HazardPoint> hazard = hazardRepository.findAll().stream()
            .filter(h -> task.getHazardCode().equals(h.getHazardCode())).findFirst();
        if (hazard.isPresent()) {
          lon = hazard.get().getLongitude();
          lat = hazard.get().getLatitude();
          status = "待出发";
        }
      }

      Map<String, Object> person = new LinkedHashMap<>();
      person.put("id", name);
      person.put("name", name);
      person.put("role", "值班员");
      person.put("status", status);
      person.put("taskCode", task.getTaskCode());
      person.put("taskName", task.getName());
      person.put("hazardCode", task.getHazardCode());
      person.put("lon", lon == null ? null : lon);
      person.put("lat", lat == null ? null : lat);
      person.put("lastSeen", lastSeen == null ? null : lastSeen.toString());
      result.add(person);
    }

    if (result.isEmpty()) {
      result.add(samplePerson("周小雨", "龙安镇"));
      result.add(samplePerson("王建国", "石桥镇"));
      result.add(samplePerson("陈晓峰", "清河镇"));
    }
    return result;
  }

  private Map<String, Object> samplePerson(String name, String region) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", name);
    m.put("name", name);
    m.put("role", "值班员");
    m.put("status", "待命");
    m.put("taskCode", null);
    m.put("taskName", null);
    m.put("hazardCode", null);
    m.put("lon", null);
    m.put("lat", null);
    m.put("lastSeen", null);
    m.put("region", region);
    return m;
  }

  /** 移动端位置上报请求体（record 类型便于前端提交 JSON）。 */
  public record PositionReportRequest(
      String userId,
      String name,
      BigDecimal lon,
      BigDecimal lat,
      String taskCode,
      String hazardCode,
      Double speed,
      Double heading
  ) {}
}
