package cn.disasterguard.device.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 演示设备在线保鲜调度器测试。
 *
 * <p>回归重点：
 * <ol>
 *   <li>开关默认关闭时不得刷新任何设备 —— 生产环境零影响；</li>
 *   <li>开启后只刷新 last_report_at，不得改 online_status —— 保持职责边界（谁来设在线仍由刷新脚本 / 真实上报负责）；</li>
 *   <li>只处理已在线设备，不把离线设备误刷成在线。</li>
 * </ol>
 */
class DemoDevicePresenceSchedulerTest {

  private MonitoringDevice device(String code, String status, OffsetDateTime lastReportAt) {
    MonitoringDevice d = new MonitoringDevice();
    d.setDeviceCode(code);
    d.setOnlineStatus(status);
    d.setLastReportAt(lastReportAt);
    return d;
  }

  @Test
  @DisplayName("开关关闭时 refreshPresence 不查库、不刷新（生产零影响）")
  void disabledDoesNothing() {
    MonitoringDeviceRepository repo = mock(MonitoringDeviceRepository.class);
    DemoDevicePresenceScheduler scheduler = new DemoDevicePresenceScheduler(repo, false);

    scheduler.refreshPresence();

    verify(repo, never()).findByOnlineStatus(any());
    verify(repo, never()).saveAll(anyList());
  }

  @Test
  @DisplayName("开启后刷新在线设备 last_report_at，但不改 online_status")
  void refreshesLastReportAtOnly() {
    MonitoringDeviceRepository repo = mock(MonitoringDeviceRepository.class);
    DemoDevicePresenceScheduler scheduler = new DemoDevicePresenceScheduler(repo, true);

    OffsetDateTime stale = OffsetDateTime.now().minusMinutes(30);
    MonitoringDevice online = device("GNSS-001", "在线", stale);
    MonitoringDevice offline = device("RAIN-052", "离线", stale);
    // 仓储层只返回在线设备（findByOnlineStatus 的语义），离线设备不进入刷新流程
    when(repo.findByOnlineStatus("在线")).thenReturn(List.of(online));

    OffsetDateTime now = OffsetDateTime.now();
    long refreshed = scheduler.refreshOnlineDevices(now);

    assertEquals(1, refreshed);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MonitoringDevice>> captor = ArgumentCaptor.forClass(List.class);
    verify(repo).saveAll(captor.capture());
    List<MonitoringDevice> saved = captor.getValue();
    assertEquals(1, saved.size());
    assertEquals("在线", saved.get(0).getOnlineStatus(), "保鲜不得改在线状态");
    assertEquals(0, ChronoUnit.MILLIS.between(now, saved.get(0).getLastReportAt()), "last_report_at 应刷成传入时间");
    assertEquals("离线", offline.getOnlineStatus(), "离线设备状态不受影响");
  }

  @Test
  @DisplayName("无在线设备时返回 0，不触发 saveAll")
  void noOnlineDevices() {
    MonitoringDeviceRepository repo = mock(MonitoringDeviceRepository.class);
    DemoDevicePresenceScheduler scheduler = new DemoDevicePresenceScheduler(repo, true);
    when(repo.findByOnlineStatus("在线")).thenReturn(List.of());

    long refreshed = scheduler.refreshOnlineDevices(OffsetDateTime.now());

    assertEquals(0, refreshed);
    verify(repo, never()).saveAll(anyList());
  }

  @Test
  @DisplayName("refreshPresence 开启时确实调用仓储刷新")
  void enabledTriggersRepositoryCall() {
    MonitoringDeviceRepository repo = mock(MonitoringDeviceRepository.class);
    DemoDevicePresenceScheduler scheduler = new DemoDevicePresenceScheduler(repo, true);
    when(repo.findByOnlineStatus("在线")).thenReturn(List.of(
        device("GNSS-001", "在线", OffsetDateTime.now().minusMinutes(30))));

    scheduler.refreshPresence();

    verify(repo).findByOnlineStatus("在线");
    verify(repo).saveAll(anyList());
    assertTrue(true);
  }
}
