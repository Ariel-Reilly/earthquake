package cn.disasterguard.auth.jwt;

import io.jsonwebtoken.Claims;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * JWT 撤销名单：用于改密、禁用账号、主动登出等场景主动吊销尚未过期的 JWT。
 *
 * <p><b>主存储为 Redis</b>（多副本共享）：
 * <ul>
 *   <li>key：{@code jwt:blacklist:{jti}}（{@link #deriveKey(Claims)} 直接返回该形式）；</li>
 *   <li>value：固定字符串 {@code "1"}；</li>
 *   <li>TTL：JWT 的原始 {@code exp} 减去当前时间，即与 token 剩余有效期一致，
 *       到期由 Redis 自动删除，无需扫描清理。</li>
 * </ul>
 *
 * <p><b>降级</b>：Redis 未注入或读写抛异常时，自动退化为本进程内存表并打 WARN 日志。
 * 内存表同样记录原始 {@code exp}。降级期间写入的条目在 Redis 恢复后仍会被优先判定，
 * 不会把已吊销的 token 重新放行；但降级期间的吊销只在当前实例生效（多副本需 Redis）。
 *
 * <p>Redis 故障的 WARN 只打一次，恢复后自动复位，避免每个请求都刷日志。
 */
@Component
public class JwtBlacklist {

  private static final Logger log = LoggerFactory.getLogger(JwtBlacklist.class);

  /** Redis key 前缀，最终 key 形如 {@code jwt:blacklist:6f1c...}。 */
  public static final String KEY_PREFIX = "jwt:blacklist:";
  private static final String REVOKED_VALUE = "1";

  /** 允许为 null：未配置 Redis 时整体退化为单机内存。 */
  private final StringRedisTemplate redis;

  /**
   * 内存降级表：key = Redis key，value = 原始过期 epoch ms。
   * 正常期也写一份本地镜像，Redis 抖动/宕机时本实例仍能拒绝已吊销 token。
   */
  private final Map<String, Long> revoked = new ConcurrentHashMap<>();

  private final AtomicBoolean redisWarned = new AtomicBoolean(false);

  public JwtBlacklist(@Nullable StringRedisTemplate redis) {
    this.redis = redis;
    if (redis == null) {
      log.warn("未注入 StringRedisTemplate，JWT 黑名单降级为单机内存（多副本吊销互不可见）");
    }
  }

  /**
   * 检查给定 token key 是否已被吊销。先查本地镜像，再查 Redis；Redis 故障时降级为内存判定。
   */
  public boolean isRevoked(String tokenKey) {
    if (tokenKey == null || tokenKey.isBlank()) {
      return false;
    }
    // 降级期间写入的条目必须优先生效，Redis 恢复后不能因为 Redis 里没有就把 token 放行
    if (isRevokedInMemory(tokenKey)) {
      return true;
    }
    if (redis == null) {
      return false;
    }
    try {
      boolean hit = Boolean.TRUE.equals(redis.hasKey(redisKey(tokenKey)));
      redisWarned.set(false);
      return hit;
    } catch (RuntimeException e) {
      warnOnce("读取", e);
      return false;
    }
  }

  /**
   * 将 token 加入黑名单直至其原始 {@code exp} 到期。
   * 忽略空 key；已过期的 token 无需再拉黑。
   */
  public void revoke(String tokenKey, long originalExpEpochMs) {
    if (tokenKey == null || tokenKey.isBlank()) {
      return;
    }
    long ttlMs = originalExpEpochMs - System.currentTimeMillis();
    if (ttlMs <= 0) {
      return;
    }

    // 本地镜像：无论 Redis 是否成功都写，保证 Redis 故障时本实例仍拒绝该 token
    revoked.put(tokenKey, originalExpEpochMs);
    if (redis == null) {
      return;
    }
    try {
      // TTL 与 JWT 剩余有效期一致，Redis 到期自动过期
      redis.opsForValue().set(redisKey(tokenKey), REVOKED_VALUE, Duration.ofMillis(ttlMs));
      redisWarned.set(false);
    } catch (RuntimeException e) {
      warnOnce("写入", e);
    }
  }

  /**
   * 清理内存降级表中已过期条目，供 {@link JwtBlacklistCleanupScheduler} 周期调用。
   * Redis 主存储由 TTL 自动过期，此处不做 SCAN / DEL。
   */
  public void cleanup() {
    long now = System.currentTimeMillis();
    Iterator<Map.Entry<String, Long>> it = revoked.entrySet().iterator();
    while (it.hasNext()) {
      if (it.next().getValue() < now) {
        it.remove();
      }
    }
  }

  private boolean isRevokedInMemory(String tokenKey) {
    Long exp = revoked.get(tokenKey);
    if (exp == null) {
      return false;
    }
    if (exp < System.currentTimeMillis()) {
      revoked.remove(tokenKey);
      return false;
    }
    return true;
  }

  private void warnOnce(String op, RuntimeException e) {
    if (redisWarned.compareAndSet(false, true)) {
      log.warn("Redis {}失败，JWT 黑名单降级为单机内存（仅本实例生效）：{}；Redis 恢复后自动切回",
          op, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  /** 兼容历史调用：老代码可能传 {@code jti:xxx}，统一补成 Redis 前缀。 */
  private static String redisKey(String tokenKey) {
    return tokenKey.startsWith(KEY_PREFIX) ? tokenKey : KEY_PREFIX + tokenKey;
  }

  /**
   * 取自 token 的 Claims 派生稳定的 Redis key：{@code jwt:blacklist:{jti}}。
   * 迁移期若老 token 没有 jti，退化为 {@code jwt:blacklist:sub:<sub>:iat:<iat>}。
   */
  public static String deriveKey(Claims claims) {
    String jti = claims.getId();
    if (jti != null && !jti.isBlank()) {
      return KEY_PREFIX + jti;
    }
    return KEY_PREFIX + "sub:" + claims.getSubject() + ":iat:" + claims.getIssuedAt().getTime();
  }

  /** 为新签发的 token 生成唯一 jti。 */
  public static String newJti() {
    return UUID.randomUUID().toString();
  }

  /** 当前内存降级表条目数（仅用于监控/测试；Redis 主存储由 TTL 自动过期，不做计数）。 */
  public int size() {
    return revoked.size();
  }
}
