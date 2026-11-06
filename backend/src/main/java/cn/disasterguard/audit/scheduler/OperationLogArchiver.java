package cn.disasterguard.audit.scheduler;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * operation_log 归档调度器。
 *
 * <p>每日凌晨 3:00 调用 PostgreSQL 函数 {@code archive_operation_logs(retention_days)}，
 * 将超过保留期的审计日志迁移到 {@code operation_log_archive} 归档表并从主表删除。
 * 归档函数定义在 {@code infra/postgres/init/003_archive_operation_log.sql}。
 *
 * <p>若归档函数不存在（如首次部署未执行 init 脚本），调度器会 WARN 日志并跳过，
 * 不影响应用主流程。
 */
@Component
public class OperationLogArchiver {

  private static final Logger log = LoggerFactory.getLogger(OperationLogArchiver.class);

  private final JdbcTemplate jdbc;

  /** 日志保留天数，默认 90 天。 */
  @Value("${disasterguard.audit.log-retention-days:90}")
  int retentionDays;

  public OperationLogArchiver(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 每日凌晨 3:00 执行归档（cron: 秒 分 时 日 月 周）。 */
  @Scheduled(cron = "${disasterguard.audit.archive-cron:0 0 3 * * *}")
  void archive() {
    try {
      log.info("开始归档 operation_log（保留 {} 天，执行时间 {}）...", retentionDays, OffsetDateTime.now());
      var rows = jdbc.queryForRowSet("SELECT * FROM archive_operation_logs(?)", retentionDays);
      if (rows.next()) {
        int migrated = rows.getInt(1);
        int deleted = rows.getInt(2);
        log.info("operation_log 归档完成：迁移 {} 条到 operation_log_archive，删除 {} 条", migrated, deleted);
      } else {
        log.warn("archive_operation_logs() 返回空结果，请确认 infra/postgres/init/003_archive_operation_log.sql 已执行");
      }
    } catch (Exception e) {
      log.warn("operation_log 归档失败（跳过，不影响主流程）: {}", e.getMessage());
      log.warn("请确认已执行 infra/postgres/init/003_archive_operation_log.sql 创建归档函数和表");
    }
  }
}
