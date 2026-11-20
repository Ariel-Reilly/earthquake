package cn.disasterguard.device.scheduler;

import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示设备「在线保鲜」调度器。
 *
 * <p>问题背景：dev / 演示库的设备不会真实上报，在线状态由
 * {@code scripts/delivery/refresh-demo-data.sql} 手动刷成「在线」且 last_report_at=now()。
 * 但 {@link DeviceOnlineChecker} 每 60s 会把「在线且 5 分钟未上报」的设备打回离线，
 * 因此刷新约 5 分钟后总览页「在线监测设备」必然回落为 0/16，评审会误以为功能没接数据。
 *
 * <p>本调度器在**演示环境**定时把仍标记为「在线」的设备 last_report_at 刷成当前时间，
 * 模拟设备持续上报，使在线数稳定。职责边界：
 * <ul>
 *   <li>只刷新 last_report_at，不改 online_status —— 「谁把设备设为在线」仍由刷新脚本 / 真实上报负责；</li>
 *   <li>只处理当前「在线」的设备，不会把离线设备误刷成在线；</li>
 *   <li>默认关闭（{@code demo-presence-enabled=false}），生产环境严禁开启，否则会掩盖真实设备断连。</li>
 * </ul>
 *
 * <p>生产设备请走 MQTT / HTTP 真实上报（见 {@code DeviceDataService}），由真实数据流更新 last_report_at。
 */
@Component
public class DemoDevicePresenceScheduler {

  private static final Logger log = LoggerFactory.getLogger(DemoDevicePresenceScheduler.class);

  private final MonitoringDeviceRepository repository;
  private final boolean enabled;

  public DemoDevicePresenceScheduler(
      MonitoringDeviceRepository repository,
      @Value("${disasterguard.device.demo-presence-enabled:false}") boolean enabled) {
    this.repository = repository;
    this.enabled = enabled;
  }

  /** 默认 60s 一次（远小于 5min 离线阈值），可通过配置覆盖。 */
  @Scheduled(fixedDelayString = "${disasterguard.device.demo-presence-interval-seconds:60000}",
             initialDelay = 20000)
  @Transactional
  public void refreshPresence() {
    if (!enabled) {
      return;
    }
    long refreshed = refreshOnlineDevices(OffsetDateTime.now());
    if (refreshed > 0) {
      log.info("演示设备在线保鲜：刷新 {} 台在线设备的 last_report_at", refreshed);
    }
  }

  /**
   * 刷新全部在线设备的上报时间。拆成独立方法便于单测直接调用（不依赖调度与开关注入时机）。
   *
   * @param now 要写入的上报时间
   * @return 实际刷新的设备数
   */
  long refreshOnlineDevices(OffsetDateTime now) {
    List<MonitoringDevice> online = repository.findByOnlineStatus("在线");
    if (online.isEmpty()) {
      return 0;
    }
    for (MonitoringDevice device : online) {
      device.setLastReportAt(now);
    }
    repository.saveAll(online);
    return online.size();
  }
}
