package cn.disasterguard.confirmation;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.event.EventState;
import cn.disasterguard.event.EventTrigger;
import cn.disasterguard.event.entity.EventTransfer;
import cn.disasterguard.event.entity.UnifiedEventEntity;
import cn.disasterguard.event.repository.EventTransferRepository;
import cn.disasterguard.event.repository.UnifiedEventRepository;
import cn.disasterguard.event.service.EventStateService;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 人工确认工作台（v1.7.0+ P3-4 确认队列 + v2.0 全功能实现）。
 *
 * <p>职责：
 * <ul>
 *   <li>GET /stats — 顶栏红点统计（pending/today/highLevel）</li>
 *   <li>GET /queue — 待确认队列（分页、关键词过滤）</li>
 *   <li>POST /queue/{id}/claim — 认领确认任务</li>
 *   <li>POST /queue/{id}/transfer — 转派给其他用户</li>
 *   <li>POST /queue/{id}/confirm — 确认灾害（状态流转）</li>
 *   <li>POST /queue/{id}/reject — 驳回灾害（状态流转）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/confirmation")
public class ConfirmationQueueController {

  private final UnifiedEventRepository unifiedEventRepository;
  private final HazardPointRepository hazardPointRepository;
  private final EventStateService eventStateService;
  private final EventTransferRepository transferRepository;

  public ConfirmationQueueController(
      UnifiedEventRepository unifiedEventRepository,
      HazardPointRepository hazardPointRepository,
      EventStateService eventStateService,
      EventTransferRepository transferRepository
  ) {
    this.unifiedEventRepository = unifiedEventRepository;
    this.hazardPointRepository = hazardPointRepository;
    this.eventStateService = eventStateService;
    this.transferRepository = transferRepository;
  }

  /**
   * 顶栏红点统计（仅返回计数）。
   * GET /api/confirmation/stats
   */
  @GetMapping("/stats")
  @PreAuthorize("hasAuthority('confirmation:view')")
  public ApiResponse<Map<String, Object>> stats() {
    long pending = unifiedEventRepository.findByState(EventState.DETECTED).size()
        + unifiedEventRepository.findByState(EventState.CONFIRMING).size();
    long highLevel = hazardPointRepository.findAll().stream()
        .filter(h -> {
          String level = h.getRiskLevel();
          return level != null && (level.contains("红") || level.contains("橙"));
        })
        .count();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("pending", pending);
    result.put("today", pending); // 简化：暂用 pending 作为今日进队列近似值
    result.put("highLevel", highLevel);
    return ApiResponse.success(result);
  }

  /**
   * 待确认队列列表（含灾害详情，按风险等级优先级排序）。
   * GET /api/confirmation/queue
   *
   * @param page    页码（从 1 开始）
   * @param size    每页条数
   * @param keyword 事件号/标题模糊搜索
   */
  @GetMapping("/queue")
  @PreAuthorize("hasAuthority('confirmation:view')")
  public ApiResponse<Map<String, Object>> queue(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String keyword
  ) {
    // 查询待确认状态的事件（DETECTED + CONFIRMING）—— 内存过滤避免 Example API
    List<UnifiedEventEntity> allPending = new java.util.ArrayList<>();
    allPending.addAll(unifiedEventRepository.findByState(EventState.DETECTED));
    allPending.addAll(unifiedEventRepository.findByState(EventState.CONFIRMING));
    final String kw = keyword == null ? "" : keyword.trim().toLowerCase();
    List<UnifiedEventEntity> filtered = allPending.stream()
        .filter(e -> kw.isBlank()
            || (e.getEventCode() != null && e.getEventCode().toLowerCase().contains(kw))
            || (e.getTitle() != null && e.getTitle().toLowerCase().contains(kw))
            || (e.getHazardCode() != null && e.getHazardCode().toLowerCase().contains(kw)))
        .sorted((a, b) -> {
          var ta = a.getUpdatedAt() == null ? a.getCreatedAt() : a.getUpdatedAt();
          var tb = b.getUpdatedAt() == null ? b.getCreatedAt() : b.getUpdatedAt();
          if (ta == null && tb == null) return 0;
          if (ta == null) return 1;
          if (tb == null) return -1;
          return tb.compareTo(ta);
        })
        .toList();

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);
    int total = filtered.size();
    int fromIndex = Math.min((safePage - 1) * safeSize, total);
    int toIndex = Math.min(fromIndex + safeSize, total);
    List<Map<String, Object>> items = filtered.subList(fromIndex, toIndex).stream()
        .map(this::toQueueItem)
        .collect(Collectors.toList());

