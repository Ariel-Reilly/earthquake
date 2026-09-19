package cn.disasterguard.auth.jwt;

import cn.disasterguard.common.exception.AppException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
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
      "disasterguard-secret",
      "disasterguard-secret-key",
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
  private final JwtBlacklist blacklist;

  public JwtService(JwtProperties properties, JwtBlacklist blacklist) {
    this.properties = properties;
    this.blacklist = blacklist;
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
        .id(JwtBlacklist.newJti())
        .issuer(properties.issuer() == null ? "disasterguard" : properties.issuer())
        .subject(username)
        .claim("role", role)
        .issuedAt(Date.from(now))
        .expiration(Date.from(expireAt))
        .signWith(signingKey)
        .compact();
  }

  public Claims parseToken(String token) {
    Claims claims;
    try {
      claims = Jwts.parser().verifyWith((javax.crypto.SecretKey) signingKey).build().parseSignedClaims(token).getPayload();
    } catch (ExpiredJwtException ex) {
      log.debug("JWT 已过期: {}", ex.getMessage());
      throw new AppException(401, "登录状态已过期，请重新登录");
    } catch (UnsupportedJwtException ex) {
      log.warn("不支持的 JWT 格式: {}", ex.getMessage());
      throw new AppException(401, "登录状态无效");
    } catch (MalformedJwtException ex) {
      log.warn("JWT 格式错误: {}", ex.getMessage());
      throw new AppException(401, "登录状态无效");
    } catch (SecurityException ex) {
      // 签名/密钥相关错误
      log.warn("JWT 验签失败: {}", ex.getMessage());
      throw new AppException(401, "登录状态无效");
    } catch (JwtException ex) {
      // 其他 JJWT 已知异常
      log.warn("JWT 解析失败 {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
      throw new AppException(401, "登录状态无效");
    } catch (IllegalArgumentException ex) {
      // token 为 null/空/空白
      log.debug("JWT 参数非法: {}", ex.getMessage());
      throw new AppException(401, "登录状态无效");
    }

    // 验签通过后再查黑名单，避免伪造 token 通过验签阶段却拿不到正确 Claims。
    String key = JwtBlacklist.deriveKey(claims);
    if (blacklist.isRevoked(key)) {
      log.info("拒绝已吊销 JWT key={}", key);
      throw new AppException(401, "登录状态已失效，请重新登录");
    }
    return claims;
  }

  /**
   * 将给定 token 对应的身份吊销（改密/禁用账号等场景）。
   * 入参是已通过 {@link #parseToken(String)} 校验的 Claims，避免重复验签。
   */
  public void revoke(Claims claims) {
    if (claims == null) {
      return;
    }
    String key = JwtBlacklist.deriveKey(claims);
    Date exp = claims.getExpiration();
    long expMs = exp == null ? Long.MAX_VALUE : exp.getTime();
    blacklist.revoke(key, expMs);
    log.info("JWT 已吊销 key={}, 剩余 TTL ms={}", key, Math.max(0, expMs - System.currentTimeMillis()));
  }

  private byte[] resolveSecret(String secret) {
    return secret.getBytes(StandardCharsets.UTF_8);
  }
}
