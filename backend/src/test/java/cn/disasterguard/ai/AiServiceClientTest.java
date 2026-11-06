package cn.disasterguard.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cn.disasterguard.ai.dto.DeviceHealthRequestDto;
import cn.disasterguard.ai.dto.DisasterAssessmentRequestDto;
import cn.disasterguard.ai.dto.FeedbackRequestDto;
import cn.disasterguard.config.AiProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

/**
 * AiServiceClient 契约测试。
 *
 * <p>聚焦点：{@code ai.enabled=false} 时**所有公开方法**都必须抛
 * {@link AiUnavailableException}（code=503），触发上层降级。
 *
 * <p>不做的事：HTTP 异常路径（RestClient 内部创建，不可 mock）。
 * 那部分依赖 `restClient` 真实调用，集成测试才有意义。
 */
@DisplayName("AiServiceClient — 契约（enabled 开关）")
class AiServiceClientTest {

  private static final String FAKE_URL = "http://localhost:9999/api/ai";

  private AiServiceClient newClient(boolean enabled) {
    return new AiServiceClient(new AiProperties(FAKE_URL, enabled, 5000L));
  }

  @Nested
  @DisplayName("isEnabled()")
  class EnabledFlag {

    @Test
    @DisplayName("配置 enabled=true 时应返回 true")
    void enabledTrue() {
      assertTrue(newClient(true).isEnabled());
    }

    @Test
    @DisplayName("配置 enabled=false 时应返回 false")
    void enabledFalse() {
      assertFalse(newClient(false).isEnabled());
    }
  }

  @Nested
  @DisplayName("enabled=false 时所有方法应抛 AiUnavailableException(503)")
  class DisabledContract {

    private final AiServiceClient client = newClient(false);

    private void assertDisabled(Runnable call) {
      AiUnavailableException ex = assertThrows(AiUnavailableException.class, call::run);
      assertEquals(503, ex.getCode(), "降级异常应为 503");
    }

    @Test
    @DisplayName("detect 图像识别")
    void detect() {
      assertDisabled(() -> client.detect(mock(MultipartFile.class)));
    }

    @Test
    @DisplayName("forecast 时序预测")
    void forecast() {
      assertDisabled(() -> client.forecast("dev-1", "displacement", 24, List.of(1.0, 2.0)));
    }

    @Test
    @DisplayName("anomaly 异常检测")
    void anomaly() {
      assertDisabled(() -> client.anomaly("dev-1", List.of(1.0, 2.0)));
    }

    @Test
    @DisplayName("fusionRisk 融合预警")
    void fusionRisk() {
      assertDisabled(() -> client.fusionRisk("hz-1", Map.of("rainfall", 1.0)));
    }

    @Test
    @DisplayName("comprehensiveAnalysis 综合分析")
    void comprehensiveAnalysis() {
      assertDisabled(() -> client.comprehensiveAnalysis("hz-1", Map.of("k", 1.0)));
    }

    @Test
    @DisplayName("disasterAssessment 灾害评估")
    void disasterAssessment() {
      assertDisabled(() -> client.disasterAssessment(
          new DisasterAssessmentRequestDto(null, Map.of())));
    }

    @Test
    @DisplayName("deviceHealthScore 设备健康评分")
    void deviceHealthScore() {
      assertDisabled(() -> client.deviceHealthScore(new DeviceHealthRequestDto(null, Map.of(), null, null)));
    }

    @Test
    @DisplayName("submitFeedback 反馈提交")
    void submitFeedback() {
      assertDisabled(() -> client.submitFeedback(new FeedbackRequestDto(null, null, null, null, null, null, null, null, null, null)));
    }

    @Test
    @DisplayName("analyzeSocialMedia 社交媒体分析")
    void analyzeSocialMedia() {
      assertDisabled(() -> client.analyzeSocialMedia(List.of(), null, null));
    }

    @Test
    @DisplayName("modelInfo 模型信息")
    void modelInfo() {
      assertDisabled(() -> client.modelInfo());
    }

    @Test
    @DisplayName("modelReload 模型重载")
    void modelReload() {
      assertDisabled(() -> client.modelReload());
    }

    @Test
    @DisplayName("detectHazardsBatch 批量隐患检测")
    void detectHazardsBatch() {
      assertDisabled(() -> client.detectHazardsBatch(List.of(), "HIGH"));
    }

    @Test
    @DisplayName("independentAssessment 独立测算")
    void independentAssessment() {
      assertDisabled(() -> client.independentAssessment(List.of(), "MEDIUM", true));
    }

    @Test
    @DisplayName("detectHazardsForRegions 按区域检测（@Deprecated，仍测契约）")
    @SuppressWarnings("deprecation")
    void detectHazardsForRegions() {
      assertDisabled(() -> client.detectHazardsForRegions(
          List.of("region-1"), 0.02, Map.of(), "HIGH"));
    }
  }
}