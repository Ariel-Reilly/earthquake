package cn.geosentry.warning;

import cn.geosentry.common.api.ApiResponse;
import cn.geosentry.common.api.PageResponse;
import cn.geosentry.common.export.ExcelExportService;
import cn.geosentry.common.export.WarningExportRow;
import cn.geosentry.warning.dto.WarningDto;
import cn.geosentry.warning.dto.WarningUpdateRequest;
import cn.geosentry.warning.service.WarningService;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/warnings")
public class WarningController {

  private static final Logger log = LoggerFactory.getLogger(WarningController.class);

  private final WarningService warningService;
  private final ExcelExportService excelExportService;

  public WarningController(WarningService warningService, ExcelExportService excelExportService) {
    this.warningService = warningService;
    this.excelExportService = excelExportService;
  }

  @GetMapping
  public ApiResponse<PageResponse<WarningDto>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String zoom
  ) {
    return ApiResponse.success(warningService.page(page, size, keyword, status));
  }

  @GetMapping("/viewport")
  public ApiResponse<List<WarningDto>> viewport(
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String zoom
  ) {
    return ApiResponse.success(warningService.listByViewport(bbox, keyword, status));
  }

  @GetMapping("/preview")
  public ApiResponse<List<WarningDto>> preview() {
    return ApiResponse.success(warningService.preview());
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    return ApiResponse.success(Map.of("item", warningService.getById(id)));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<WarningDto> update(@PathVariable Long id, @Valid @RequestBody WarningUpdateRequest request) {
    return ApiResponse.success(warningService.update(id, request));
  }

  @PostMapping("/{id}/confirm")
  @PreAuthorize("hasAuthority('warnings:confirm')")
  public ApiResponse<WarningDto> confirm(@PathVariable Long id) {
    return ApiResponse.success(warningService.confirm(id));
  }

  @PostMapping("/{id}/dispatch")
  @PreAuthorize("hasAuthority('warnings:dispatch')")
  public ApiResponse<WarningDto> dispatch(@PathVariable Long id) {
    return ApiResponse.success(warningService.dispatch(id));
  }

  @PostMapping("/{id}/close")
  @PreAuthorize("hasAuthority('warnings:close')")
  public ApiResponse<WarningDto> close(@PathVariable Long id, @RequestBody(required = false) Map<String, String> payload) {
    String reason = payload == null ? null : payload.get("reason");
    return ApiResponse.success(warningService.close(id, reason, currentOperator()));
  }

  @PostMapping("/{id}/dismiss")
  @PreAuthorize("hasAuthority('warnings:close')")
  public ApiResponse<WarningDto> dismiss(@PathVariable Long id) {
    return ApiResponse.success(warningService.dismiss(id, currentOperator()));
  }

  @PostMapping("/{id}/resolve")
  @PreAuthorize("hasAuthority('warnings:close')")
  public ApiResponse<WarningDto> resolve(
      @PathVariable Long id,
      @RequestBody(required = false) Map<String, Object> payload) {
    String reason = payload == null ? null : asString(payload.get("reason"));
    String scope = payload == null ? null : asString(payload.get("scope"));
    String scopeArea = payload == null ? null : asString(payload.get("scopeArea"));
    String resolvedBy = payload == null ? null : asString(payload.get("resolvedBy"));
    if (resolvedBy == null) resolvedBy = currentOperator();
    return ApiResponse.success(warningService.resolve(id, reason, scope, scopeArea, resolvedBy));
  }

  /** P0-2：预览解除通知。 */
  @GetMapping("/{id}/resolve/preview")
  @PreAuthorize("hasAuthority('warnings:close')")
  public ApiResponse<Map<String, Object>> previewResolve(@PathVariable Long id) {
    return ApiResponse.success(warningService.previewResolve(id));
  }

  /** P0-2：解除范围。 */
  @GetMapping("/{id}/resolve/scope")
  @PreAuthorize("hasAuthority('warnings:close')")
  public ApiResponse<Map<String, Object>> resolveScope(@PathVariable Long id) {
    return ApiResponse.success(warningService.resolveScope(id));
  }

  private String asString(Object value) {
    if (value == null) return null;
    return String.valueOf(value);
  }

  /** 从 SecurityContext 取当前登录人，取不到时回落为 "system"。 */
  private String currentOperator() {
    try {
      var ctx = org.springframework.security.core.context.SecurityContextHolder.getContext();
      var auth = ctx.getAuthentication();
      if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
        return auth.getName();
      }
    } catch (Exception ignored) {
      log.debug("从 SecurityContext 获取操作员失败（回落为 system）: {}", ignored.getMessage());
    }
    return "system";
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    warningService.delete(id);
    return ApiResponse.success("删除成功", null);
  }

  @PostMapping("/export")
  @PreAuthorize("hasAuthority('warnings:export')")
  public void export(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
    excelExportService.export(response, "预警处置导出.xlsx", WarningExportRow.class, warningService.exportRows());
  }
}