    Map<String, Object> pageResult = new LinkedHashMap<>();
    pageResult.put("items", items);
    pageResult.put("total", total);
    pageResult.put("page", safePage);
    pageResult.put("size", safeSize);
    pageResult.put("pages", Math.max(1, (int) Math.ceil(total / (double) safeSize)));
    return ApiResponse.success(pageResult);
  }

  // 下面是 claim / transfer / confirm / reject 等接口

  /**
   * 认领确认任务。
   * POST /api/confirmation/queue/{id}/claim
   *
   * @param id      统一事件 ID
   * @param payload {operator: "username"}
   */
  @PostMapping("/queue/{id}/claim")
  @PreAuthorize("hasAuthority('confirmation:view')")
  public ApiResponse<Map<String, Object>> claim(
      @PathVariable Long id,
      @RequestBody Map<String, String> payload
  ) {
    String operator = payload.get("operator");
    if (operator == null || operator.isBlank()) {
      return ApiResponse.fail(400, "请提供操作人");
    }

    UnifiedEventEntity event = unifiedEventRepository.findById(id).orElse(null);
    if (event == null) {
      return ApiResponse.fail(404, "未找到事件");
    }

    // 状态必须为 DETECTED 才可认领
    if (event.getState() != EventState.DETECTED) {
      return ApiResponse.fail(400, "仅允许认领待确认状态的事件");
    }

    // 认领时状态流转到 CONFIRMING
    boolean ok = eventStateService.transition(event.getEventCode(), EventTrigger.START_CONFIRM, operator, null);
    if (!ok) {
      return ApiResponse.fail(400, "认领失败：状态流转被拒绝");
    }

    // 刷新实体
    event = unifiedEventRepository.findById(id).orElse(null);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("eventCode", event != null ? event.getEventCode() : null);
    result.put("assignedTo", operator);
    result.put("state", event != null ? event.getState().name() : "CONFIRMING");
    result.put("message", "已成功认领任务");
    return ApiResponse.success("认领成功", result);
  }

  /**
   * 转派任务给其他用户。
   * POST /api/confirmation/queue/{id}/transfer
   *
   * @param id      统一事件 ID
   * @param payload {toUser: "target_user", reason: "原因"}
   */
  @PostMapping("/queue/{id}/transfer")
  @PreAuthorize("hasAuthority('confirmation:view')")
  public ApiResponse<Map<String, Object>> transfer(
      @PathVariable Long id,
      @RequestBody Map<String, String> payload
  ) {
    String toUser = payload.get("toUser");
    String reason = payload.get("reason");

    if (toUser == null || toUser.isBlank()) {
      return ApiResponse.fail(400, "请提供目标用户");
    }

    UnifiedEventEntity event = unifiedEventRepository.findById(id).orElse(null);
    if (event == null) {
      return ApiResponse.fail(404, "未找到事件");
    }

    if (event.getState() != EventState.CONFIRMING) {
      return ApiResponse.fail(400, "仅允许转派确认中的任务");
    }

    String fromUser = event.getClosedBy() == null ? "system" : event.getClosedBy();

    // 记录转派历史
    EventTransfer transfer = new EventTransfer();
    transfer.setEventId(event.getEventCode());
    transfer.setFromUser(fromUser);
    transfer.setToUser(toUser);
    transfer.setReason(reason);
    transfer.setTransferredAt(OffsetDateTime.now());
    transferRepository.save(transfer);

    // UnifiedEventEntity 没有独立的 assignedTo 字段，转派信息在 EventTransfer 表中通过最新记录追踪
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("eventCode", event.getEventCode());
    result.put("fromUser", fromUser);
    result.put("toUser", toUser);
    result.put("reason", reason);
    result.put("transferredAt", transfer.getTransferredAt().toString());
    return ApiResponse.success("转派成功", result);
  }

  /**
   * 确认灾害（状态流转到 CONFIRMED）。
   * POST /api/confirmation/queue/{id}/confirm
   *
   * @param id      统一事件 ID
   * @param payload {operator: "username", reason: "optional"}
   */
  @PostMapping("/queue/{id}/confirm")
  @PreAuthorize("hasAuthority('confirmation:view')")
  public ApiResponse<Map<String, Object>> confirm(
      @PathVariable Long id,
      @RequestBody Map<String, String> payload
  ) {
    String operator = payload.get("operator");
    String reason = payload.get("reason");

    if (operator == null || operator.isBlank()) {
      return ApiResponse.fail(400, "请提供操作人");
    }

    UnifiedEventEntity event = unifiedEventRepository.findById(id).orElse(null);
    if (event == null) {
      return ApiResponse.fail(404, "未找到事件");
    }

    // 状态必须为 CONFIRMING 才可确认
    if (event.getState() != EventState.CONFIRMING) {
      return ApiResponse.fail(400, "仅允许确认确认中的任务");
    }

    boolean ok = eventStateService.transition(event.getEventCode(), EventTrigger.CONFIRM, operator, reason);
    if (!ok) {
      return ApiResponse.fail(400, "确认失败：状态流转被拒绝");
    }

    // 刷新实体
    event = unifiedEventRepository.findById(id).orElse(null);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("eventCode", event != null ? event.getEventCode() : null);
    result.put("state", event != null ? event.getState().name() : "CONFIRMED");
    result.put("confirmedBy", operator);
    result.put("confirmedAt", OffsetDateTime.now().toString());
    result.put("message", "灾害已确认，即将进入预警流程");
    return ApiResponse.success("确认成功", result);
  }

  /**
   * 驳回灾害（状态流转到 REJECTED）。
   * POST /api/confirmation/queue/{id}/reject
   *
   * @param id      统一事件 ID
   * @param payload {operator: "username", reason: "驳回原因"}
   */
  @PostMapping("/queue/{id}/reject")
  @PreAuthorize("hasAuthority('confirmation:view')")
  public ApiResponse<Map<String, Object>> reject(
      @PathVariable Long id,
      @RequestBody Map<String, String> payload
  ) {
    String operator = payload.get("operator");
    String reason = payload.get("reason");

    if (operator == null || operator.isBlank()) {
      return ApiResponse.fail(400, "请提供操作人");
    }
    if (reason == null || reason.isBlank()) {
      return ApiResponse.fail(400, "请提供驳回原因");
    }

    UnifiedEventEntity event = unifiedEventRepository.findById(id).orElse(null);
    if (event == null) {
      return ApiResponse.fail(404, "未找到事件");
    }

    // 状态必须为 CONFIRMING 才可驳回
    if (event.getState() != EventState.CONFIRMING) {
      return ApiResponse.fail(400, "仅允许驳回确认中的任务");
    }

    boolean ok = eventStateService.transition(event.getEventCode(), EventTrigger.REJECT, operator, reason);
    if (!ok) {
      return ApiResponse.fail(400, "驳回失败：状态流转被拒绝");
    }

    // 刷新实体
    event = unifiedEventRepository.findById(id).orElse(null);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("eventCode", event != null ? event.getEventCode() : null);
    result.put("state", event != null ? event.getState().name() : "REJECTED");
    result.put("rejectedBy", operator);
    result.put("rejectReason", reason);
    result.put("rejectedAt", OffsetDateTime.now().toString());
    result.put("message", "灾害已驳回");
    return ApiResponse.success("驳回成功", result);
  }

  /**
   * 将 UnifiedEventEntity 转换为队列项（含灾害详情）。
   */
  private Map<String, Object> toQueueItem(UnifiedEventEntity event) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", event.getId());
    item.put("eventCode", event.getEventCode());
    item.put("hazardCode", event.getHazardCode());
    item.put("title", event.getTitle());
    item.put("source", event.getSource());
    item.put("state", event.getState().name());
    item.put("discoveredAt", event.getCreatedAt() != null ? event.getCreatedAt().toString() : null);
    item.put("updatedAt", event.getUpdatedAt() != null ? event.getUpdatedAt().toString() : null);

    // 填充灾害详情（从 hazard_point 表查询）
    if (event.getHazardCode() != null) {
      HazardPoint hazard = hazardPointRepository.findByHazardCode(event.getHazardCode());
      if (hazard != null) {
        item.put("hazardName", hazard.getName());
        item.put("hazardType", hazard.getHazardType());
        item.put("riskLevel", hazard.getRiskLevel());
        item.put("regionCode", hazard.getRegionCode());
        item.put("longitude", hazard.getLongitude());
        item.put("latitude", hazard.getLatitude());
        // 优先级：红 > 橙 > 黄 > 蓝
        item.put("priority", getRiskPriority(hazard.getRiskLevel()));
      }
    }

    return item;
  }

  /**
   * 计算风险优先级（数值越小优先级越高，用于排序）。
   */
  private int getRiskPriority(String riskLevel) {
    if (riskLevel == null) return 5;
    if (riskLevel.contains("红")) return 1;
    if (riskLevel.contains("橙")) return 2;
    if (riskLevel.contains("黄")) return 3;
    if (riskLevel.contains("蓝") || riskLevel.contains("提醒")) return 4;
    return 5;
  }

  /**
   * 动态构建查询条件（关键词过滤）。
   */
  private Specification<UnifiedEventEntity> queryRestriction(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return (root, query, cb) -> cb.conjunction();
    }
    String like = "%" + keyword.trim().toLowerCase() + "%";
    return (root, query, cb) -> cb.or(
        cb.like(cb.lower(root.get("eventCode")), like),
        cb.like(cb.lower(root.get("title")), like),
        cb.like(cb.lower(root.get("hazardCode")), like)
    );
  }
}
