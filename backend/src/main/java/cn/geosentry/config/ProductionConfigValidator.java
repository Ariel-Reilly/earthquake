package cn.geosentry.config;

import cn.geosentry.auth.repository.SysUserRepository;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境配置强校验：prod profile 启动时对所有安全敏感配置做 fail-fast 检查。
 *
 * <p>校验项（命中任一即阻断启动）：
 * <ul>
 *   <li>{@code DEV_USERS_ENABLED=true} —— 禁止硬编码账号 fallback</li>
 *   <li>{@code DEVICE_AUTH_STRICT=false} —— 允许匿名设备上报会污染数据</li>
 *   <li>{@code WS_REQUIRE_TICKET=false} —— raw JWT 暴露在 URL 中</li>
 *   <li>{@code EMQX_ENABLED=true} 但 username/password 为空或匹配默认值</li>
 *   <li>{@code FRONTEND_ORIGIN} 仍为 localhost 通配，或为 {@code *}</li>
 *   <li>种子用户密码仍为 {@code 123456}（BCrypt hash 匹配检查）</li>
 *   <li>MINIO secret 强度不足（长度 < 16 或命中常见弱密码）</li>
 * </ul>
 *
 * <p>dev 环境默认不加载此组件（@Profile("prod")），避免本地开发被阻断。
 */
@Component
@Profile("prod")
public class ProductionConfigValidator {

  private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);

  /** BCrypt("123456") 的 hash（与 data.sql 种子用户一致）。 */
  private static final String DEFAULT_SEED_PASSWORD_HASH_PREFIX = "$2b$10$e7J";

  private static final Set<String> WEAK_TOKENS = Set.of(
      "password", "password123", "123456", "12345678", "123456789",
      "qwerty", "admin", "admin123", "changeme", "letmein",
      "welcome", "monkey", "dragon", "baseball", "iloveyou"
  );

  private static final Pattern TOO_SHORT = Pattern.compile(".{1,15}");

  @Value("${geosentry.security.dev-users-enabled:true}")
  boolean devUsersEnabled;

  @Value("${geosentry.device.auth-strict-mode:false}")
  boolean deviceAuthStrict;

  @Value("${geosentry.ws.require-ticket:false}")
  boolean wsRequireTicket;

  @Value("${geosentry.frontend-origin:http://localhost:5173}")
  String frontendOrigin;

  @Value("${emqx.enabled:true}")
  boolean emqxEnabled;

  @Value("${emqx.username:}")
  String emqxUsername;

  @Value("${emqx.password:}")
  String emqxPassword;

  @Value("${minio.access.key:}")
  String minioAccessKey;

  @Value("${minio.access.secret:}")
  String minioSecret;

  private final SysUserRepository userRepository;

  public ProductionConfigValidator(SysUserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @PostConstruct
  void validate() {
    List<String> errors = new ArrayList<>();

    // 1) DEV_USERS_ENABLED
    if (devUsersEnabled) {
      errors.add("DEV_USERS_ENABLED=true 在 prod 下禁止（会启用硬编码账号 fallback）。请设为 false。");
    }

    // 2) DEVICE_AUTH_STRICT
    if (!deviceAuthStrict) {
      errors.add("DEVICE_AUTH_STRICT=false 在 prod 下禁止（允许匿名设备上报）。请设为 true。");
    }

    // 3) WS_REQUIRE_TICKET
    if (!wsRequireTicket) {
      errors.add("WS_REQUIRE_TICKET=false 在 prod 下禁止（raw JWT 会暴露在 WebSocket URL 中）。请设为 true。");
    }

    // 4) EMQX 凭据
    if (emqxEnabled) {
      if (isBlank(emqxUsername) || isBlank(emqxPassword)) {
        errors.add("EMQX_ENABLED=true 但 EMQX_USERNAME / EMQX_PASSWORD 为空。"
            + "请设置非空凭据，或关闭 EMQX_ENABLED。");
      } else {
        String pwd = emqxPassword.trim().toLowerCase();
        if (pwd.equals("change_me_emqx_password") || pwd.equals("geosentry_backend")
            || WEAK_TOKENS.contains(pwd)) {
          errors.add("EMQX_PASSWORD 命中默认/弱密码（当前值='" + emqxPassword + "'）。请设置强随机值。");
        }
      }
    }

    // 5) FRONTEND_ORIGIN
    if (frontendOrigin == null || frontendOrigin.isBlank()
        || frontendOrigin.contains("localhost")
        || frontendOrigin.trim().equals("*")) {
      errors.add("FRONTEND_ORIGIN 在 prod 下必须是可信前端域名（不能是 localhost 或 *）。当前='"
          + frontendOrigin + "'");
    }

    // 6) MinIO secret 强度（MinioCredentialValidator 已检查默认值，这里补充长度 & 常见弱密码）
    if (!isBlank(minioSecret)) {
      String secret = minioSecret.trim();
      if (secret.length() < 16) {
        errors.add("MINIO_SECRET_KEY 长度 < 16（当前 " + secret.length() + "）。请设置长度 ≥ 24 的强随机值。");
      }
      if (WEAK_TOKENS.contains(secret.toLowerCase()) || secret.toLowerCase().contains("minioadmin")) {
        errors.add("MINIO_SECRET_KEY 命中弱密码模式（当前='" + minioSecret + "'）。请设置强随机值。");
      }
    }

    // 7) 种子用户密码检查（仅在 DB 中有种子用户时）
    checkSeedUserPasswords(errors);

    if (!errors.isEmpty()) {
      String msg = "prod 配置校验失败（" + errors.size() + " 项）：\n  - " + String.join("\n  - ", errors)
          + "\n请修复环境变量后重启。";
      log.error(msg);
      throw new IllegalStateException(msg);
    }

    log.info("prod 配置校验通过 ✓");
  }

  /**
   * 扫描 sys_user 表中所有启用账号，如果密码 hash 仍是种子值（对应明文 123456），
   * 追加到错误列表。
   */
  private void checkSeedUserPasswords(List<String> errors) {
    try {
      var users = userRepository.findAll();
      List<String> offenders = new ArrayList<>();
      for (var u : users) {
        String hash = u.getPasswordHash();
        if (hash != null && hash.startsWith(DEFAULT_SEED_PASSWORD_HASH_PREFIX)) {
          offenders.add(u.getUsername());
        }
      }
      if (!offenders.isEmpty()) {
        errors.add("种子用户密码仍为默认值 '123456'：" + String.join(", ", offenders)
            + "。请登录后立即执行 POST /api/auth/change-password 修改，或在 data.sql 中更换 BCrypt hash。");
      }
    } catch (Exception ex) {
      // DB 未就绪时跳过（schema.sql 还没跑完）
      log.warn("prod 校验：sys_user 表不可访问，跳过种子密码检查 — {}", ex.getMessage());
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
