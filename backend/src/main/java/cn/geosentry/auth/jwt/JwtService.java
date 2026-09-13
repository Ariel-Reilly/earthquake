package cn.geosentry.auth.jwt;

import cn.geosentry.common.exception.AppException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.regex.Pattern;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private static final Logger log = LoggerFactory.getLogger(JwtService.class);

  /** 精确黑名单（大小写不敏感）。 */
  private static final java.util.Set<String> EXACT_BLACKLIST = java.util.Set.of(
      "change-me-change-me-change-me-change-me",
      "change-me",
      "geosentry-secret",
      "geosentry-secret-key",
      "default-secret",
      "default-secret-key",
      "secret",
      "secret-key",
      "my-secret",
      "mysecret",
      "password",
      "admin",
      "12345678901234567890123456789012",
      "00000000000000000000000000000000",
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      // docker-compose / 脚手架中常见的占位符
      "replace_me_with_a_strong_secret_at_least_32_chars_long_xxx",
      "replace_me_with_a_strong_secret_at_least_32_chars_long",
      "your-32-char-secret-key-here",
      "please-change-this-secret-key-immediately-123456"
  );

  /** 模式黑名单：纯数字、纯字母、全相同字符（长度 ≥16 时才算弱）。 */
  private static final Pattern PURELY_NUMERIC = Pattern.compile("^\\d{16,}$");
  private static final Pattern PURELY_ALPHA = Pattern.compile("^[a-zA-Z]{16,}$");
  private static final Pattern ALL_SAME_CHAR = Pattern.compile("^(.)\\1{15,}$");

  private final JwtProperties properties;
  private final Key signingKey;

  public JwtService(JwtProperties properties) {
    this.properties = properties;
    this.signingKey = Keys.hmacShaKeyFor(resolveSecret(properties.secret()));
  }

  @PostConstruct
  void validate() {
    String secret = properties.secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "JWT_SECRET 环境变量未设置或为空！生产环境必须设置至少 32 字符的强随机密钥。"
              + " 生成方式：openssl rand -base64 48");
    }
    if (secret.length() < 32) {
      throw new IllegalStateException(
          "JWT_SECRET 必须至少 32 字符（当前长度: " + secret.length()
              + "）。设置 via JWT_SECRET 环境变量。生成方式：openssl rand -base64 48");
    }
    String trimmed = secret.trim().toLowerCase();
    if (EXACT_BLACKLIST.contains(trimmed)) {
      throw new IllegalStateException(
          "JWT_SECRET matches a known weak/default value ('" + secret + "'). "
              + "Generate a strong random secret via: openssl rand -base64 48");
    }
    if (PURELY_NUMERIC.matcher(trimmed).matches()) {
      throw new IllegalStateException("JWT_SECRET must not be purely numeric. Generate with: openssl rand -base64 48");
    }
    if (PURELY_ALPHA.matcher(trimmed).matches()) {
      throw new IllegalStateException("JWT_SECRET must not be purely alphabetic. Generate with: openssl rand -base64 48");
    }
    if (ALL_SAME_CHAR.matcher(trimmed).matches()) {
      throw new IllegalStateException("JWT_SECRET must not consist of a single repeated character. "
          + "Generate with: openssl rand -base64 48");
    }
    log.info("JWT secret validated (length={}, issuer={})", secret.length(), properties.issuer());
  }

  public String generateToken(String username, String role) {
    Instant now = Instant.now();
    Instant expireAt = now.plus(properties.expire() == null ? java.time.Duration.ofHours(2) : properties.expire());
    return Jwts.builder()
        .issuer(properties.issuer() == null ? "geosentry" : properties.issuer())
        .subject(username)
        .claim("role", role)
        .issuedAt(Date.from(now))
        .expiration(Date.from(expireAt))
        .signWith(signingKey)
        .compact();
  }

  public Claims parseToken(String token) {
    try {
      return Jwts.parser().verifyWith((javax.crypto.SecretKey) signingKey).build().parseSignedClaims(token).getPayload();
    } catch (Exception ex) {
      throw new AppException(401, "登录状态已过期，请重新登录");
    }
  }

  private byte[] resolveSecret(String secret) {
    return secret.getBytes(StandardCharsets.UTF_8);
  }
}
