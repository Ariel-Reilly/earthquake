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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;

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
   * 待确认队列列表（含灾害详情，按最近更新时间倒序）。
   * GET /api/confirmation/queue
   *
   * @param page    页码（从 1 开始）
   * @param size    每页条数
   * @param keyword 事件号/标题模糊搜索
   * @param status  状态页签：待确认/已认领/已转派/已确认/已驳回/已关闭/全部；
   *                缺省等同「待确认」（尚未结论的事件）
   */
  @GetMapping("/queue")
  @PreAuthorize("hasAuthority('confirmation:view')")
  public ApiResponse<Map<String, Object>> queue(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status
  ) {
    QueueScope scope = resolveScope(status);
    List<UnifiedEventEntity> candidates = scope.states().stream()
        .flatMap(st -> unifiedEventRepository.findByState(st).stream())
        .toList();

    // 转派记录决定「已认领」还是「已转派」，批量取一次避免 N+1
    Map<String, EventTransfer> latestTransfer = latestTransfersByEvent(candidates);

    final String kw = keyword == null ? "" : keyword.trim().toLowerCase();
    List<UnifiedEventEntity> filtered = candidates.stream()
        .filter(e -> kw.isBlank()
            || (e.getEventCode() != null && e.getEventCode().toLowerCase().contains(kw))
            || (e.getTitle() != null && e.getTitle().toLowerCase().contains(kw))
            || (e.getHazardCode() != null && e.getHazardCode().toLowerCase().contains(kw)))
        .filter(e -> scope.matchesTransfer(latestTransfer.get(e.getEventCode())))
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
        .map(e -> toQueueItem(e, latestTransfer.get(e.getEventCode())))
        .collect(Collectors.toList());

    Map<String, Object> pageResult = new LinkedHashMap<>();
    pageResult.put("items", items);
    pageResult.put("total", total);
    pageResult.put("page", safePage);
    pageResult.put("size", safeSize);
    pageResult.put("pages", Math.max(1, (int) Math.ceil(total / (double) safeSize)));
    return ApiResponse.success(pageResult);
  }

  /**
   * 队列页签的检索范围。
   *
   * @param states         覆盖的事件状态集合
   * @param transferredOnly true=只保留已转派，false=只保留未转派，null=不按转派筛选
   */
  private record QueueScope(Set<EventState> states, Boolean transferredOnly) {
    /**
     * 按转派记录筛选事件。
     *
     * @param transfer 事件的最新转派记录，无转派时为 null
     * @return 是否保留
     */
    boolean matchesTransfer(EventTransfer transfer) {
      if (transferredOnly == null) {
        return true;
      }
      return transferredOnly == (transfer != null);
    }
  }

  /**
   * 把前端页签名解析为检索范围。
   *
   * @param status 页签名，可为空
   * @return 对应的检索范围
   */
  private QueueScope resolveScope(String status) {
    String s = status == null ? "" : status.trim();
    return switch (s) {
      case "已认领" -> new QueueScope(Set.of(EventState.CONFIRMING), Boolean.FALSE);
      case "已转派" -> new QueueScope(Set.of(EventState.CONFIRMING), Boolean.TRUE);
      case "已确认" -> new QueueScope(Set.of(EventState.CONFIRMED), null);
      case "已驳回" -> new QueueScope(Set.of(EventState.REJECTED), null);
      case "已关闭" -> new QueueScope(Set.of(EventState.CLOSED), null);
      case "全部" -> new QueueScope(Set.of(EventState.values()), null);
      // 缺省与「待确认」同义：尚未给出结论的事件
      default -> new QueueScope(Set.of(EventState.DETECTED, EventState.CONFIRMING), null);
    };
  }

  /**
   * 取每个事件最新的一条转派记录。
   *
   * @param events 事件集合
   * @return 事件号 → 最新转派记录（从未转派过的事件不在结果中）
   */
  private Map<String, EventTransfer> latestTransfersByEvent(List<UnifiedEventEntity> events) {
    List<String> codes = events.stream()
        .map(UnifiedEventEntity::getEventCode)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    if (codes.isEmpty()) {
      return Map.of();
    }
    Map<String, EventTransfer> latest = new LinkedHashMap<>();
    for (EventTransfer t : transferRepository.findByEventIdIn(codes)) {
      latest.merge(t.getEventId(), t, (older, newer) ->
          newer.getTransferredAt() != null
              && (older.getTransferredAt() == null
                  || newer.getTransferredAt().isAfter(older.getTransferredAt()))
                  ? newer
                  : older);
    }
    return latest;
  }

  /**
   * 取当前登录用户作为操作人。
   *
   * <p>历史缺陷：claim/confirm/reject 曾直接读请求体 payload.get("operator")，
   * transfer 则读 event.getClosedBy()，等于允许调用方伪造操作人与审批留痕。
   * 四个写端点都有 @PreAuthorize，SecurityContext 必然存在；这里统一取
   * Authentication#getName()（与 HazardController 一致），请求体里的 operator
   * 一律忽略，仅保留该字段以兼容现有前端调用。
   *
   * @return 登录用户名；无有效身份时返回 null，由调用方返回 401
   */
  private String currentOperator() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || auth.getName() == null || auth.getName().isBlank()
        || "anonymousUser".equals(auth.getName())) {
      return null;
    }
    return auth.getName();
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
  @PreAuthorize("hasAuthority('confirmation:claim')")
  public ApiResponse<Map<String, Object>> claim(
      @PathVariable Long id,
      @RequestBody Map<String, String> payload
  ) {
    // 操作人只认 SecurityContext，请求体里的 operator 一律忽略（见 currentOperator）。
    String operator = currentOperator();
    if (operator == null) {
      return ApiResponse.fail(401, "未登录或登录状态已失效");
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
  @PreAuthorize("hasAuthority('confirmation:transfer')")
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

    // 转派人也取当前登录用户；旧实现读 event.getClosedBy()，既可能拿到"system"，
    // 也可能把上一个处理人当成发起人，留痕不可信。
    String fromUser = currentOperator();
    if (fromUser == null) {
      return ApiResponse.fail(401, "未登录或登录状态已失效");
    }

    // 记录转派历史
    EventTransfer transfer = new EventTransfer();
    transfer.setEventId(event.getEventCode());
    transfer.setFromUser(fromUser);
    transfer.setToUser(toUser);
    transfer.setReason(reason);
    transfer.setOperator(fromUser);
    transfer.setTransferredAt(OffsetDateTime.now());
    transferRepository.save(transfer);

    // 事件持有人改为接收人，否则队列里的 assignee 仍指向原持有人，
    // 前端「已转派」页签与"谁该处理"都会指错人。
    event.setClosedBy(toUser);
    event.setUpdatedAt(OffsetDateTime.now());
    unifiedEventRepository.save(event);

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
  @PreAuthorize("hasAuthority('confirmation:confirm')")
  public ApiResponse<Map<String, Object>> confirm(
      @PathVariable Long id,
      @RequestBody Map<String, String> payload
  ) {
    // 操作人一律取 SecurityContext，不信任请求体（请求体里的 operator 字段保留兼容但忽略）。
    String operator = currentOperator();
    String reason = payload.get("reason");

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
  @PreAuthorize("hasAuthority('confirmation:reject')")
  public ApiResponse<Map<String, Object>> reject(
      @PathVariable Long id,
      @RequestBody Map<String, String> payload
  ) {
    // 操作人一律取 SecurityContext，不信任请求体（请求体里的 operator 字段保留兼容但忽略）。
    String operator = currentOperator();
    String reason = payload.get("reason");
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
  private Map<String, Object> toQueueItem(UnifiedEventEntity event, EventTransfer transfer) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", event.getId());
    item.put("eventCode", event.getEventCode());
    item.put("hazardCode", event.getHazardCode());
    item.put("title", event.getTitle());
    item.put("source", event.getSource());
    item.put("state", event.getState().name());
    item.put("discoveredAt", event.getCreatedAt() != null ? event.getCreatedAt().toString() : null);
    item.put("updatedAt", event.getUpdatedAt() != null ? event.getUpdatedAt().toString() : null);
    // 当前持有人：认领/确认/驳回时写入，转派后为接收人
    item.put("assignee", event.getClosedBy());
    // 是否发生过转派：前端据此区分「已认领」与「已转派」
    item.put("transferred", transfer != null);
    item.put("transferredTo", transfer != null ? transfer.getToUser() : null);

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
