package cn.disasterguard.audit;

import cn.disasterguard.audit.entity.OperationLog;
import cn.disasterguard.audit.repository.OperationLogRepository;
import cn.disasterguard.common.api.ApiResponse;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 操作审计日志（v1.7.0+ 审计日志页面）。
 *
 * <p>前端 dashboardApi.js 依赖：{@code GET /api/audit/logs}.
 *
 * <p>数据源：{@code operation_log} 表 via {@link OperationLogRepository}.
 * 支持分页、关键字过滤（搜索 operator / moduleName / actionName / requestPath）、
 * 时间范围过滤（range=24h/7/30）与动作分组过滤（action=WARNING/HAZARD/...）。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditLogController {

  private final OperationLogRepository logRepository;

  public AuditLogController(OperationLogRepository logRepository) {
    this.logRepository = logRepository;
  }

  /** 查询操作日志（分页 + 关键字 + 时间范围 + 动作分组过滤）。 */
  @GetMapping("/logs")
  @PreAuthorize("hasAuthority('audit:view')")
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String range,
      @RequestParam(required = false) String action) {
    // range：前端审计页的"最近 24 小时 / 7 天 / 30 天"，空值表示全部
    OffsetDateTime since = resolveRangeStart(range);
    var all = logRepository.findAll();
    var filtered = all.stream()
        .filter(log -> since == null || (log.getCreatedAt() != null && !log.getCreatedAt().isBefore(since)))
        .filter(log -> matchesAction(log, action))
        .filter(log -> keyword == null || keyword.isBlank()
            || contains(log.getOperator(), keyword)
            || contains(log.getModuleName(), keyword)
            || contains(log.getActionName(), keyword)
            || contains(log.getRequestPath(), keyword)
            || contains(log.getResultStatus(), keyword))
        .sorted((a, b) -> {
          var ta = a.getCreatedAt() == null ? OffsetDateTime.MIN : a.getCreatedAt();
          var tb = b.getCreatedAt() == null ? OffsetDateTime.MIN : b.getCreatedAt();
          return tb.compareTo(ta);
        })
        .toList();

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);
    int fromIndex = Math.min((safePage - 1) * safeSize, filtered.size());
    int toIndex = Math.min(fromIndex + safeSize, filtered.size());
    List<Map<String, Object>> items = filtered.subList(fromIndex, toIndex).stream()
        .map(this::toDto)
        .collect(Collectors.toList());

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("total", filtered.size());
    result.put("page", safePage);
    result.put("size", safeSize);
    result.put("pages", Math.max(1, (int) Math.ceil(filtered.size() / (double) safeSize)));
    return ApiResponse.success(result);
  }

  private boolean contains(String field, String keyword) {
    if (field == null) return false;
    return field.toLowerCase().contains(keyword.toLowerCase());
  }

  /** 时间范围（24h / 7 / 30，空值=全部）→ 过滤起始时间；无法识别时返回 null（不过滤）。 */
  private OffsetDateTime resolveRangeStart(String range) {
    if (range == null || range.isBlank()) {
      return null;
    }
    long hours = switch (range.trim().toLowerCase()) {
      case "24h" -> 24L;
      case "7", "7d" -> 24L * 7;
      case "30", "30d" -> 24L * 30;
      default -> -1L;
    };
    if (hours <= 0) {
      return null;
    }
    return OffsetDateTime.now().minusHours(hours);
  }

  /**
   * 动作分组过滤。
   *
   * <p>前端审计页的筛选项是业务分组（WARNING / HAZARD / TASK / DEVICE / CONFIRMATION /
   * USER / LOGIN），而 {@code operation_log.module_name} 记录的是 Controller 所在包名
   * （warning / hazard / task / device / ...），{@code action_name} 是方法名。因此分组必须
   * 映射到 module_name 比较，直接按 actionName 等值筛选会永远查不到数据。
   */
  private boolean matchesAction(OperationLog log, String action) {
    if (action == null || action.isBlank()) {
      return true;
    }
    String group = action.trim().toLowerCase();
    String module = log.getModuleName() == null ? "" : log.getModuleName().toLowerCase();
    String actionName = log.getActionName() == null ? "" : log.getActionName().toLowerCase();
    return switch (group) {
      case "warning" -> "warning".equals(module);
      case "hazard" -> "hazard".equals(module);
      case "task" -> "task".equals(module) || "inspection".equals(module);
      case "device" -> "device".equals(module) || "ota".equals(module);
      case "confirmation" -> "confirmation".equals(module);
      case "user" -> "system".equals(module) || "auth".equals(module);
      case "login" -> "auth".equals(module)
          && (actionName.contains("login") || actionName.contains("logout"));
      default -> module.equals(group) || actionName.contains(group);
    };
  }

  private Map<String, Object> toDto(OperationLog log) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("id", log.getId());
    dto.put("operator", log.getOperator());
    dto.put("moduleName", log.getModuleName());
    dto.put("actionName", log.getActionName());
    dto.put("requestMethod", log.getRequestMethod());
    dto.put("requestPath", log.getRequestPath());
    dto.put("resultStatus", log.getResultStatus());
    dto.put("costMs", log.getCostMs());
    dto.put("ipAddress", log.getIpAddress());
    dto.put("createdAt", log.getCreatedAt());
    return dto;
  }
}
