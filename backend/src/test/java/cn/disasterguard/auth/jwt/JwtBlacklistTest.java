package cn.disasterguard.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import java.time.Duration;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * JwtBlacklist 的 Redis 主存储与内存降级路径。
 *
 * <p>R8 补：verify-jwt-blacklist.ps1 只覆盖「Redis 正常」的端到端链路，
 * 这里用单测把「Redis 未注入 / Redis 读写抛异常」两条降级分支钉死，
 * 避免以后改安全配置或 Redis 客户端时把降级能力悄悄改坏。
 */
@DisplayName("JwtBlacklist — Redis 主存储与内存降级")
class JwtBlacklistTest {

  private static Claims claims(String jti, String sub, long iatEpochMs) {
    Claims c = mock(Claims.class);
    when(c.getId()).thenReturn(jti);
    when(c.getSubject()).thenReturn(sub);
    when(c.getIssuedAt()).thenReturn(new Date(iatEpochMs));
    return c;
  }

  @Test
  @DisplayName("deriveKey：jti 优先，无 jti 时退化为 sub + iat")
  void deriveKeyFormat() {
    assertEquals("jwt:blacklist:abc-123",
        JwtBlacklist.deriveKey(claims("abc-123", "admin", 1000L)));
    assertEquals("jwt:blacklist:sub:admin:iat:1234",
        JwtBlacklist.deriveKey(claims(null, "admin", 1234L)));
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("正常路径：key=jwt:blacklist:{jti}、value=1、TTL=JWT 剩余有效期")
  void redisWriteUsesRemainingLifetimeAsTtl() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(redis.hasKey(anyString())).thenReturn(true);

    JwtBlacklist blacklist = new JwtBlacklist(redis);
    String key = JwtBlacklist.deriveKey(claims("jti-1", "admin", 1000L));
    long exp = System.currentTimeMillis() + 60_000L;
    blacklist.revoke(key, exp);

    ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
    verify(ops).set(eq(key), eq("1"), ttl.capture());
    long actualMs = ttl.getValue().toMillis();
    assertTrue(actualMs > 55_000L && actualMs <= 60_000L,
        "TTL 应等于 JWT 剩余有效期（约 60s），实际 " + actualMs + "ms");
    assertTrue(blacklist.isRevoked(key), "Redis 命中应判定为已吊销");
  }

  @Test
  @DisplayName("未注入 Redis：纯内存降级仍能拒绝已吊销 token；已过期 token 不写入")
  void noRedisFallsBackToMemory() {
    JwtBlacklist blacklist = new JwtBlacklist(null);
    String key = "jwt:blacklist:jti-2";
    assertFalse(blacklist.isRevoked(key));

    blacklist.revoke(key, System.currentTimeMillis() + 60_000L);
    assertTrue(blacklist.isRevoked(key));
    assertEquals(1, blacklist.size());

    blacklist.revoke("jwt:blacklist:already-expired", System.currentTimeMillis() - 1L);
    assertFalse(blacklist.isRevoked("jwt:blacklist:already-expired"));
    assertEquals(1, blacklist.size(), "已过期 token 不应占用黑名单条目");
  }

  @Test
  @DisplayName("Redis 读写抛异常：降级不向外抛，本地镜像优先生效")
  void redisFailureFallsBackToMemory() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
    when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

    JwtBlacklist blacklist = new JwtBlacklist(redis);
    String key = "jwt:blacklist:jti-3";
    assertDoesNotThrow(() -> blacklist.revoke(key, System.currentTimeMillis() + 60_000L));
    assertTrue(blacklist.isRevoked(key), "降级期写入的条目必须仍然生效");
    verify(redis, never()).hasKey(anyString());
  }

  @Test
  @DisplayName("cleanup 只回收内存降级表的过期条目")
  void cleanupRemovesExpiredMemoryEntries() {
    JwtBlacklist blacklist = new JwtBlacklist(null);
    blacklist.revoke("jwt:blacklist:expired", System.currentTimeMillis() - 1L);
    // 过期 token 不会写入内存表；这里直接验证未写入 + cleanup 幂等
    assertEquals(0, blacklist.size());
    blacklist.cleanup();
    assertEquals(0, blacklist.size());

    blacklist.revoke("jwt:blacklist:valid", System.currentTimeMillis() + 60_000L);
    blacklist.cleanup();
    assertEquals(1, blacklist.size(), "未过期条目不应被清理");
  }
}
