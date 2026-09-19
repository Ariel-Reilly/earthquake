package cn.disasterguard.system;

import cn.disasterguard.auth.entity.SysUser;
import cn.disasterguard.auth.repository.SysUserRepository;
import cn.disasterguard.common.api.ApiResponse;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

  private final SysUserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public UserController(SysUserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  /** 查询用户列表（分页 + 关键字过滤 + 角色过滤）。 */
  @GetMapping
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
    user.setRoleCode(String.valueOf(body.getOrDefault("roleCode", "VIEWER")));
    user.setRegionCode(String.valueOf(body.getOrDefault("regionCode", "")));
    user.setStatus(String.valueOf(body.getOrDefault("status", "启用")));
    user.setCreatedAt(OffsetDateTime.now());
    user.setUpdatedAt(OffsetDateTime.now());

    user = userRepository.save(user);
    Map<String, Object> dto = toDto(user);
    return ApiResponse.success("创建成功", dto);
  }

  /** 更新用户。 */
  @PutMapping("/{id}")
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
      user.setRoleCode(String.valueOf(body.get("roleCode")));
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

  /** 删除用户。 */
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    if (!userRepository.existsById(id)) {
      return ApiResponse.fail(404, "用户不存在");
    }
    userRepository.deleteById(id);
    return ApiResponse.success("删除成功", null);
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
