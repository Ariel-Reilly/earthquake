package cn.disasterguard.config;

import cn.disasterguard.auth.repository.SysUserRepository;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 *   <li>{@code FILE_BASE_URL} 仍为 localhost:9000 或为空</li>
 *   <li>{@code MINIO_ENDPOINT} 为空或指向本机（R4 P0-4 新增）；
 *       若只是内网单标签服务名（如 {@code minio}）则仅告警，因为 presigned URL
 *       会以它作为主机名，浏览器解析不了 —— 属「能上传、打不开附件」</li>
 *   <li>{@code JWT_SECRET} 为空 / 长度 < 32 / 命中弱密码</li>
 *   <li>种子用户密码仍为 {@code admin123}（BCrypt hash 匹配检查）</li>
 *   <li>MINIO secret 强度不足（长度 < 16 或命中常见弱密码）</li>
 *   <li>{@code DATA_ENCRYPTION_KEY} 为空 / 长度 < 32 / 命中弱密码
 *       （注册申请新增：加密身份证号，缺失会导致历史密文永久不可解）</li>
 *   <li>{@code REGISTER_SMS_ENABLED} / {@code REGISTER_EMAIL_ENABLED} 为 true
 *       （短信/邮件网关未接入，置 true 会让注册接口「假装发送成功」）</li>
 *   <li>{@code POSTGRES_PASSWORD} 为空或仍为 application.yml 的开发默认值
 *       （占位符静默回落，应用能启动但连的是已公开的口令）</li>
 *   <li>{@code REDIS_PASSWORD} 命中开发默认值（仅告警）</li>
 * </ul>
 *
 * <p>dev 环境默认不加载此组件（@Profile("prod")），避免本地开发被阻断。
 */
@Component
@Profile("prod")
public class ProductionConfigValidator {

