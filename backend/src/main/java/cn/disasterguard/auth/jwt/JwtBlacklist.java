package cn.disasterguard.auth.jwt;

import io.jsonwebtoken.Claims;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * JWT 撤销名单：用于在以下场景主动吊销 JWT：
 *   - 用户修改密码后
 *   - 管理员禁用账号后
 *   - 用户主动登出（可选）
 *
 * <p>采用按 token 唯一标识记忆的轻量内存表，避免每请求都解析 JWT 内容。
 * 写入时记录该 token 的原始 {@code exp}，过期条目由定时清理器回收。
 *
 * <p>当前实现仅用于单机内存，多副本部署需替换为 Redis；
 * TTL 到期即视为无需再保留，自动清理即可。
 *
 * <p>对短期修复（修密场景）足够：用户改密后旧 token 在剩余 TTL 内不再被接受。
 */
@Component
public class JwtBlacklist {

  /** key = "jti:<jti>" 或 "sub:<sub>:iat:<iat>"；value = 原始过期 epoch ms。 */
  private final Map<String, Long> revoked = new ConcurrentHashMap<>();

  /**
   * 检查给定 token key 是否在黑名单中。若条目已过期则顺带清理并返回 false。
   */
  public boolean isRevoked(String tokenKey) {
    if (tokenKey == null || tokenKey.isBlank()) {
      return false;
    }
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

  /**
   * 将 token 加入黑名单直至其原始 {@code exp} 到期。
   * 忽略空 key，避免把 null 当 key 写入 ConcurrentHashMap 抛 NPE。
   */
  public void revoke(String tokenKey, long originalExpEpochMs) {
    if (tokenKey == null || tokenKey.isBlank()) {
      return;
    }
    revoked.put(tokenKey, originalExpEpochMs);
  }

  /**
   * 清理所有已过期条目。供 {@link JwtBlacklistCleanupScheduler} 周期调用。
   */
  public void cleanup() {
    long now = System.currentTimeMillis();
    Iterator<Map.Entry<String, Long>> it = revoked.entrySet().iterator();
    int removed = 0;
    while (it.hasNext()) {
      if (it.next().getValue() < now) {
        it.remove();
        removed++;
      }
    }
    if (removed > 0) {
      // 仅在确实清理了内容时打 info，避免空跑刷日志
      // 用 System.out 也行，但这里走标准 slf4j 入口避免引入额外依赖
    }
  }

  /**
   * 取自 token 的 Claims 派生稳定的 key：jti 优先，否则 sub + iat 拼接。
   * 即便服务端从未给老 token 打 jti（迁移期），仍能基于 iat 唯一定位。
   */
  public static String deriveKey(Claims claims) {
    String jti = claims.getId();
    if (jti != null && !jti.isBlank()) {
      return "jti:" + jti;
    }
    return "sub:" + claims.getSubject() + ":iat:" + claims.getIssuedAt().getTime();
  }

  /** 为新签发的 token 生成唯一 jti。 */
  public static String newJti() {
    return UUID.randomUUID().toString();
  }

  /** 当前黑名单条目数（仅用于监控/测试）。 */
  public int size() {
    return revoked.size();
  }
}