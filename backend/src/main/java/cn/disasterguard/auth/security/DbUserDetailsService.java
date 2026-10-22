package cn.disasterguard.auth.security;

import cn.disasterguard.auth.entity.SysUser;
import cn.disasterguard.auth.repository.SysUserRepository;
import cn.disasterguard.common.security.PermissionRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 数据库用户详情服务（优先级：DB > in-memory fallback）。
 *
 * <p>从 {@code sys_user} 表加载认证主体，密码哈希由 BCrypt 校验。
 * 当 {@code disasterguard.security.dev-users-enabled=true} 且 DB 未查到用户时，
 * 回退到内存中硬编码的三个开发账号（admin/attendant/maintenance）。
 *
 * <p>密码说明：内存 fallback 账号的明文密码是 {@link #DEV_PASSWORD_PLAIN}（123456），
 * 与 {@code data.sql} 种子用户的 admin123 <b>刻意不同</b> —— 两者服务于不同场景：
 * 种子密码供 Docker/DB 初始化后的常规登录，fallback 密码仅在「DB 无该用户」时兜底。
 * 生产环境禁止开启本开关（见 {@code ProductionConfigValidator}），因此该弱密码不会外泄到生产。
 *
 * <p>安全说明：fallback 账号的密码使用 BCrypt hash，AuthController 统一校验，
 * 避免"任意密码即可登录"的后门问题。
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

  /**
   * 内存 fallback 开发账号的固定明文密码。
   *
   * <p>刻意不等于 data.sql 种子密码（admin123）：种子密码要出现在文档里供人使用，
   * 而本常量仅服务于「DB 无该用户」的兜底路径，且 prod 禁用该开关，无需对外公开。
   */
  private static final String DEV_PASSWORD_PLAIN = "123456";

  private final SysUserRepository userRepository;
  private final boolean devUsersEnabled;
  /** BCrypt hash of DEV_PASSWORD_PLAIN（"123456"），构造时由 PasswordEncoder 生成，确保正确性。 */
  private final String devPasswordHash;

  public DbUserDetailsService(SysUserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              @Value("${disasterguard.security.dev-users-enabled:false}") boolean devUsersEnabled) {
    this.userRepository = userRepository;
    this.devUsersEnabled = devUsersEnabled;
    this.devPasswordHash = passwordEncoder.encode(DEV_PASSWORD_PLAIN);
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    SysUser user = userRepository.findByUsername(username).orElse(null);
    if (user != null) {
      if (!"启用".equals(user.getStatus())) {
        throw new UsernameNotFoundException("用户已被禁用: " + username);
      }
      return buildDetails(user.getUsername(), user.getPasswordHash(), user.getRoleCode());
    }
    // DB 未命中 → fallback 到开发账号（dev-users-enabled 默认 false，生产需显式开启）
    if (devUsersEnabled) {
      String role = guessRole(username);
      if (role != null) {
        // 使用 BCrypt hash，AuthController 会统一校验密码是否匹配 DEV_PASSWORD_PLAIN（123456）
        return buildDetails(username, devPasswordHash, role);
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
