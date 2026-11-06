package cn.disasterguard.system;

import cn.disasterguard.auth.entity.SysUser;
import cn.disasterguard.auth.repository.SysUserRepository;
import cn.disasterguard.audit.entity.OperationLog;
import cn.disasterguard.audit.repository.OperationLogRepository;
import cn.disasterguard.common.api.ApiResponse;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统用户管理（v1.7.0+ 用户管理页面）。
 *
 * <p>前端 dashboardApi.js 依赖：
 * <ul>
 *   <li>{@code GET /api/users} — list with pagination, keyword filter, role filter</li>
 *   <li>{@code POST /api/users} — create user</li>
 *   <li>{@code PUT /api/users/{id}} — update user</li>
 *   <li>{@code DELETE /api/users/{id}} — delete user</li>
 * </ul>
 *
 * <p>数据源：{@code sys_user} 表 via {@link SysUserRepository}.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

  /**
   * 允许的 roleCode 白名单（与 sys_role.role_code 保持一致）。
   *
   * <p>禁止把请求体里的 roleCode 原样写入数据库 —— 否则任意已登录的普通用户
   * 都可以 POST 一个 roleCode=admin 把自己提权为管理员（SecurityConfig 仅校验
   * "已登录"，不校验角色）。注意原默认值为 "VIEWER"，该值并不存在于 sys_role 表，
   * 这里改为真实存在且权限最小的 attendant。
   */
  private static final Set<String> ALLOWED_ROLE_CODES = Set.of(
      "admin", "system_admin", "emergency_commander", "attendant",
      "expert", "ai_engineer", "maintenance"
  );

  private final SysUserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final OperationLogRepository operationLogRepository;

  public UserController(SysUserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        OperationLogRepository operationLogRepository) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.operationLogRepository = operationLogRepository;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('users:view')")
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String role) {
    var all = userRepository.findAll();
    var filtered = all.stream()
        .filter(u -> keyword == null || keyword.isBlank()
            || (u.getUsername() != null && u.getUsername().toLowerCase().contains(keyword.toLowerCase()))
            || (u.getRealName() != null && u.getRealName().toLowerCase().contains(keyword.toLowerCase()))
            || (u.getPhone() != null && u.getPhone().contains(keyword)))
        .filter(u -> role == null || role.isBlank()
            || (u.getRoleCode() != null && u.getRoleCode().equalsIgnoreCase(role)))
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

  /** 创建用户。密码自动 BCrypt 哈希。 */
  @PostMapping
  @PreAuthorize("hasAuthority('users:create')")
  public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
    String username = String.valueOf(body.getOrDefault("username", ""));
    if (username.isBlank()) {
      return ApiResponse.fail(400, "用户名不能为空");
    }
    if (userRepository.existsByUsername(username)) {
      return ApiResponse.fail(409, "用户名已存在");
    }
    String password = String.valueOf(body.getOrDefault("password", "123456"));
    if (password.length() < 6) {
      return ApiResponse.fail(400, "密码至少 6 个字符");
    }

    SysUser user = new SysUser();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setRealName(String.valueOf(body.getOrDefault("realName", username)));
    user.setPhone(String.valueOf(body.getOrDefault("phone", "")));
    String roleCode = String.valueOf(body.getOrDefault("roleCode", "attendant"));
    if (!ALLOWED_ROLE_CODES.contains(roleCode)) {
      return ApiResponse.fail(400, "角色不合法，允许值：" + String.join(" / ", ALLOWED_ROLE_CODES));
    }
    user.setRoleCode(roleCode);
    user.setRegionCode(String.valueOf(body.getOrDefault("regionCode", "")));
    user.setStatus(String.valueOf(body.getOrDefault("status", "启用")));
    user.setCreatedAt(OffsetDateTime.now());
    user.setUpdatedAt(OffsetDateTime.now());

    user = userRepository.save(user);
    Map<String, Object> dto = toDto(user);
    return ApiResponse.success("创建成功", dto);
  }

  /** 更新用户。roleCode 与 password 由服务端再次校验。 */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('users:edit')")
  public ApiResponse<Map<String, Object>> update(
      @PathVariable Long id,
      @RequestBody Map<String, Object> body) {
    SysUser user = userRepository.findById(id).orElse(null);
    if (user == null) {
      return ApiResponse.fail(404, "用户不存在");
    }
    if (body.containsKey("realName")) {
      user.setRealName(String.valueOf(body.get("realName")));
    }
    if (body.containsKey("phone")) {
      user.setPhone(String.valueOf(body.get("phone")));
    }
    if (body.containsKey("roleCode")) {
      String roleCode = String.valueOf(body.get("roleCode"));
      if (!ALLOWED_ROLE_CODES.contains(roleCode)) {
        return ApiResponse.fail(400, "角色不合法，允许值：" + String.join(" / ", ALLOWED_ROLE_CODES));
      }
      user.setRoleCode(roleCode);
    }
    if (body.containsKey("regionCode")) {
      user.setRegionCode(String.valueOf(body.get("regionCode")));
    }
    if (body.containsKey("status")) {
      user.setStatus(String.valueOf(body.get("status")));
    }
    if (body.containsKey("password") && body.get("password") != null) {
      String pwd = String.valueOf(body.get("password"));
      if (!pwd.isBlank()) {
        user.setPasswordHash(passwordEncoder.encode(pwd));
      }
    }
    user.setUpdatedAt(OffsetDateTime.now());
    user = userRepository.save(user);
    return ApiResponse.success("更新成功", toDto(user));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('users:delete')")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    if (!userRepository.existsById(id)) {
      return ApiResponse.fail(404, "用户不存在");
    }
    userRepository.deleteById(id);
    return ApiResponse.success("删除成功", null);
  }

  /**
   * 重置用户密码（POST /api/users/{id}/reset-password）。
   *
   * <p>密码用 BCrypt {@link PasswordEncoder} 编码后落库；操作人从 SecurityContext 取，
   * <b>不信任请求体里的 operator</b>；并显式写一条 {@code operation_log} 审计
   * （AOP 切面还会另写一条通用写操作审计，两者互不冲突）。
   */
  @PostMapping("/{id}/reset-password")
  @PreAuthorize("hasAuthority('users:edit')")
  public ApiResponse<Map<String, Object>> resetPassword(
      @PathVariable Long id,
      @RequestBody(required = false) Map<String, Object> body) {
    SysUser user = userRepository.findById(id).orElse(null);
    if (user == null) {
      return ApiResponse.fail(404, "用户不存在");
    }
    String newPassword = body == null ? null : str(body.get("newPassword"));
    if (isBlank(newPassword) || newPassword.length() < 6) {
      return ApiResponse.fail(400, "新密码至少 6 个字符");
    }

    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setUpdatedAt(OffsetDateTime.now());
    userRepository.save(user);

    String operator = currentOperator();
    writeResetAudit(operator, id, user.getUsername());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("username", user.getUsername());
    data.put("operator", operator);
    data.put("resetAt", OffsetDateTime.now().toString());
    return ApiResponse.success("密码已重置", data);
  }

  /** 显式审计：只记录非敏感摘要，绝不写入明文密码。 */
  private void writeResetAudit(String operator, Long targetUserId, String targetUsername) {
    OperationLog op = new OperationLog();
    op.setOperator(operator);
    op.setModuleName("system");
    op.setActionName("reset-password");
    op.setRequestMethod("POST");
    op.setRequestPath("/api/users/" + targetUserId + "/reset-password");
    op.setRequestBody("{\"targetUserId\":" + targetUserId
        + ",\"targetUsername\":\"" + targetUsername + "\"}");
    op.setResultStatus("SUCCESS");
    op.setCostMs(0L);
    op.setCreatedAt(OffsetDateTime.now());
    try {
      operationLogRepository.save(op);
    } catch (Exception ignored) {
      // 审计写入失败不影响主流程（密码已成功重置）
    }
  }

  private String currentOperator() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
      return auth.getName();
    }
    return "system";
  }

  private String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  private boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private Map<String, Object> toDto(SysUser u) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("id", u.getId());
    dto.put("username", u.getUsername());
    dto.put("realName", u.getRealName());
    dto.put("phone", u.getPhone());
    dto.put("roleCode", u.getRoleCode());
    dto.put("regionCode", u.getRegionCode());
    dto.put("status", u.getStatus());
    dto.put("lastLoginAt", u.getLastLoginAt());
    dto.put("createdAt", u.getCreatedAt());
    dto.put("updatedAt", u.getUpdatedAt());
    return dto;
  }
}
