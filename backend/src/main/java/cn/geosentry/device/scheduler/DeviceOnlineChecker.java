package cn.geosentry.device.scheduler;

import cn.geosentry.device.entity.MonitoringDevice;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import cn.geosentry.realtime.RealtimeBroadcastService;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备离线状态定时心跳检查。
 *
 * <p>每 {@code offline-check-interval-seconds} 秒扫描一次，
 * 将当前标记为"在线"但最后上报时间早于 {@code offline-threshold-minutes} 分钟前的设备
 * 置为"离线"，并通过 WebSocket 广播状态变更。
 *
 * <p>批量处理：每批最多处理 500 台设备，避免大表全量锁。查询条件已覆盖索引
 * {@code idx_device_status_region(online_status, region_code)} —— status 在前缀，可走索引扫描。
 */
@Component
public class DeviceOnlineChecker {

  private static final Logger log = LoggerFactory.getLogger(DeviceOnlineChecker.class);
  private static final int BATCH_SIZE = 500;

  private final MonitoringDeviceRepository repository;
  private final RealtimeBroadcastService broadcastService;
  private final int offlineThresholdMinutes;

  public DeviceOnlineChecker(MonitoringDeviceRepository repository,
                             RealtimeBroadcastService broadcastService,
                             @Value("${geosentry.device.offline-threshold-minutes:5}") int offlineThresholdMinutes) {
    this.repository = repository;
    this.broadcastService = broadcastService;
    this.offlineThresholdMinutes = offlineThresholdMinutes;
  }

  /** 默认 60s 执行一次，可通过 offline-check-interval-seconds 覆盖。 */
  @Scheduled(fixedDelayString = "${geosentry.device.offline-check-interval-seconds:60000}")
  @Transactional  // 批量离线标记保证原子性：中途异常不留下部分离线部分在线的不一致状态
  void scan() {
    OffsetDateTime cutoff = OffsetDateTime.now().minus(offlineThresholdMinutes, ChronoUnit.MINUTES);
    int totalProcessed = 0;
    int totalMarkedOffline = 0;
    // 游标式分批：每批处理完把设备置为离线，下一次查询会自然排除已处理设备，
    // 不依赖固定 offset 翻页（offset 翻页在数据变化时会跳过设备）。
    while (true) {
      List<MonitoringDevice> stale = repository.findOfflineCandidates(
          "在线", cutoff, PageRequest.of(0, BATCH_SIZE));
      if (stale.isEmpty()) {
        break;
      }
      for (MonitoringDevice device : stale) {
        // lastReportAt 为 NULL 表示从未上报，也计入离线（避免"假在线"）
        device.setOnlineStatus("离线");
        repository.save(device);
        totalMarkedOffline++;
        broadcastService.broadcastDevice(java.util.Map.of(
            "deviceCode", device.getDeviceCode(),
            "onlineStatus", "离线",
            "lastReportAt", device.getLastReportAt() == null ? null : device.getLastReportAt().toString(),
            "timestamp", OffsetDateTime.now().toString()
        ));
      }
      totalProcessed += stale.size();
      if (stale.size() < BATCH_SIZE) {
        break;  // 最后一批
      }
    }
    if (totalProcessed > 0) {
      log.info("离线检查完成：扫描 {} 台在线设备，{} 台被置为离线（阈值 {}min）",
          totalProcessed, totalMarkedOffline, offlineThresholdMinutes);
    }
  }
}
