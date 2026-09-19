package cn.disasterguard.approval;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批中心（v1.7.0+ P2-1）。
 *
 * <p>前端 dashboardApi.listApprovals / getApprovalStats / decideApproval 依赖本端点。
 *
 * <p>当前实现以 warning_event 表作为审批数据源（待确认 / 处理中 视为"待审批"，
 * 已确认 / 已发布 / 已解除 / 已关闭 / 已忽略 视为"已审批"）：
 *
 * <ul>
 *   <li>{@code pending} — 待审批数</li>
 *   <li>{@code approved} — 已通过数</li>
 *   <li>{@code rejected} — 已驳回数</li>
 *   <li>{@code approvalRate} — 通过率（百分比 0-100）</li>
 *   <li>{@code avgDurationMin} — 平均审批耗时（分钟；当前用 closedAt - triggeredAt 简化估算）</li>
 *   <li>{@code trend} — 14 天每日审批量</li>
 * </ul>
 *
 * <p>后续接入独立审批工作流后，替换数据源即可，接口契约不变。
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalCenterController {

  private final WarningEventRepository warningRepository;

  public ApprovalCenterController(WarningEventRepository warningRepository) {
    this.warningRepository = warningRepository;
  }

  /** 审批统计（顶栏红点 + 审批中心顶部 4 个数字 + 14 天每日审批量）。 */
  @GetMapping("/stats")
  public ApiResponse<Map<String, Object>> stats() {
    var all = warningRepository.findAll();
    long pending = all.stream().filter(w -> {
      String s = w.getStatus();
      return "待确认".equals(s) || "处理中".equals(s);
    }).count();
    long approved = all.stream().filter(w -> {
      String s = w.getStatus();
      return "已确认".equals(s) || "已发布".equals(s);
    }).count();
    long rejected = all.stream().filter(w -> "已忽略".equals(w.getStatus())).count();
    long closed = all.stream().filter(w -> "已关闭".equals(w.getStatus()) || "已解除".equals(w.getStatus())).count();
    long decided = approved + rejected + closed;
    double approvalRate = decided == 0 ? 0.0 : Math.round((approved * 10000.0) / decided) / 100.0;
    double avgDurationMin = all.stream()
        .filter(w -> w.getClosedAt() != null && w.getTriggeredAt() != null)
        .mapToLong(w -> java.time.Duration.between(w.getTriggeredAt(), w.getClosedAt()).toMinutes())
        .average()
        .orElse(0.0);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("pending", pending);
    result.put("approved", approved);
    result.put("rejected", rejected);
    result.put("closed", closed);
    result.put("approvalRate", approvalRate);
    result.put("avgDurationMin", Math.round(avgDurationMin * 10) / 10.0);

    // 14 天每日审批量：把 decided 事件按 closedAt 日期分组；空缺日期补 0
    java.time.LocalDate today = java.time.LocalDate.now();
    Map<java.time.LocalDate, Long> perDay = new java.util.HashMap<>();
    all.stream()
        .filter(w -> w.getClosedAt() != null)
        .forEach(w -> {
          java.time.LocalDate d = w.getClosedAt().toLocalDate();
          perDay.merge(d, 1L, Long::sum);
        });
    List<Map<String, Object>> trend = new ArrayList<>();
    for (int i = 13; i >= 0; i--) {
      java.time.LocalDate d = today.minusDays(i);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("day", d.toString());
      row.put("count", perDay.getOrDefault(d, 0L));
      trend.add(row);
    }
    result.put("trend", trend);

    // 饼图分布
    Map<String, Object> pie = new LinkedHashMap<>();
    pie.put("pending", pending);
    pie.put("approved", approved);
    pie.put("rejected", rejected);
    result.put("pie", pie);

    return ApiResponse.success(result);
  }

  /** 审批列表（支持按状态 / 级别 / 关键字过滤）。 */
  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    var all = warningRepository.findAll();
    var filtered = all.stream()
        .filter(w -> status == null || status.isBlank() || status.equals(w.getStatus()))
        .filter(w -> level == null || level.isBlank()
            || (w.getWarningLevel() != null && w.getWarningLevel().contains(level)))
        .filter(w -> {
          if (keyword == null || keyword.isBlank()) return true;
          String k = keyword.toLowerCase();
          String title = w.getTitle() == null ? "" : w.getTitle().toLowerCase();
          String code = w.getWarningCode() == null ? "" : w.getWarningCode().toLowerCase();
          return title.contains(k) || code.contains(k);
        })
        .sorted((a, b) -> {
          var ta = a.getTriggeredAt() == null ? java.time.OffsetDateTime.MIN : a.getTriggeredAt();
          var tb = b.getTriggeredAt() == null ? java.time.OffsetDateTime.MIN : b.getTriggeredAt();
          return tb.compareTo(ta);
        })
        .toList();
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);
    int fromIndex = Math.min((safePage - 1) * safeSize, filtered.size());
    int toIndex = Math.min(fromIndex + safeSize, filtered.size());
    List<Map<String, Object>> items = new ArrayList<>();
    for (var w : filtered.subList(fromIndex, toIndex)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", w.getId());
      row.put("no", w.getWarningCode());
      row.put("category", "warning");
      row.put("title", w.getTitle());
      row.put("applicant", w.getClosedBy() == null ? "—" : w.getClosedBy());
      row.put("level", w.getWarningLevel());
      row.put("submittedAt", w.getTriggeredAt());
      row.put("status", w.getStatus());
      items.add(row);
    }
    Map<String, Object> pageResult = new LinkedHashMap<>();
    pageResult.put("items", items);
    pageResult.put("total", filtered.size());
    pageResult.put("page", safePage);
    pageResult.put("size", safeSize);
    pageResult.put("pages", Math.max(1, (int) Math.ceil(filtered.size() / (double) safeSize)));
    return ApiResponse.success(pageResult);
  }

  /**
   * 审批决策：当前仅做状态流转占位。
   * decision=approve → "已确认"；decision=reject → "已忽略"。
   * 真实工作流会写入独立审批表 + 审计日志，本接口保留作向后兼容骨架。
   */
  @org.springframework.web.bind.annotation.PostMapping("/{id}/decide")
  public ApiResponse<Map<String, Object>> decide(
      @org.springframework.web.bind.annotation.PathVariable Long id,
      @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
    String decision = String.valueOf(body.getOrDefault("decision", "approve"));
    var warning = warningRepository.findById(id).orElse(null);
    if (warning == null) {
      return ApiResponse.fail(404, "未找到预警 id=" + id);
    }
    warning.setStatus("approve".equals(decision) ? "已确认" : "已忽略");
    warning.setClosedAt(java.time.OffsetDateTime.now());
    warning.setClosedBy(String.valueOf(body.getOrDefault("operator", "system")));
    warning.setCloseReason(String.valueOf(body.getOrDefault("comment", "")));
    warningRepository.save(warning);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("decision", decision);
    result.put("status", warning.getStatus());
    return ApiResponse.success(result);
  }
}
