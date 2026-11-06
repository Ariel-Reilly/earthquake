package cn.disasterguard.warning;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.export.ExcelExportService;
import cn.disasterguard.common.export.WarningExportRow;
import cn.disasterguard.warning.dto.WarningCreateRequest;
import cn.disasterguard.warning.dto.WarningDto;
import cn.disasterguard.warning.dto.WarningUpdateRequest;
import cn.disasterguard.warning.repository.WarningEventRepository;
import cn.disasterguard.warning.service.WarningService;
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
  private final WarningEventRepository warningRepository;
  private final ExcelExportService excelExportService;
  private final cn.disasterguard.warning.engine.WarningCreateService warningCreateService;

  public WarningController(WarningService warningService,
                           WarningEventRepository warningRepository,
                           ExcelExportService excelExportService,
                           cn.disasterguard.warning.engine.WarningCreateService warningCreateService) {
    this.warningService = warningService;
    this.warningRepository = warningRepository;
    this.excelExportService = excelExportService;
    this.warningCreateService = warningCreateService;
  }

  /**
   * 人工发布预警（对应前端「预警发布向导」）。
   * 等级码与灾害类型由 WarningService#createManual 统一归一化后落库。
   */
  @PostMapping
  @PreAuthorize("hasAuthority('warnings:create')")
  public ApiResponse<WarningDto> create(@Valid @RequestBody WarningCreateRequest request) {
    return ApiResponse.success(warningService.createManual(request));
  }

  @GetMapping
  @PreAuthorize("hasAuthority('warnings:view')")
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

  /** 顶栏红点 / 概览统计：按状态分组计数 + 待发布 / 已派发 / 已关闭聚合。
   *  必须在 /{id} 之上单独声明，避免被 Spring 解析成 id="stats" → 400 Long 转换错误。
   */
  @GetMapping("/stats")
  @PreAuthorize("hasAuthority('warnings:view')")
  public ApiResponse<Map<String, Object>> stats() {
    // 状态集合参考 WarningService.create() 默认 "待确认"，
    // 以及 dispatch/close/dismiss/resolve 后流转出的 "处理中" / "已发布" / "已解除" / "已忽略" / "已关闭" / "已确认"。
    // 等级分布与状态计数是两套口径：countByLevel 按 warning_level（红色预警 / ...），
    // countByStatus 按 status（待确认 / 处理中 / ...）。此前误用等级 Map 去取状态名，
    // 导致 dispatched / closed 恒为 0。
    Map<String, Long> byLevel = new java.util.LinkedHashMap<>();
    for (Object[] row : warningRepository.countByLevel()) {
      byLevel.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
    }
    Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
    for (Object[] row : warningRepository.countByStatus()) {
      byStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
    }
    long pending = warningRepository.countActiveWarnings(
        List.of("已关闭", "已忽略", "已解除"));
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("pending", pending);           // 待我处理（活动态，去掉终态）
    result.put("dispatched", byStatus.getOrDefault("处理中", 0L));
    result.put("closed", byStatus.getOrDefault("已关闭", 0L)
        + byStatus.getOrDefault("已解除", 0L)
        + byStatus.getOrDefault("已忽略", 0L));
    result.put("total", warningRepository.count());
    result.put("byLevel", byLevel);
    result.put("byStatus", byStatus);
    return ApiResponse.success(result);
  }

  @GetMapping("/viewport")
  @PreAuthorize("hasAuthority('warnings:view')")
  public ApiResponse<List<WarningDto>> viewport(
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String zoom
  ) {
    return ApiResponse.success(warningService.listByViewport(bbox, keyword, status));
  }

  @GetMapping("/preview")
  @PreAuthorize("hasAuthority('warnings:view')")
  public ApiResponse<List<WarningDto>> preview() {
    return ApiResponse.success(warningService.preview());
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('warnings:view')")
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

  /**
   * 【调试接口】手动触发一条测试预警，用于联调统一事件关联与 WebSocket 推送。
   *
   * R5：此前该端点在 SecurityConfig 里 permitAll 且无任何方法级鉴权，
   * 任何人都能匿名往预警表写「红色预警」并广播，属于可被利用的假预警注入口。
   * 现在要求登录且具备 warnings:create 权限；生产环境建议由配置整体关闭。
   */
  @PreAuthorize("hasAuthority('warnings:create')")
  @PostMapping("/debug-trigger")
  public ApiResponse<Map<String, Object>> debugTrigger() {
    try {
      // 手动构造测试数据
      String ruleCode = "R004";  // 数据库中真实存在的规则编号
      String hazardCode = "HZ-004";  // 现有隐患点
      String title = "【测试】手动触发预警";
      String level = "红色预警";
      String metricCode = "TEST";
      double triggerValue = 99.9;
      
      // 调用 doPersist 方法，注意参数：
      // ruleCode, hazardCode, title, level, metricCode, triggerValue, source,
      // aiAvailable, fusion, factors
      warningCreateService.doPersist(
          ruleCode,
          hazardCode,
          title,
          level,
          metricCode,
          triggerValue,
          "DEBUG_MANUAL",        // source: 手动调试
          false,                 // aiAvailable: false（跳过AI融合）
          null,                  // fusion: null
          new java.util.HashMap<>()  // factors: 空Map
      );
      
      log.info("【测试接口】手动触发预警成功 ruleCode={}, hazardCode={}", ruleCode, hazardCode);
      
      return ApiResponse.success(Map.of(
          "message", "预警已创建，请查看 WebSocket 推送和统一事件关联状态",
          "ruleCode", ruleCode,
          "hazardCode", hazardCode,
          "title", title,
          "level", level
      ));
    } catch (Exception e) {
      log.error("【测试接口】手动触发预警失败", e);
      return ApiResponse.fail(500, "debug-trigger failed: " + e.getMessage());
    }
  }

  @PostMapping("/export")
  @PreAuthorize("hasAuthority('warnings:export')")
  public void export(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
    excelExportService.export(response, "预警处置导出.xlsx", WarningExportRow.class, warningService.exportRows());
  }
}
