package cn.disasterguard.emergency;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.emergency.entity.EmergencyPlan;
import cn.disasterguard.emergency.repository.EmergencyPlanRepository;
import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

/**
 * 预案管理（Plan）REST API。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET /api/plans} — 分页列表</li>
 *   <li>{@code GET /api/plans/{planId}} — 详情</li>
 *   <li>{@code POST /api/plans} — 创建</li>
 *   <li>{@code PUT /api/plans/{planId}} — 更新</li>
 *   <li>{@code DELETE /api/plans/{planId}} — 删除（仅 DRAFT 状态）</li>
 *   <li>{@code POST /api/plans/{planId}/simulate-launch} — 模拟启动</li>
 *   <li>{@code GET /api/plans/launches} — 启动历史</li>
 * </ul>
 *
 * <p>M6 技术债改造：预案由内存 ConcurrentHashMap 改为
 * {@code emergency_plan} JSONB 落库，端点、路径与请求/响应结构保持不变。
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

  private final EmergencyPlanRepository planRepo;

  /** id 计数器：启动时以库内最大 id 初始化，新增记录用 incrementAndGet。 */
  private final AtomicLong idGen = new AtomicLong();

  /** 模拟启动历史（原内存实现，原样保留）。 */
  private final List<Map<String, Object>> launchHistory = new ArrayList<>();

  public PlanController(EmergencyPlanRepository planRepo) {
    this.planRepo = planRepo;
  }

  @PostConstruct
  void init() {
    idGen.set(planRepo.findMaxId());
    if (planRepo.count() == 0) {
      seedPlans();
    }
  }

  private void seedPlans() {
    for (int i = 1; i <= 5; i++) {
      long id = idGen.incrementAndGet();
      Map<String, Object> plan = new LinkedHashMap<>();
      plan.put("planId", id);
      plan.put("name", "地震应急响应预案 " + i);
      plan.put("hazardType", i % 2 == 0 ? "地震" : "洪涝");
      plan.put("level", i % 3 == 0 ? "I级（特别重大）" : (i % 3 == 1 ? "II级（重大）" : "III级（较大）"));
      plan.put("status", i == 1 ? "已发布" : (i == 2 ? "草稿" : "已发布"));
      plan.put("version", "1." + (i - 1) + ".0");
      plan.put("content", "本预案适用于" + (i % 2 == 0 ? "地震" : "洪涝") + "灾害的应急响应工作...");
      plan.put("steps", List.of(
          Map.of("step", 1, "action", "启动应急响应", "estimatedTime", "5分钟"),
          Map.of("step", 2, "action", "组织人员疏散", "estimatedTime", "30分钟"),
          Map.of("step", 3, "action", "调配救援资源", "estimatedTime", "1小时")
      ));
      plan.put("contacts", List.of(
          Map.of("name", "应急指挥中心", "phone", "010-12345678"),
          Map.of("name", "救援队伍", "phone", "010-87654321")
      ));
      plan.put("updatedAt", OffsetDateTime.now().minusDays(i).toString());
      savePlan(id, plan);
    }
  }

  private EmergencyPlan savePlan(Long id, Map<String, Object> payload) {
    EmergencyPlan entity = new EmergencyPlan();
    entity.setId(id);
    entity.setPayload(payload);
    return planRepo.save(entity);
  }

  @GetMapping
  @PreAuthorize("hasAuthority('plans:view')")
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String hazardType,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {
    List<Map<String, Object>> all = planRepo.findAll().stream()
        .sorted(Comparator.comparing(EmergencyPlan::getId))
        .map(EmergencyPlan::getPayload)
        .toList();
    var filtered = all.stream()
        .filter(p -> hazardType == null || hazardType.isBlank()
            || hazardType.equals(p.get("hazardType")))
        .filter(p -> level == null || level.isBlank()
            || level.equals(p.get("level")))
        .filter(p -> status == null || status.isBlank()
            || status.equals(p.get("status")))
        .filter(p -> {
          if (keyword == null || keyword.isBlank()) return true;
          String k = keyword.toLowerCase();
          String name = String.valueOf(p.get("name")).toLowerCase();
          return name.contains(k);
        })
        .sorted((a, b) -> {
          // updatedAt 已统一为 ISO 字符串，字典序即时间序
          var ta = String.valueOf(b.get("updatedAt"));
          var tb = String.valueOf(a.get("updatedAt"));
          return ta.compareTo(tb);
        })
        .toList();

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);
    int total = filtered.size();
    int pages = Math.max(1, (int) Math.ceil(total / (double) safeSize));
    int fromIndex = Math.min((safePage - 1) * safeSize, total);
    int toIndex = Math.min(fromIndex + safeSize, total);
    List<Map<String, Object>> pageItems = new ArrayList<>();
    for (var p : filtered.subList(fromIndex, toIndex)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("planId", p.get("planId"));
      row.put("name", p.get("name"));
      row.put("hazardType", p.get("hazardType"));
      row.put("level", p.get("level"));
      row.put("status", p.get("status"));
      row.put("version", p.get("version"));
      row.put("updatedAt", p.get("updatedAt"));
      pageItems.add(row);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", pageItems);
    result.put("total", total);
    result.put("page", safePage);
    result.put("size", safeSize);
    return ApiResponse.success(result);
  }

  @GetMapping("/{planId}")
  @PreAuthorize("hasAuthority('plans:view')")
  public ApiResponse<Map<String, Object>> get(@PathVariable Long planId) {
    Map<String, Object> plan = planRepo.findById(planId)
        .map(EmergencyPlan::getPayload).orElse(null);
    if (plan == null) {
      return ApiResponse.fail(404, "预案不存在: " + planId);
    }
    return ApiResponse.success(plan);
  }

  @PostMapping
  @PreAuthorize("hasAuthority('plans:edit')")
  public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
    long id = idGen.incrementAndGet();
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("planId", id);
    plan.put("name", body.getOrDefault("name", "新预案"));
    plan.put("hazardType", body.getOrDefault("hazardType", "地震"));
    plan.put("level", body.getOrDefault("level", "III级（较大）"));
    plan.put("status", "草稿");
    plan.put("version", "1.0.0");
    plan.put("content", body.getOrDefault("content", ""));
    plan.put("steps", body.getOrDefault("steps", new ArrayList<>()));
    plan.put("contacts", body.getOrDefault("contacts", new ArrayList<>()));
    plan.put("updatedAt", OffsetDateTime.now().toString());
    savePlan(id, plan);
    return ApiResponse.success(plan);
  }

  @PutMapping("/{planId}")
  @PreAuthorize("hasAuthority('plans:edit')")
  public ApiResponse<Map<String, Object>> update(@PathVariable Long planId,
                                                 @RequestBody Map<String, Object> body) {
    EmergencyPlan entity = planRepo.findById(planId).orElse(null);
    if (entity == null) {
      return ApiResponse.fail(404, "预案不存在: " + planId);
    }
    Map<String, Object> existing = entity.getPayload();
    if (body.containsKey("name")) existing.put("name", body.get("name"));
    if (body.containsKey("hazardType")) existing.put("hazardType", body.get("hazardType"));
    if (body.containsKey("level")) existing.put("level", body.get("level"));
    if (body.containsKey("content")) existing.put("content", body.get("content"));
    if (body.containsKey("steps")) existing.put("steps", body.get("steps"));
    if (body.containsKey("contacts")) existing.put("contacts", body.get("contacts"));
    existing.put("updatedAt", OffsetDateTime.now().toString());
    entity.setPayload(existing);
    planRepo.save(entity);
    return ApiResponse.success(existing);
  }

  @DeleteMapping("/{planId}")
  @PreAuthorize("hasAuthority('plans:edit')")
  public ApiResponse<Map<String, Object>> delete(@PathVariable Long planId) {
    EmergencyPlan entity = planRepo.findById(planId).orElse(null);
    if (entity == null) {
      return ApiResponse.fail(404, "预案不存在: " + planId);
    }
    Map<String, Object> plan = entity.getPayload();
    if (!"草稿".equals(plan.get("status"))) {
      return ApiResponse.fail(400, "仅草稿状态的预案可删除");
    }
    planRepo.deleteById(planId);
    return ApiResponse.success(Map.of("deleted", planId));
  }

  @PostMapping("/{planId}/simulate-launch")
  @PreAuthorize("hasAuthority('plans:edit')")
  public ApiResponse<Map<String, Object>> simulateLaunch(@PathVariable Long planId) {
    Map<String, Object> plan = planRepo.findById(planId)
        .map(EmergencyPlan::getPayload).orElse(null);
    if (plan == null) {
      return ApiResponse.fail(404, "预案不存在: " + planId);
    }
    Map<String, Object> simulation = new LinkedHashMap<>();
    simulation.put("simulationId", "SIM-" + System.currentTimeMillis());
    simulation.put("steps", List.of(
        Map.of("step", 1, "action", "启动应急响应指挥体系", "estimatedTime", "5分钟",
            "resourcesNeeded", List.of("指挥车1辆", "通信设备2套")),
        Map.of("step", 2, "action", "发布预警信息", "estimatedTime", "10分钟",
            "resourcesNeeded", List.of("广播车1辆", "手机短信通道")),
        Map.of("step", 3, "action", "组织群众疏散", "estimatedTime", "30分钟",
            "resourcesNeeded", List.of("疏散车辆10辆", "志愿者20人")),
        Map.of("step", 4, "action", "调配救援队伍", "estimatedTime", "45分钟",
            "resourcesNeeded", List.of("救援队3支", "救护车2辆"))
    ));
    simulation.put("summary", "模拟启动成功，预计总响应时间约90分钟");
    return ApiResponse.success(simulation);
  }

  /**
   * 启动预案（POST /api/plans/{planId}/launch）。
   *
   * <p>复用 {@link #simulateLaunch(Long)} 的预案读取与 {@link #launchHistory} 启动历史，
   * 并复用 {@link #update(Long, Map)} 的 {@code setPayload + planRepo.save} 持久化链路：
   * 把启动信息真实回写到 {@code emergency_plan.payload}。操作人取 SecurityContext。
   *
   * <p>与 {@code simulate-launch} 的区别：模拟只算不落库；本端点会写入启动历史 +
   * 预案最近启动信息，供 {@code GET /plans/launches} 查询。
   */
  @PostMapping("/{planId}/launch")
  @PreAuthorize("hasAuthority('plans:launch')")
  public ApiResponse<Map<String, Object>> launch(
      @PathVariable Long planId,
      @RequestBody(required = false) Map<String, Object> params,
      Authentication authentication) {
    String operator = authentication == null ? "system" : authentication.getName();
    EmergencyPlan entity = planRepo.findById(planId).orElse(null);
    if (entity == null) {
      return ApiResponse.fail(404, "预案不存在: " + planId);
    }
    Map<String, Object> plan = entity.getPayload();
    if (!"已发布".equals(String.valueOf(plan.get("status")))) {
      return ApiResponse.fail(400, "仅「已发布」状态的预案可启动，当前状态: " + plan.get("status"));
    }
    Map<String, Object> body = params == null ? Map.of() : params;
    OffsetDateTime now = OffsetDateTime.now();
    String launchId = "LAUNCH-" + now.toEpochSecond() + "-" + planId;

    Object steps = plan.get("steps") == null ? List.of() : plan.get("steps");
    Map<String, Object> launch = new LinkedHashMap<>();
    launch.put("launchId", launchId);
    launch.put("planId", planId);
    launch.put("name", plan.get("name"));
    launch.put("level", plan.get("level"));
    launch.put("status", "执行中");
    launch.put("launchedBy", operator);
    launch.put("launchedAt", now.toString());
    launch.put("trigger", body.getOrDefault("trigger", "manual"));
    launch.put("steps", steps);
    launch.put("stepCount", steps instanceof List<?> l ? l.size() : 0);

    synchronized (launchHistory) {
      launchHistory.add(launch);
    }

    // 回写预案：真实落库，记录最近一次启动
    plan.put("lastLaunchId", launchId);
    plan.put("lastLaunchedAt", now.toString());
    plan.put("lastLaunchedBy", operator);
    Object count = plan.get("launchCount");
    plan.put("launchCount", count instanceof Number n ? n.intValue() + 1 : 1);
    entity.setPayload(plan);
    planRepo.save(entity);

    return ApiResponse.success("预案已启动", launch);
  }

  @GetMapping("/launches")
  @PreAuthorize("hasAuthority('plans:view')")
  public ApiResponse<List<Map<String, Object>>> listLaunches() {
    return ApiResponse.success(launchHistory);
  }
}
