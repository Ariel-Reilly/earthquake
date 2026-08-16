package cn.geosentry.auth.security;

import cn.geosentry.auth.entity.SysUser;
import cn.geosentry.auth.repository.SysUserRepository;
import cn.geosentry.common.security.PermissionRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 数据库用户详情服务（优先级：DB > in-memory fallback）。
 *
 * <p>从 {@code sys_user} 表加载认证主体，密码哈希由 BCrypt 校验。
 * 当 {@code geosentry.security.dev-users-enabled=true} 且 DB 未查到用户时，
 * 回退到内存中硬编码的三个开发账号（admin/attendant/maintenance / 123456），
 * 保证无 DB 场景也能演示。
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

  private final SysUserRepository userRepository;
  private final boolean devUsersEnabled;

  public DbUserDetailsService(SysUserRepository userRepository,
                              @Value("${geosentry.security.dev-users-enabled:true}") boolean devUsersEnabled) {
    this.userRepository = userRepository;
    this.devUsersEnabled = devUsersEnabled;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    SysUser user = userRepository.findByUsername(username);
    if (user != null) {
      if (!"启用".equals(user.getStatus())) {
        throw new UsernameNotFoundException("用户已被禁用: " + username);
      }
      return buildDetails(user.getUsername(), user.getPasswordHash(), user.getRoleCode());
    }
    // DB 未命中 → fallback 到开发账号
    if (devUsersEnabled) {
      String role = guessRole(username);
      if (role != null) {
        // 内存 fallback 不校验密码（由调用方统一校验）
        return buildDetails(username, "{noop}FALLBACK_" + role, role);
      }
    }
    throw new UsernameNotFoundException("用户不存在: " + username);
  }

  private String guessRole(String username) {
    return switch (username.toLowerCase()) {
      case "admin" -> "admin";
      case "attendant" -> "attendant";
      case "maintenance" -> "maintenance";
      default -> null;
    };
  }

  private UserDetails buildDetails(String username, String passwordHash, String roleCode) {
    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode.toUpperCase()));
    Set<String> perms = PermissionRegistry.permissionsOf(roleCode);
    for (String p : perms) {
      authorities.add(new SimpleGrantedAuthority(p));
    }
    return User.withUsername(username)
        .password(passwordHash)
        .authorities(authorities)
        .build();
  }
}