  private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);

  /** BCrypt("admin123") 的 hash 前缀（与 data.sql 种子用户一致）。 */
  private static final String DEFAULT_SEED_PASSWORD_HASH_PREFIX = "$2b$10$sl2";

  private static final Set<String> WEAK_TOKENS = Set.of(
      "password", "password123", "123456", "12345678", "123456789",
      "qwerty", "admin", "admin123", "changeme", "letmein",
      "welcome", "monkey", "dragon", "baseball", "iloveyou"
  );

  /**
   * application.yml 里为「宿主机直跑」保留的本地开发默认值。
   *
   * <p>这些值现在以 {@code ${ENV_VAR:default}} 的形式写在 yml 中，好处是本地无需任何
   * 配置即可启动；副作用是 prod 忘记注入环境变量时，占位符会**静默回落**到它们。
   * 而这些值已随代码进入 git 历史，等同于公开密钥。
   *
   * <p>长度与弱口令黑名单都拦不住它们（例如 JWT 默认值有 60 余位、不含任何弱词），
   * 所以必须按「已知泄露值」精确匹配并阻断启动。
   */
  private static final Set<String> DEV_DEFAULT_SECRETS = Set.of(
      "this_is_a_very_secure_secret_key_for_disaster_guard_2026_!@#",
      "a_very_secure_data_encryption_key_2026_!@#",
      "CHANGE_ME__dev_postgres_password_2026",
      "CHANGE_ME__dev_redis_password_2026",
      "CHANGE_ME__dev_minio_password_24chars_minimum_2026"
  );

  @Value("${disasterguard.security.dev-users-enabled:true}")
  boolean devUsersEnabled;

  @Value("${disasterguard.device.auth-strict-mode:false}")
  boolean deviceAuthStrict;

  @Value("${disasterguard.ws.require-ticket:false}")
  boolean wsRequireTicket;

  @Value("${disasterguard.frontend-origin:http://localhost:5173}")
  String frontendOrigin;

  @Value("${disasterguard.file-base-url:http://localhost:9000}")
  String fileBaseUrl;

  @Value("${disasterguard.jwt.secret:}")
  String jwtSecret;

  /**
   * 注册申请的敏感数据加密密钥（AES-256-GCM）。
   *
   * <p>身份证号明文不落库，密文存 sys_user_application.id_card_enc。密钥一旦变更，
   * 历史申请单的密文将永久无法解密 —— 因此 prod 下必须由环境变量注入且不得使用默认值。
   */
  @Value("${disasterguard.security.data-encryption-key:}")
  String dataEncryptionKey;

  /** 注册短信验证码是否启用（关 = 未接入网关，prod 下验证码接口直接 50301）。 */
  @Value("${disasterguard.register.sms-enabled:false}")
  boolean registerSmsEnabled;

  /** 注册邮箱验证码是否启用。 */
  @Value("${disasterguard.register.email-enabled:false}")
  boolean registerEmailEnabled;

  @Value("${emqx.enabled:true}")
  boolean emqxEnabled;

  @Value("${emqx.username:}")
  String emqxUsername;

  @Value("${emqx.password:}")
  String emqxPassword;

  // 与 MinioCredentialValidator 同样修正为 application.yml 中的连字符键名，
  // 否则 @Value 取不到值（不做宽松绑定），生产校验形同虚设。
  @Value("${minio.access-key:}")
  String minioAccessKey;

  @Value("${minio.secret-key:}")
  String minioSecret;

  /** R4 P0-4：附件预签名 URL 的主机就是它，浏览器要直接访问，此前完全没有校验。 */
  @Value("${minio.endpoint:}")
  String minioEndpoint;

  /**
   * 数据源口令：application.yml 中为本地开发保留了默认值，prod 忘记注入 POSTGRES_PASSWORD
   * 时占位符会静默回落到它。此前本类完全没有校验数据库口令。
   */
  @Value("${spring.datasource.password:}")
  String datasourcePassword;

  /** Redis 口令，同上：回落默认值意味着生产缓存/会话可被匿名读写。 */
  @Value("${spring.data.redis.password:}")
  String redisPassword;

  @Value("${disasterguard.sms.enabled:false}")
  boolean smsEnabled;

  @Value("${disasterguard.sms.access-key-id:}")
  String smsAccessKeyId;

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
        if (pwd.equals("change_me_emqx_password") || pwd.equals("disasterguard_backend")
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
      if (isDevDefaultSecret(secret)) {
        errors.add("MINIO_SECRET_KEY 仍是 application.yml 里的本地开发默认值（已随代码进入 git 历史）。"
            + "请通过 MINIO_ROOT_PASSWORD / MINIO_SECRET_KEY 注入强随机值。");
      }
    }

    // 7) JWT secret 强度
    if (isBlank(jwtSecret)) {
      errors.add("JWT_SECRET 为空！prod 下必须设置 ≥ 32 字符的强随机密钥。");
    } else if (jwtSecret.trim().length() < 32) {
      errors.add("JWT_SECRET 长度 < 32（当前 " + jwtSecret.trim().length() + "）。请设置 ≥ 32 字符的强随机值。");
    } else if (WEAK_TOKENS.contains(jwtSecret.trim().toLowerCase())) {
      errors.add("JWT_SECRET 命中弱密码模式（当前='" + jwtSecret.trim() + "'）。请设置强随机值。");
    } else if (isDevDefaultSecret(jwtSecret)) {
      errors.add("JWT_SECRET 仍是 application.yml 里的本地开发默认值（已随代码进入 git 历史，"
          + "等同于公开密钥）。请通过环境变量注入新的强随机值：openssl rand -base64 48");
    }

    // 8) FILE_BASE_URL（附件下载基础地址）
    if (isBlank(fileBaseUrl) || fileBaseUrl.contains("localhost:9000")) {
      errors.add("FILE_BASE_URL 在 prod 下必须设置为真实 MinIO 访问地址（当前='"
          + fileBaseUrl + "'）。附件下载链接将使用此值拼接。");
    }

    // 9) MINIO_ENDPOINT（R4 P0-4 新增）
    //    这个值此前没有任何校验，而它同时决定两件事：
    //      a. 后端 → MinIO 的上传链路（必须容器内可达）
    //      b. AttachmentService 生成的 **presigned URL 主机名**（必须浏览器可达）
    //    最危险的是「沿用 dev 的 .env」——compose 的 ${MINIO_ENDPOINT:?} 会被
    //    .env 里那个 127.0.0.1:19000 满足，于是原样注入 prod、悄悄失效。
    if (isBlank(minioEndpoint)) {
      errors.add("MINIO_ENDPOINT 为空！prod 下必须设置为客户端可达的 MinIO 地址（如 https://minio.example.com）。");
    } else {
      String ep = minioEndpoint.trim().toLowerCase();
      if (ep.contains("localhost") || ep.contains("127.0.0.1") || ep.contains("0.0.0.0")) {
        errors.add("MINIO_ENDPOINT 指向本机（当前='" + minioEndpoint + "'）。"
            + "在容器里 localhost 指向容器自己，上传必然连接失败；且该地址会被写进附件"
            + "预签名 URL，客户端同样访问不到。请改为客户端可达的公开地址。");
      } else if (hostHasNoDot(ep)) {
        log.warn("MINIO_ENDPOINT='{}' 看起来是容器网络内的单标签服务名。"
            + "后端上传可用，但 AttachmentService 生成的 presigned URL 会以它作为主机名，"
            + "浏览器无法解析 —— 表现为「能上传、点开附件打不开」。"
            + "若已通过反向代理把它暴露给客户端，可忽略本告警。", minioEndpoint);
      }
    }

    // 10) 种子用户密码检查（仅在 DB 中已有用户时触发，空 DB 为首次部署场景，跳过）
    checkSeedUserPasswords(errors);

    // 11) DATA_ENCRYPTION_KEY（注册申请新增）
    //     身份证号密文（sys_user_application.id_card_enc）靠它解密。留空时
    //     SensitiveDataCipher 会生成一次性随机密钥，表现为「申请单里的身份证
    //     重启后永远解不开」，且不会有任何报错 —— 必须在这里 fail-fast。
    if (isBlank(dataEncryptionKey)) {
      errors.add("DATA_ENCRYPTION_KEY 为空！prod 下必须设置用于加密身份证号的对称密钥"
          + "（生成：openssl rand -base64 32）。留空会导致历史申请单的身份证密文永久无法解密。");
    } else if (dataEncryptionKey.trim().length() < 32) {
      errors.add("DATA_ENCRYPTION_KEY 长度 < 32（当前 " + dataEncryptionKey.trim().length()
          + "）。请设置长度 >= 32 的强随机值。");
    } else if (WEAK_TOKENS.contains(dataEncryptionKey.trim().toLowerCase())) {
      errors.add("DATA_ENCRYPTION_KEY 命中弱密码模式（当前='" + dataEncryptionKey + "'）。请设置强随机值。");
    } else if (isDevDefaultSecret(dataEncryptionKey)) {
      errors.add("DATA_ENCRYPTION_KEY 仍是 application.yml 里的本地开发默认值（已随代码进入 git 历史）。"
          + "该密钥用于加密身份证号，请通过环境变量注入新的强随机值：openssl rand -base64 32");
    }

    // 12) 注册验证码渠道开关
    //     开关置 true 表示「已接入短信/邮件网关」。当前项目 SmsSender / EmailSender
    //     仍是 Mock 实现（仅打日志），若在 prod 打开开关，注册接口会对所有人返回
    //     「验证码已发送」但用户永远收不到 —— 与未接入网关等价，却伪装成可用，
    //     因此这里直接阻断，要求先落地真实网关。
    if (registerSmsEnabled || registerEmailEnabled) {
      errors.add("REGISTER_SMS_ENABLED / REGISTER_EMAIL_ENABLED 当前不允许在 prod 置为 true："
          + "短信/邮件网关尚未接入（SmsSender / EmailSender 仍为 Mock 实现）。"
          + "置 true 会让注册接口返回「验证码已发送」但用户收不到，属于伪装成可用的静默失败。"
          + "请先落地真实网关后再开启，否则请保持两者为 false（关闭时 prod 明确返回 50301）。");
    }

    // 13) 数据库 / Redis 口令
    //     application.yml 现在为本地开发保留了默认口令，prod 忘记注入时占位符会静默回落：
    //     应用照常启动，连的却是一个「已经写进 git 历史」的密码。此前完全没有这项校验。
    if (isBlank(datasourcePassword)) {
      errors.add("POSTGRES_PASSWORD 为空！prod 下必须显式注入数据库口令。");
    } else if (isDevDefaultSecret(datasourcePassword)) {
      errors.add("POSTGRES_PASSWORD 仍是 application.yml 里的本地开发默认值"
          + "（已随代码进入 git 历史，等同于公开口令）。请通过环境变量注入强随机值。");
    }

    if (isBlank(redisPassword)) {
      log.warn("REDIS_PASSWORD 为空：prod 下 Redis 将允许匿名访问。"
          + "请确认已通过网络隔离保护，或补上强随机口令。");
    } else if (isDevDefaultSecret(redisPassword)) {
      log.warn("REDIS_PASSWORD 仍是 application.yml 里的本地开发默认值（已进入 git 历史），请改为强随机值。");
    }

    // 14) 阿里云短信凭证
    //     只告警不阻断：占位值不会泄露任何密钥，属于「功能不可用」而非「安全问题」；
    //     但置 CHANGE_ME 会让验证码登录静默失败（接口返回「已发送」而用户收不到），
    //     因此在启动日志里显式提示。
    if (smsEnabled && isDevDefaultSecret(smsAccessKeyId)) {
      log.warn("ALIYUN_SMS_ACCESS_KEY_ID 未注入真实凭证（当前='{}'）：短信验证码发送将失败。"
          + "若不使用短信登录，请将 ALIYUN_SMS_ENABLED 设为 false。", smsAccessKeyId);
    }

    if (!errors.isEmpty()) {
      String msg = "prod 配置校验失败（" + errors.size() + " 项）：\n  - " + String.join("\n  - ", errors)
          + "\n请修复环境变量后重启。";
      log.error(msg);
      throw new IllegalStateException(msg);
    }

    log.info("prod 配置校验通过 ✓");
  }

  /**
   * 扫描 sys_user 表中启用账号，若密码 hash 仍为种子值（对应明文 admin123），
   * 追加到错误列表（阻断启动）。这与其他 6 项 fail-fast 策略保持一致——
   * 种子用户密码为已知默认值，攻击者可直接登录，属于高风险配置。
   *
   * <p>若 sys_user 表为空（全新空 DB、未执行 data.sql），则跳过此检查，
   * 由运维自行通过 API 创建管理员。这是首次部署唯一允许的旁路。
   */
  private void checkSeedUserPasswords(List<String> errors) {
    try {
      var users = userRepository.findAll();
      if (users.isEmpty()) {
        log.info("prod 校验：sys_user 表为空（全新部署），跳过种子密码检查，请创建管理员后立即设置强密码");
        return;
      }
      List<String> offenders = new ArrayList<>();
      for (var u : users) {
        String hash = u.getPasswordHash();
        if (hash != null && hash.startsWith(DEFAULT_SEED_PASSWORD_HASH_PREFIX)) {
          offenders.add(u.getUsername());
        }
      }
      if (!offenders.isEmpty()) {
        errors.add("种子用户密码仍为默认值 'admin123'：" + String.join(", ", offenders)
            + "。请在启动 backend 前修改："
            + "docker exec -i disasterguard-postgres psql -U disasterguard -d disasterguard -c "
            + "\"UPDATE sys_user SET password_hash='<新BCrypt哈希>' WHERE username IN (...);\""
            + "（hash 用 htpasswd -nbBC 10 '' '<新密码>' 生成）。"
            + "或删除 data.sql 中的种子注入，改为首次部署后通过 API 创建管理员。");
      }
    } catch (Exception ex) {
      // DB 未就绪时跳过（schema.sql 还没跑完）
      log.warn("prod 校验：sys_user 表不可访问，跳过种子密码检查 — {}", ex.getMessage());
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * 判断某个密钥是否命中了 application.yml 里的本地开发默认值。
   *
   * <p>同时覆盖 {@code CHANGE_ME} 前缀：yml 中所有占位符的兜底值都以它开头，
   * 便于一眼分辨「配置了」与「没配置」。
   *
   * @param value 待校验的配置值，可为 null
   * @return true 表示该值是 yml 内置的开发默认值（prod 下不可接受）
   */
  private static boolean isDevDefaultSecret(String value) {
    if (value == null) {
      return false;
    }
    String v = value.trim();
    if (v.isEmpty()) {
      return false;
    }
    return DEV_DEFAULT_SECRETS.contains(v) || v.startsWith("CHANGE_ME");
  }

  /**
   * 判断 endpoint 的 host 部分是否为「单标签」名（不含点），例如 {@code minio}。
   *
   * <p>用于区分「容器网络内的服务名」与「可被外部解析的域名」。
   * 只做提示性判断，不阻断启动 —— 反向代理场景下服务名也可能被外部正确解析。
   */
  private static boolean hostHasNoDot(String endpoint) {
    String s = endpoint;
    int schemeIdx = s.indexOf("://");
    if (schemeIdx >= 0) {
      s = s.substring(schemeIdx + 3);
    }
    int slash = s.indexOf('/');
    if (slash >= 0) {
      s = s.substring(0, slash);
    }
    int colon = s.indexOf(':');
    if (colon >= 0) {
      s = s.substring(0, colon);
    }
    return !s.isBlank() && !s.contains(".");
  }
}
