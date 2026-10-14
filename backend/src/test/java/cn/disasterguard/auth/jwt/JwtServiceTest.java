package cn.disasterguard.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.WeakKeyException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JwtService — 签发/校验/吊销")
class JwtServiceTest {

  /** 测试专用强随机密钥（非生产值，≥32 字节） */
  private static final String VALID_SECRET =
      "test-only-strong-random-secret-key-abcdef-0123456789";
  private static final Duration DEFAULT_EXPIRE = Duration.ofHours(2);
  private static final String DEFAULT_ISSUER = "disasterguard-test";

  private JwtBlacklist blacklist;

  @BeforeEach
  void setUp() {
    blacklist = mock(JwtBlacklist.class);
    when(blacklist.isRevoked(anyString())).thenReturn(false);
  }

  private JwtService newService() {
    return newService(VALID_SECRET, DEFAULT_EXPIRE);
  }

  private JwtService newService(String secret, Duration expire) {
    return new JwtService(new JwtProperties(secret, expire, DEFAULT_ISSUER), blacklist);
  }

  // ============================================================
  // 密钥校验（validate）
  // ============================================================

  @Nested
  @DisplayName("密钥校验")
  class Validate {

    @Test
    @DisplayName("合法密钥应通过校验")
    void validSecret() {
      JwtService service = newService();
      service.validate();
    }

    @Test
    @DisplayName("长度不足 32 字节的密钥应在构造时拒绝（WeakKeyException）")
    void tooShortSecret() {
      assertThrows(WeakKeyException.class, () -> newService("short-secret", DEFAULT_EXPIRE));
    }

    @Test
    @DisplayName("黑名单密钥（40 字符）应被 validate 拒绝")
    void blacklistedSecret() {
      JwtService service =
          newService("change-me-change-me-change-me-change-me", DEFAULT_EXPIRE);
      assertThrows(IllegalStateException.class, service::validate);
    }

    @Test
    @DisplayName("纯数字密钥（32 位）应被 validate 拒绝")
    void purelyNumericSecret() {
      JwtService service = newService("12345678901234567890123456789012", DEFAULT_EXPIRE);
      assertThrows(IllegalStateException.class, service::validate);
    }

    @Test
    @DisplayName("纯字母密钥（32 位）应被 validate 拒绝")
    void purelyAlphabeticSecret() {
      JwtService service = newService("abcdefghijklmnopqrstuvwxyzabcdef", DEFAULT_EXPIRE);
      assertThrows(IllegalStateException.class, service::validate);
    }

    @Test
    @DisplayName("全部相同字符的密钥应被 validate 拒绝")
    void allSameCharSecret() {
      JwtService service = newService("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", DEFAULT_EXPIRE);
      assertThrows(IllegalStateException.class, service::validate);
    }
  }

  // ============================================================
  // 签发 + 解析
  // ============================================================

  @Nested
  @DisplayName("签发与解析")
  class GenerateAndParse {

    @Test
    @DisplayName("生成的 token 应能被自身解析且字段完整")
    void roundTrip() {
      JwtService service = newService();
      String token = service.generateToken("alice", "admin");
      assertNotNull(token);

      Claims claims = service.parseToken(token);
      assertEquals("alice", claims.getSubject());
      assertEquals("admin", claims.get("role"));
      assertEquals(DEFAULT_ISSUER, claims.getIssuer());
      assertNotNull(claims.getId(), "jti 应存在");
      assertNotNull(claims.getExpiration(), "exp 应存在");
    }

    @Test
    @DisplayName("篡改的 token 应被拒绝（401）")
    void tamperedToken() {
      JwtService service = newService();
      String token = service.generateToken("alice", "admin");
      String tampered = token.substring(0, token.length() - 2) + "XX";

      AppException ex = assertThrows(AppException.class, () -> service.parseToken(tampered));
      assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("null / 空 token 应被拒绝（401）")
    void nullOrEmptyToken() {
      JwtService service = newService();
      assertEquals(401, assertThrows(AppException.class, () -> service.parseToken(null)).getCode());
      assertEquals(401, assertThrows(AppException.class, () -> service.parseToken("")).getCode());
    }

    @Test
    @DisplayName("已吊销的 token 应被拒绝（401）")
    void revokedToken() {
      JwtService service = newService();
      String token = service.generateToken("alice", "admin");

      // 解析前把 key 标记为已吊销
      when(blacklist.isRevoked(anyString())).thenReturn(true);

      AppException ex = assertThrows(AppException.class, () -> service.parseToken(token));
      assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("过期的 token 应被拒绝（401）")
    void expiredToken() {
      JwtService service = newService(VALID_SECRET, Duration.ofSeconds(-10));
      String token = service.generateToken("alice", "admin");

      AppException ex = assertThrows(AppException.class, () -> service.parseToken(token));
      assertEquals(401, ex.getCode());
    }
  }

  // ============================================================
  // 吊销
  // ============================================================

  @Nested
  @DisplayName("revoke()")
  class Revoke {

    @Test
    @DisplayName("null claims 应静默返回，不调用 blacklist")
    void nullClaims() {
      JwtService service = newService();
      service.revoke(null);
      verify(blacklist, never()).revoke(anyString(), anyLong());
    }

    @Test
    @DisplayName("有效 claims 应调用 blacklist.revoke")
    void validClaims() {
      JwtService service = newService();
      String token = service.generateToken("alice", "admin");
      Claims claims = service.parseToken(token);

      service.revoke(claims);
      verify(blacklist, times(1)).revoke(anyString(), anyLong());
    }
  }
}