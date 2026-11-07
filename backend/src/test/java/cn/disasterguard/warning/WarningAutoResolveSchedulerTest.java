package cn.disasterguard.warning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.realtime.RealtimeBroadcastService;
import cn.disasterguard.warning.engine.WarningAutoResolveScheduler;
import cn.disasterguard.warning.entity.WarningEvent;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 预警自动解除调度器测试（R10 P1-4 + A2 离线保护）。
 *
 * <p>回归重点：
 * <ol>
 *   <li>规则引擎「已有未关闭预警就跳过」是必需的防重复手段，但旧实现没有任何反向退出机制，
 *       一条预警挂住后该规则对该点终身只报一次。这里钉住自动解除的四个落库字段，避免改回"只跳过不解除"。</li>
 *   <li>A2 修复：解除前校验隐患点源设备是否在线。设备离线 / 数据中断时，规则引擎因收不到数据
 *       而不会再次命中，last_seen_at 停滞会被误判为"风险解除"。此时必须跳过解除，避免掩盖真实在险。</li>
 * </ol>
 */
class WarningAutoResolveSchedulerTest {

  private WarningEvent warning(String code, String ruleCode) {
    WarningEvent w = new WarningEvent();
    w.setWarningCode(code);
    w.setRuleCode(ruleCode);
    w.setHazardCode("HZ-SBOX-01");
    w.setTitle("测试预警");
    w.setWarningLevel("黄色预警");
    w.setStatus("待确认");
    w.setOccurCount(3);
    w.setLastSeenAt(OffsetDateTime.now().minusMinutes(40));
    w.setTriggeredAt(OffsetDateTime.now().minusMinutes(40));
    return w;
  }

  private MonitoringDevice offlineDevice(String hazardCode) {
    MonitoringDevice d = new MonitoringDevice();
    d.setDeviceCode("SB-001");
    d.setHazardCode(hazardCode);
    d.setOnlineStatus("离线");
    d.setLastReportAt(OffsetDateTime.now().minusMinutes(60));
    return d;
  }

  private MonitoringDevice onlineDevice(String hazardCode) {
    MonitoringDevice d = new MonitoringDevice();
    d.setDeviceCode("SB-001");
    d.setHazardCode(hazardCode);
    d.setOnlineStatus("在线");
    d.setLastReportAt(OffsetDateTime.now());
    return d;
  }

  @Test
  @DisplayName("静默超时的未关闭预警 → 转为已解除并标记自动解除")
  void resolvesStaleWarnings() {
    WarningEventRepository repository = mock(WarningEventRepository.class);
    RealtimeBroadcastService broadcast = mock(RealtimeBroadcastService.class);
    MonitoringDeviceRepository deviceRepo = mock(MonitoringDeviceRepository.class);
    when(deviceRepo.findByHazardCode(any())).thenReturn(List.of());
    WarningAutoResolveScheduler scheduler =
        new WarningAutoResolveScheduler(repository, broadcast, deviceRepo, true, 30);

    WarningEvent stale = warning("WG-TEST0001", "R001");
    when(repository.findStaleOpenWarnings(anyList(), any())).thenReturn(List.of(stale));

    int resolved = scheduler.resolveStaleWarnings();

    assertEquals(1, resolved);
    assertEquals("已解除", stale.getStatus());
    assertEquals("SYSTEM", stale.getClosedBy());
    assertEquals(Boolean.TRUE, stale.getAutoResolved());
    assertTrue(stale.getCloseReason().contains("自动解除"), "解除原因需写明是系统自动解除");
    assertTrue(stale.getClosedAt() != null, "解除必须写 closed_at，否则前端统计会漏");
    verify(repository, times(1)).save(stale);
    verify(broadcast, times(1)).broadcastWarning(stale);
    // 复现计数不能被解除动作清零：它是"这条预警持续了多久"的证据
    assertEquals(3, stale.getOccurCount());
  }

