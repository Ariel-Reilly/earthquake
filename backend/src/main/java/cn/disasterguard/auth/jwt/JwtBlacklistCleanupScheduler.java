package cn.disasterguard.auth.jwt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理 {@link JwtBlacklist} 内存降级表中已过期的吊销条目。
 *
 * <p>主存储已改为 Redis（key {@code jwt:blacklist:{jti}}，TTL 与 JWT 剩余有效期一致），
 * 由 Redis 自行过期；这里只回收「Redis 不可用期间写入 / 本地镜像」的内存条目，
 * 避免降级表单调增长。频率默认每 10 分钟一次，由 cron 控制；每个实例各跑各的。
 *
 * <p>无需事务：纯内存 Map 操作，不触碰 Redis。
 */
@Component
public class JwtBlacklistCleanupScheduler {

  private static final Logger log = LoggerFactory.getLogger(JwtBlacklistCleanupScheduler.class);

  private final JwtBlacklist blacklist;

  public JwtBlacklistCleanupScheduler(JwtBlacklist blacklist) {
    this.blacklist = blacklist;
  }

  @Scheduled(cron = "${disasterguard.auth.jwt-blacklist-cleanup-cron:0 */10 * * * *}")
  public void cleanup() {
    int before = blacklist.size();
    blacklist.cleanup();
    int after = blacklist.size();
    if (before != after) {
      log.info("JWT 黑名单清理完成: 清理前 {} 条，清理后 {} 条", before, after);
    }
  }
}