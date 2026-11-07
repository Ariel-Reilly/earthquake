package cn.disasterguard.warning.engine;

import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.realtime.RealtimeBroadcastService;
import cn.disasterguard.warning.entity.WarningEvent;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预警自动解除调度器（R10 P1-4）。
 *
 * <p>背景：规则引擎的「同 (规则, 隐患点) 已有未关闭预警就跳过」是防重复建单的必要手段，
 * 但旧实现没有任何反向机制——预警一旦挂住没人处理，该规则对该点就永久哑火。
 * 对持续性监测平台是功能性缺陷：第二场暴雨来临时平台不会再次报警，而恰恰那是最需要它的时刻。
 *
 * <p>策略：规则每命中一次就累加 {@code occur_count} 并刷新 {@code last_seen_at}；
 * 本调度器扫描「超过 {@code auto-resolve-after-minutes} 分钟没有再命中」的未关闭预警，
 * 自动转为「已解除」，写入解除原因与标记位，前端据此显示"自动解除"。
 *
 * <p>边界：
 * <ul>
 *   <li>只处理系统生成的预警（source 为空或非 MANUAL），人工上报的必须由人处理；</li>
 *   <li>必须显式打开 {@code disasterguard.warning.auto-resolve-enabled} 才生效；</li>
 *   <li>解除只是让规则可以重新建单，不删除历史记录；</li>
 *   <li>解除前校验隐患点源设备是否在线：设备离线 / 数据中断时跳过解除（掉线≠安全），避免掩盖真实在险。</li>
 * </ul>
 */
@Component
public class WarningAutoResolveScheduler {

  private static final Logger log = LoggerFactory.getLogger(WarningAutoResolveScheduler.class);

  /** 与 RuleEngineScheduler 保持同一组终态，避免两边口径漂移。 */
  private static final List<String> CLOSED_STATUSES = List.of("已关闭", "已忽略", "已解除");

  private static final String AUTO_CLOSE_REASON = "指标连续未再命中阈值，系统自动解除";

  private final WarningEventRepository warningRepository;
  private final RealtimeBroadcastService realtimeBroadcastService;
  private final MonitoringDeviceRepository monitoringDeviceRepository;

  private final boolean enabled;
  private final int autoResolveAfterMinutes;

  public WarningAutoResolveScheduler(
      WarningEventRepository warningRepository,
      RealtimeBroadcastService realtimeBroadcastService,
      MonitoringDeviceRepository monitoringDeviceRepository,
      @Value("${disasterguard.warning.auto-resolve-enabled:true}") boolean enabled,
      @Value("${disasterguard.warning.auto-resolve-after-minutes:30}") int autoResolveAfterMinutes) {
    this.warningRepository = warningRepository;
    this.realtimeBroadcastService = realtimeBroadcastService;
    this.monitoringDeviceRepository = monitoringDeviceRepository;
    this.enabled = enabled;
    this.autoResolveAfterMinutes = autoResolveAfterMinutes;
  }

  /**
   * 定时入口。
   *
   * <p>⚠️ 事务注解必须打在这里，不能打在下面的 {@link #resolveStaleWarnings()} 上：
   * 同类自调用不经过 Spring 代理，方法级 {@code @Transactional} 会静默失效
   * （这正是本轮 R10 P1-2 修掉的同一个坑）。{@code @Scheduled} 由容器通过代理调用，
   * 因此这里的注解是真实生效的。
   */
  @Scheduled(cron = "${disasterguard.warning.auto-resolve-cron:0 * * * * *}")
  @Transactional
  public void scheduledResolve() {
    if (!enabled || autoResolveAfterMinutes <= 0) {
      return;
    }
    try {
      int resolved = resolveStaleWarnings();
      if (resolved > 0) {
        log.info("自动解除 {} 条静默超过 {} 分钟的预警", resolved, autoResolveAfterMinutes);
      }
    } catch (Exception ex) {
      // 调度器顶层兜底：自动解除失败不应影响规则引擎与其它调度
      log.error("自动解除调度异常: {}", ex.getMessage(), ex);
    }
  }

