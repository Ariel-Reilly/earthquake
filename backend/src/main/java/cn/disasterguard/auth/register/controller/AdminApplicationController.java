package cn.disasterguard.auth.register.controller;

import cn.disasterguard.auth.register.dto.ApplicationQuery;
import cn.disasterguard.auth.register.dto.ApplicationView;
import cn.disasterguard.auth.register.dto.RejectRequest;
import cn.disasterguard.auth.register.service.RegisterService;
import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册申请管理端接口（仅 admin 角色）。
 *
 * <p>权限说明：使用 {@code hasRole('ADMIN')}。{@code JwtAuthenticationFilter} 会把
 * JWT 中的角色映射为 {@code ROLE_<ROLE>} 权限码，admin 角色天然具备该权限，
 * 因此无需改动 {@code PermissionRegistry}（该文件由脚本从前端定义生成，
 * 增删权限码会被下次同步覆盖）。
 */
@RestController
@RequestMapping("/api/admin/applications")
public class AdminApplicationController {

  private final RegisterService registerService;

  public AdminApplicationController(RegisterService registerService) {
    this.registerService = registerService;
  }

  /** 分页查询注册申请。 */
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<PageResponse<ApplicationView>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(registerService.page(new ApplicationQuery(status, keyword, page, size)));
  }

  /** 审核通过：创建系统用户。 */
  @PostMapping("/{id}/approve")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Void> approve(@PathVariable Long id) {
    registerService.approve(id, currentUsername());
    return ApiResponse.success("审核通过", null);
  }

  /** 审核拒绝。 */
  @PostMapping("/{id}/reject")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Void> reject(@PathVariable Long id, @Valid @RequestBody RejectRequest request) {
    registerService.reject(id, request.reason(), currentUsername());
    return ApiResponse.success("已拒绝该申请", null);
  }

  /** 取当前登录用户名作为审核人。 */
  private String currentUsername() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getName() == null) {
      return "admin";
    }
    return authentication.getName();
  }
}
