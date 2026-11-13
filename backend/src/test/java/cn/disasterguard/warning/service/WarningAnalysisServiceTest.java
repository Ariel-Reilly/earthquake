package cn.disasterguard.warning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.disasterguard.ai.service.AiInsightService;
import cn.disasterguard.ai.service.AiRiskEnricher;
import cn.disasterguard.attachment.service.AttachmentService;
import cn.disasterguard.device.entity.DeviceMetric;
import cn.disasterguard.device.repository.DeviceMetricRepository;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.device.service.DeviceImageService;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import cn.disasterguard.warning.entity.WarningEvent;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 预警「AI 分析详情」聚合服务的降级路径单测。
 *
 * <p>分析详情页依赖 AI/指标/影像三类外部数据；任何一类缺失都不能让接口 500，
 * 必须返回空数组并标记 fallback，前端据此显示"暂无数据"而不是伪造数据。
 * 本测试把全部依赖 mock 成"无数据"，锁定这条降级契约。
 */
class WarningAnalysisServiceTest {

  private final WarningEventRepository warningRepository = mock(WarningEventRepository.class);
  private final HazardPointRepository hazardRepository = mock(HazardPointRepository.class);
  private final DeviceMetricRepository metricRepository = mock(DeviceMetricRepository.class);
  private final MonitoringDeviceRepository deviceRepository = mock(MonitoringDeviceRepository.class);
  private final DeviceImageService deviceImageService = mock(DeviceImageService.class);
  private final AttachmentService attachmentService = mock(AttachmentService.class);
  private final AiInsightService insightService = mock(AiInsightService.class);
  private final AiRiskEnricher riskEnricher = mock(AiRiskEnricher.class);

  private final WarningAnalysisService service = new WarningAnalysisService(
      warningRepository,
      hazardRepository,
      metricRepository,
      deviceRepository,
      deviceImageService,
      attachmentService,
      insightService,
      riskEnricher);

  @Test
  void describe_withNoAiNoMetricsNoImages_returnsHonestEmptyResult() {
    WarningEvent warning = new WarningEvent();
    warning.setId(42L);
    warning.setWarningCode("WG-TEST-42");
    warning.setTitle("测试预警");
    warning.setHazardCode("HZ-TEST-01");
    warning.setWarningLevel("橙色预警");
    warning.setStatus("待确认");
    warning.setTriggerValue("位移速率 3.2 mm/d");
    warning.setTriggeredAt(OffsetDateTime.now().minusHours(1));

    when(warningRepository.findById(42L)).thenReturn(Optional.of(warning));
    when(hazardRepository.findByHazardCode("HZ-TEST-01")).thenReturn(null);
    when(insightService.findByHazardCode("HZ-TEST-01")).thenReturn(Optional.empty());
    when(riskEnricher.of("HZ-TEST-01")).thenReturn(null);
    // 触发点 ±2h 无样本
    when(metricRepository.findByHazardCodeAndRecordedAtBetween(eq("HZ-TEST-01"), any(), any()))
        .thenReturn(List.of());
    // 最新快照也为空
    when(metricRepository.findLatestByHazardCode("HZ-TEST-01")).thenReturn(List.of());
    when(deviceRepository.findByHazardCode("HZ-TEST-01")).thenReturn(List.of());
    when(attachmentService.list(anyString(), anyString())).thenReturn(List.of());
    when(deviceImageService.listRecent(anyString(), eq(2))).thenReturn(List.of());

    Map<String, Object> result = service.describe(42L);

    assertThat(result).containsKeys("warning", "ai", "metrics", "images");
    assertThat(result.get("ai")).isNull();
    assertThat(result.get("images")).isEqualTo(List.of());

    @SuppressWarnings("unchecked")
    Map<String, Object> metrics = (Map<String, Object>) result.get("metrics");
    assertThat(metrics).containsEntry("fallback", true);
    assertThat(metrics.get("series")).isEqualTo(List.of());

    @SuppressWarnings("unchecked")
    Map<String, Object> warningContext = (Map<String, Object>) result.get("warning");
    assertThat(warningContext).containsEntry("id", 42L);
    assertThat(warningContext).containsEntry("hazardCode", "HZ-TEST-01");
    assertThat(warningContext).containsEntry("triggerValue", "位移速率 3.2 mm/d");
  }
}
