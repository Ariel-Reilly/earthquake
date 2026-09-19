package cn.disasterguard.ruleengine;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.ruleengine.dto.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rules")
public class RuleEngineController {

  private final RuleEngineService service;
  private final RuleCompiler compiler;

  public RuleEngineController(RuleEngineService service, RuleCompiler compiler) {
    this.service = service;
    this.compiler = compiler;
  }

  /**
   * 规则列表
   */
  @GetMapping
  public ApiResponse<PageResponse<RuleDto>> list(
      @RequestParam(required = false) String disasterType,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {

    PageResponse<RuleDto> result = service.list(disasterType, level, enabled, page, size);
    return ApiResponse.success(result);
  }

  /**
   * 规则详情
   */
  @GetMapping("/{id}")
  public ApiResponse<RuleDto> detail(@PathVariable Long id) {
    RuleDto result = service.getById(id);
    return ApiResponse.success(result);
  }

  /**
   * 创建规则
   */
  @PostMapping
  public ApiResponse<RuleDto> create(@Valid @RequestBody RuleCreateRequest request) {
    String operator = currentOperator();
    RuleDto result = service.create(request, operator);
    return ApiResponse.success(result);
  }

  /**
   * 更新规则
   */
  @PutMapping("/{id}")
  public ApiResponse<RuleDto> update(@PathVariable Long id, @Valid @RequestBody RuleCreateRequest request) {
    RuleDto result = service.update(id, request);
    return ApiResponse.success(result);
  }

  /**
   * 删除规则
   */
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    service.delete(id);
    return ApiResponse.success("删除成功", null);
  }

  /**
   * 启用/停用
   */
  @PutMapping("/{id}/toggle")
  public ApiResponse<RuleDto> toggle(@PathVariable Long id) {
    RuleDto result = service.toggle(id);
    return ApiResponse.success(result);
  }

  /**
   * 测试规则
   */
  @PostMapping("/test")
  public ApiResponse<RuleTestResponse> test(
      @RequestBody RuleTestRequest request,
      @RequestParam Map<String, Object> inputData) {

    RuleTestResponse result = service.test(request, inputData);
    return ApiResponse.success(result);
  }

  /**
   * 执行日志
   */
  @GetMapping("/{id}/logs")
  public ApiResponse<PageResponse<RuleExecutionLogDto>> logs(
      @PathVariable Long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {

    PageResponse<RuleExecutionLogDto> result = service.logs(id, page, size);
    return ApiResponse.success(result);
  }

  /**
   * 所有执行日志
   */
  @GetMapping("/logs")
  public ApiResponse<PageResponse<RuleExecutionLogDto>> allLogs(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {

    PageResponse<RuleExecutionLogDto> result = service.logs(null, page, size);
    return ApiResponse.success(result);
  }

  /**
   * 版本回滚
   */
  @PostMapping("/{id}/rollback")
  public ApiResponse<RuleDto> rollback(
      @PathVariable Long id,
      @RequestParam Integer version) {

    RuleDto result = service.rollback(id, version);
    return ApiResponse.success(result);
  }

  /**
   * 字段字典
   */
  @GetMapping("/fields")
  public ApiResponse<List<FieldDictDto>> fields() {
    List<FieldDictDto> result = service.getFieldDicts();
    return ApiResponse.success(result);
  }

  /**
   * 可用操作符
   */
  @GetMapping("/operators")
  public ApiResponse<List<Map<String, String>>> operators() {
    return ApiResponse.success(compiler.getAvailableOperators());
  }

  /**
   * 刷新规则缓存
   */
  @PostMapping("/reload")
  public ApiResponse<Void> reload() {
    service.reloadRules();
    return ApiResponse.success("规则缓存已刷新", null);
  }

  private String currentOperator() {
    try {
      var ctx = org.springframework.security.core.context.SecurityContextHolder.getContext();
      var auth = ctx.getAuthentication();
      if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
        return auth.getName();
      }
    } catch (Exception ignored) {}
    return "system";
  }
}
