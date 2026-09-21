package cn.disasterguard.warning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.emergency.service.EmergencyService;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import cn.disasterguard.realtime.RealtimeBroadcastService;
import cn.disasterguard.warning.dto.WarningCreateRequest;
import cn.disasterguard.warning.dto.WarningDto;
import cn.disasterguard.warning.entity.WarningEvent;
import cn.disasterguard.warning.repository.WarningEventRepository;
import cn.disasterguard.warning.service.WarningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 人工上报预警（POST /api/warnings）的落库契约测试。
 *
 * <p>前端向导页提交的是「英文等级码 + 中文灾害类型」，而库里
 * {@code warning_event.warning_level} 存中文（红色预警 / ...）、
 * {@code hazard_point.hazard_type} 存英文码（landslide / ...）。
 * 这两套编码一旦串行，预警等级样式与灾害类型筛选会静默失效，
 * 因此用测试把归一化行为固定住。
 */
@ExtendWith(MockitoExtension.class)
class WarningManualCreateTest {

  @Mock
  private WarningEventRepository warningRepository;
  @Mock
  private HazardPointRepository hazardRepository;
  @Mock
  private EmergencyService emergencyService;
  @Mock
  private RealtimeBroadcastService broadcastService;

  private WarningService service;

  @BeforeEach
  void setUp() {
    service = new WarningService(warningRepository, hazardRepository, emergencyService,
        broadcastService, new ObjectMapper());
  }

  private static WarningCreateRequest request(String level, String hazardType, String hazardCode) {
    return new WarningCreateRequest("测试预警", level, hazardCode, "某灾害点", hazardType,
        "清河镇", 104.06, 30.67, 5.0, List.of("sms"), "预警正文", null, "tester");
  }

  /** 取出真正被 save 的实体——hazardType 不在 WarningDto 里，只能从实体断言。 */
  private WarningEvent captureSaved() {
    ArgumentCaptor<WarningEvent> captor = ArgumentCaptor.forClass(WarningEvent.class);
    verify(warningRepository).save(captor.capture());
    return captor.getValue();
  }

  private void stubPersist() {
    when(hazardRepository.existsByHazardCode("HZ-001")).thenReturn(true);
    when(warningRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void 英文等级码归一化为中文存值() {
    stubPersist();

    WarningDto dto = service.createManual(request("orange", "滑坡", "HZ-001"));

    assertEquals("橙色预警", dto.level());
    assertEquals("橙色预警", captureSaved().getWarningLevel());
  }

  @Test
  void 中文灾害类型归一化为英文码() {
    stubPersist();

    service.createManual(request("red", "泥石流", "HZ-001"));

    assertEquals("debris_flow", captureSaved().getHazardType());
  }

  @Test
  void 等级与类型同时归一化并标记人工来源() {
    stubPersist();

    service.createManual(request("blue", "地面沉降", "HZ-001"));

    WarningEvent saved = captureSaved();
    assertEquals("蓝色预警", saved.getWarningLevel());
    assertEquals("subsidence", saved.getHazardType());
    assertEquals("MANUAL", saved.getSource());
    assertEquals("待确认", saved.getStatus());
  }

  @Test
  void 无法识别的等级回落为黄色预警() {
    stubPersist();

    service.createManual(request("ultraviolet", "滑坡", "HZ-001"));

    assertEquals("黄色预警", captureSaved().getWarningLevel());
  }

  @Test
  void 未在映射表内的灾害类型原样入库不丢值() {
    stubPersist();

    service.createManual(request("red", "洪水", "HZ-001"));

    assertEquals("洪水", captureSaved().getHazardType());
  }

  @Test
  void 扩展字段序列化进manualPayload() {
    stubPersist();

    service.createManual(request("red", "滑坡", "HZ-001"));

    String payload = captureSaved().getManualPayload();
    assertEquals(true, payload != null && payload.contains("\"region\":\"清河镇\""),
        "manual_payload 应包含区域字段，实际为: " + payload);
    assertEquals(true, payload != null && payload.contains("\"channels\":[\"sms\"]"),
        "manual_payload 应包含发布渠道，实际为: " + payload);
  }

  @Test
  void 隐患点不存在时拒绝创建() {
    when(hazardRepository.existsByHazardCode("HZ-404")).thenReturn(false);

    AppException ex = assertThrows(AppException.class,
        () -> service.createManual(request("red", "滑坡", "HZ-404")));
    assertEquals(400, ex.getCode());
    verify(warningRepository, never()).save(any());
  }

  @Test
  void 未关联隐患点时拒绝创建() {
    assertThrows(AppException.class, () -> service.createManual(request("red", "滑坡", "  ")));
    verify(warningRepository, never()).save(any());
  }

  @Test
  void 标题为空时拒绝创建() {
    AppException ex = assertThrows(AppException.class,
        () -> service.createManual(new WarningCreateRequest("  ", "red", "HZ-001", "某灾害点",
            "滑坡", null, null, null, null, null, null, null, null)));
    assertEquals(400, ex.getCode());
    verify(warningRepository, never()).save(any());
  }

  @Test
  void 灾害类型缺失时留空不臆造() {
    stubPersist();

    service.createManual(request("red", null, "HZ-001"));

    assertNull(captureSaved().getHazardType());
  }
}
