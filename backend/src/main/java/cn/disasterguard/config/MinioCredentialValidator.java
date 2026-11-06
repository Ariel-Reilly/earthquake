package cn.disasterguard.config;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MinIO 凭证强校验：生产环境禁止使用默认弱口令。
 *
 * <p>当 {@code DEV_USERS_ENABLED=false}（生产模式）时，启动阶段检查
 * {@code MINIO_ACCESS_KEY} / {@code MINIO_SECRET_KEY} 是否匹配已知弱值，
 * 若命中则抛出 {@link IllegalStateException} 导致应用快速失败。
 *
 * <p>开发模式（DEV_USERS_ENABLED=true）仅输出 WARN 不阻断。
 */
@Component
public class MinioCredentialValidator {

  private static final Logger log = LoggerFactory.getLogger(MinioCredentialValidator.class);

  // 用户名（access key）是公开标识，黑名单只覆盖通用默认账号即可。
  // 注意：不要把项目名（如 disasterguard）放进这里——compose 中默认 access key
  // 就是它，会导致生产模式因"用户名命中黑名单"而无法启动。
  private static final Set<String> WEAK_ACCESS_KEYS = Set.of(
      "admin", "root", "user", "test", "minioadmin"
  );

  // 密钥（secret key）才是真正的凭据，黑名单 + 长度双重校验。
  private static final Set<String> WEAK_SECRET_KEYS = Set.of(
      "minioadmin", "minioadmin123", "admin", "password",
      "12345678", "default", "changeme", "disasterguard", "secret"
  );

  // 必须与 application.yml 中的键名完全一致：minio.access-key / minio.secret-key。
  // 此前误写为点号形式（minio.access.key / minio.access.secret），而 @Value 不做
  // 宽松绑定（relaxed binding），导致两者恒为空字符串，进而在 validate() 开头
  // 的 isBlank 分支直接 return —— 弱口令防护从未真正执行过。
  @Value("${minio.access-key:}")
  String accessKey;

  @Value("${minio.secret-key:}")
  String secretKey;

  // 同样修正为 application.yml 中实际存在的 disasterguard.security.dev-users-enabled，
  // 默认值与 application.yml 保持一致（false），避免生产误判为开发模式。
  @Value("${disasterguard.security.dev-users-enabled:false}")
  boolean devMode;

  @PostConstruct
  void validate() {
    if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
      log.warn("MinIO 凭证未配置；附件上传功能将不可用。");
      return;
    }
    boolean weak = WEAK_ACCESS_KEYS.contains(accessKey.trim().toLowerCase())
        || WEAK_SECRET_KEYS.contains(secretKey.trim().toLowerCase())
        || secretKey.length() < 16;
    if (weak) {
      String msg = "MinIO 凭证过弱（access/secret 命中黑名单或 secret 长度 < 16）。"
          + "请设置 MINIO_ACCESS_KEY / MINIO_SECRET_KEY 为强随机值（建议 openssl rand -base64 24）。";
      if (devMode) {
        log.warn("{}  (dev 模式继续启动，prod 将直接失败)", msg);
      } else {
        throw new IllegalStateException(msg);
      }
    } else {
      log.info("MinIO 凭证校验通过 (access={}, secret_len={})", accessKey, secretKey.length());
    }
  }
}
