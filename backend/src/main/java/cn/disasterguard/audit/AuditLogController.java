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
 * 支持分页、关键字过滤（搜索 operator / moduleName / actionName / requestPath）。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditLogController {

  private final OperationLogRepository logRepository;

  public AuditLogController(OperationLogRepository logRepository) {
    this.logRepository = logRepository;
  }

  /** 查询操作日志（分页 + 关键字过滤）。 */
  @GetMapping("/logs")
  @PreAuthorize("hasAuthority('audit:view')")
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String keyword) {
    var all = logRepository.findAll();
    var filtered = all.stream()
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