  @Test
  @DisplayName("A2：隐患点源设备离线 + 静默超时 → 不解除（掉线≠安全）")
  void skipsResolveWhenDeviceOffline() {
    WarningEventRepository repository = mock(WarningEventRepository.class);
    RealtimeBroadcastService broadcast = mock(RealtimeBroadcastService.class);
    MonitoringDeviceRepository deviceRepo = mock(MonitoringDeviceRepository.class);
    when(deviceRepo.findByHazardCode("HZ-SBOX-01")).thenReturn(List.of(offlineDevice("HZ-SBOX-01")));
    WarningAutoResolveScheduler scheduler =
        new WarningAutoResolveScheduler(repository, broadcast, deviceRepo, true, 30);

    WarningEvent stale = warning("WG-TEST0002", "R001");
    when(repository.findStaleOpenWarnings(anyList(), any())).thenReturn(List.of(stale));

    int resolved = scheduler.resolveStaleWarnings();

    assertEquals(0, resolved, "设备离线时不应自动解除");
    assertEquals("待确认", stale.getStatus(), "预警应保持未关闭，等待设备恢复或人工处置");
    assertNull(stale.getClosedAt(), "不应写入 closed_at");
    verify(repository, never()).save(any());
    verify(broadcast, never()).broadcastWarning(any());
  }

  @Test
  @DisplayName("A2：隐患点源设备在线 + 静默超时 → 正常解除")
  void resolvesWhenDeviceOnline() {
    WarningEventRepository repository = mock(WarningEventRepository.class);
    RealtimeBroadcastService broadcast = mock(RealtimeBroadcastService.class);
    MonitoringDeviceRepository deviceRepo = mock(MonitoringDeviceRepository.class);
    when(deviceRepo.findByHazardCode("HZ-SBOX-01")).thenReturn(List.of(onlineDevice("HZ-SBOX-01")));
    WarningAutoResolveScheduler scheduler =
        new WarningAutoResolveScheduler(repository, broadcast, deviceRepo, true, 30);

    WarningEvent stale = warning("WG-TEST0003", "R001");
    when(repository.findStaleOpenWarnings(anyList(), any())).thenReturn(List.of(stale));

    int resolved = scheduler.resolveStaleWarnings();

    assertEquals(1, resolved);
    assertEquals("已解除", stale.getStatus());
    verify(repository, times(1)).save(stale);
  }

  @Test
  @DisplayName("没有超时预警 → 不做任何写操作")
  void doesNothingWhenNothingStale() {
    WarningEventRepository repository = mock(WarningEventRepository.class);
    RealtimeBroadcastService broadcast = mock(RealtimeBroadcastService.class);
    MonitoringDeviceRepository deviceRepo = mock(MonitoringDeviceRepository.class);
    when(deviceRepo.findByHazardCode(any())).thenReturn(List.of());
    WarningAutoResolveScheduler scheduler =
        new WarningAutoResolveScheduler(repository, broadcast, deviceRepo, true, 30);

    when(repository.findStaleOpenWarnings(anyList(), any())).thenReturn(List.of());

    assertEquals(0, scheduler.resolveStaleWarnings());
    verify(repository, times(0)).save(any());
    verify(broadcast, times(0)).broadcastWarning(any());
  }

  @Test
  @DisplayName("开关关闭时定时任务直接返回，不查库")
  void disabledDoesNothing() {
    WarningEventRepository repository = mock(WarningEventRepository.class);
    RealtimeBroadcastService broadcast = mock(RealtimeBroadcastService.class);
    MonitoringDeviceRepository deviceRepo = mock(MonitoringDeviceRepository.class);
    WarningAutoResolveScheduler scheduler =
        new WarningAutoResolveScheduler(repository, broadcast, deviceRepo, false, 30);

    scheduler.scheduledResolve();

    verify(repository, times(0)).findStaleOpenWarnings(anyList(), any());
  }

  @Test
  @DisplayName("静默窗口 <=0 视为关闭，即使 enabled=true 也不解除")
  void nonPositiveWindowDoesNothing() {
    WarningEventRepository repository = mock(WarningEventRepository.class);
    RealtimeBroadcastService broadcast = mock(RealtimeBroadcastService.class);
    MonitoringDeviceRepository deviceRepo = mock(MonitoringDeviceRepository.class);
    WarningAutoResolveScheduler scheduler =
        new WarningAutoResolveScheduler(repository, broadcast, deviceRepo, true, 0);

    scheduler.scheduledResolve();

    verify(repository, times(0)).findStaleOpenWarnings(anyList(), any());
  }
}
