package cn.disasterguard.auth.jwt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理 {@link JwtBlacklist} 中已过期的吊销条目。
 *
 * <p>黑名单按 (tokenKey, 原 exp) 维护，token 自然过期后该条目已无意义，
 * 周期性回收避免内存单调增长。频率默认每 10 分钟一次，由 cron 控制；
 * 多副本部署下每个实例都会跑，仅单机内存，影响可忽略。
 *
 * <p>无需事务：纯内存 Map 操作。
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