  /**
   * 执行一轮自动解除，返回解除条数。
   *
   * <p>调用方必须已开启事务（当前唯一入口是 {@link #scheduledResolve()}）。
   */
  public int resolveStaleWarnings() {
    OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(autoResolveAfterMinutes);
    List<WarningEvent> stale = warningRepository.findStaleOpenWarnings(CLOSED_STATUSES, cutoff);
    if (stale.isEmpty()) {
      return 0;
    }
    OffsetDateTime now = OffsetDateTime.now();
    int resolvedCount = 0;
    for (WarningEvent warning : stale) {
      // 【A2 修复】解除前先校验隐患点源设备是否在线 / 仍在报数。
      // 设备掉线或数据中断时，规则引擎因收不到数据而不会再次命中，
      // last_seen_at 会停滞，从而被误判为「风险解除」。此时不能解除——
      // 掉线不等于安全，否则会掩盖真实在险状态。多设备隐患点任一设备离线即跳过解除，
      // 属故意保守：宁可多留一条开放预警，也不错误关闭。仅跳过本轮解除，
      // 预警保持未关闭，待设备恢复或人工处置后再由后续周期处理。
      DeviceOnlineCheck check = checkDeviceOnline(warning);
      if (check == DeviceOnlineCheck.OFFLINE) {
        log.warn("自动解除跳过 code={} hazard={} rule={}：设备离线，暂不解除（掉线≠安全，隐患点 {} 关联设备未全部在线）",
            warning.getWarningCode(), warning.getHazardCode(), warning.getRuleCode(), warning.getHazardCode());
        continue;
      }
      if (check == DeviceOnlineCheck.NO_DEVICE) {
        // 无 hazard_code 或未关联监测设备：无法校验掉线，按原逻辑解除，
        // 但日志明确区分「无设备可查」，便于演示 / 排障解释。
        log.info("预警自动解除 code={} rule={} hazard={}：无关联监测设备可查，按原逻辑解除（未能校验设备在线状态）",
            warning.getWarningCode(), warning.getRuleCode(), warning.getHazardCode());
      }
      warning.setStatus("已解除");
      warning.setClosedAt(now);
      warning.setCloseReason(AUTO_CLOSE_REASON);
      warning.setClosedBy("SYSTEM");
      warning.setAutoResolved(true);
      warningRepository.save(warning);
      log.info("预警自动解除 code={} rule={} hazard={} occurCount={} lastSeenAt={}",
          warning.getWarningCode(), warning.getRuleCode(), warning.getHazardCode(),
          warning.getOccurCount(), warning.getLastSeenAt());
      resolvedCount++;
    }
    // 推送放到提交之后：否则事务一旦回滚，前端已经看到"已解除"而库里还是未关闭。
    // 被跳过的预警（仍为未关闭）不推送，避免演示时出现"解除"假象。
    for (WarningEvent warning : stale) {
      if ("已解除".equals(warning.getStatus())) {
        broadcastAfterCommit(warning);
      }
    }
    return resolvedCount;
  }

  /**
   * 隐患点源设备在线状态核查。
   *
   * <p>{@code OFFLINE}：关联监测设备中任一未处于「在线」状态（离线 / 训练中 / 部署中 /
   * 回滚），即视为数据源中断，应跳过自动解除（掉线≠安全）；
   * {@code NO_DEVICE}：预警无 hazard_code、或隐患点未关联任何监测设备，无法判定掉线，
   * 按原有逻辑正常解除（覆盖人工 / AI 预警及无源设备的隐患点），并在解除日志中标注
   * 「无关联监测设备可查」以便与「设备离线」跳过原因区分；
   * {@code OK}：设备全部在线，可正常解除。
   */
  private enum DeviceOnlineCheck { OFFLINE, NO_DEVICE, OK }

  private DeviceOnlineCheck checkDeviceOnline(WarningEvent warning) {
    String hazardCode = warning.getHazardCode();
    if (hazardCode == null) {
      return DeviceOnlineCheck.NO_DEVICE;
    }
    List<MonitoringDevice> devices = monitoringDeviceRepository.findByHazardCode(hazardCode);
    if (devices == null || devices.isEmpty()) {
      return DeviceOnlineCheck.NO_DEVICE;
    }
    boolean anyOffline = devices.stream().anyMatch(d -> !"在线".equals(d.getOnlineStatus()));
    return anyOffline ? DeviceOnlineCheck.OFFLINE : DeviceOnlineCheck.OK;
  }

  private void broadcastAfterCommit(WarningEvent warning) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void afterCommit() {
              safeBroadcast(warning);
            }
          });
      return;
    }
    safeBroadcast(warning);
  }

  private void safeBroadcast(WarningEvent warning) {
    try {
      realtimeBroadcastService.broadcastWarning(warning);
    } catch (Exception ex) {
      log.debug("自动解除后推送失败 code={}: {}", warning.getWarningCode(), ex.getMessage());
    }
  }
}
