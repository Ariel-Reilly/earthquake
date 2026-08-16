package cn.geosentry.config;

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

  private static final Set<String> WEAK_KEYS = Set.of(
      "minioadmin", "minioadmin123", "admin", "password",
      "12345678", "default", "changeme", "geosentry"
  );

  @Value("${minio.access.key:}")
  String accessKey;

  @Value("${minio.access.secret:}")
  String secretKey;

  @Value("${geosentry.dev-users-enabled:true}")
  boolean devMode;

  @PostConstruct
  void validate() {
    if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
      log.warn("MinIO 凭证未配置；附件上传功能将不可用。");
      return;
    }
    boolean weak = WEAK_KEYS.contains(accessKey.trim().toLowerCase())
        || WEAK_KEYS.contains(secretKey.trim().toLowerCase())
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
