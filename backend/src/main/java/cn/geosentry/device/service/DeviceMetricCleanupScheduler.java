package cn.geosentry.device.service;

import cn.geosentry.device.repository.DeviceMetricRepository;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * device_metric 清理策略：保留最近 {@code retention-days} 天，避免表无限膨胀。
 *
 * <p>规则引擎只消费「最新值」快照（见 {@code RuleEngineScheduler}），因此历史样本按天滚动清理；
 * 如需长期历史请接入 TDengine/时序库。定时每日执行，开关 {@code geosentry.device.metric-cleanup-enabled}。
 */
@Component
public class DeviceMetricCleanupScheduler {

  private static final Logger log = LoggerFactory.getLogger(DeviceMetricCleanupScheduler.class);

  private final DeviceMetricRepository repository;
  private final boolean enabled;
  private final int retentionDays;

  public DeviceMetricCleanupScheduler(DeviceMetricRepository repository,
                                      @Value("${geosentry.device.metric-cleanup-enabled:true}") boolean enabled,
                                      @Value("${geosentry.device.metric-retention-days:30}") int retentionDays) {
    this.repository = repository;
    this.enabled = enabled;
    this.retentionDays = Math.max(1, retentionDays);
  }

  @Scheduled(cron = "${geosentry.device.metric-cleanup-cron:0 30 3 * * *}")
  @Transactional
  public void cleanup() {
    if (!enabled) {
      return;
    }
    OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);
    int deleted = repository.deleteOlderThan(cutoff);
    if (deleted > 0) {
      log.info("device_metric 清理完成，删除 {} 条早于 {} 的历史样本", deleted, cutoff);
    }
  }
}
