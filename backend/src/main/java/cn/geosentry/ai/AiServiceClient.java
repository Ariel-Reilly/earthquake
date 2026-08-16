package cn.geosentry.ai;

import cn.geosentry.ai.dto.AnomalyResult;
import cn.geosentry.ai.dto.DetectionResult;
import cn.geosentry.ai.dto.ForecastResult;
import cn.geosentry.ai.dto.FusionRiskResult;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI 推理服务客户端。
 *
 * <p>与 Python FastAPI 服务（ai-service）通过 REST 解耦。
 * ai-service 直接返回 Pydantic model 的 JSON（无包装层），
 * 字段为 snake_case（通过 {@code @JsonProperty} 映射到 Java camelCase DTO）。
 *
 * <p>baseUrl 由配置项 {@code ai.service-base-url} 提供（默认 {@code http://localhost:8000/api/ai}），
 * 包含路由前缀 {@code /api/ai}；本类的 URI 仅写相对路径（{@code /fusion-risk}、{@code /forecast} 等），
 * 避免双重前缀。
 *
 * <p>AI 服务不可用或未启用时抛出 {@link AiUnavailableException}，
 * 由上层（RuleEngineScheduler / AiForecastScheduler）降级为纯规则模式，不阻塞主流程。
 */
@Service
public class AiServiceClient {

  private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

  private final AiProperties properties;
  private final RestClient restClient;

  public AiServiceClient(AiProperties properties) {
    this.properties = properties;
    this.restClient = RestClient.builder()
        .baseUrl(properties.serviceBaseUrl())
        .build();
  }

  public boolean isEnabled() {
    return properties.enabled();
  }

  /** 图像识别：multipart 上传，调用 POST /api/ai/detect。 */
  public DetectionResult detect(MultipartFile file) {
    requireEnabled();
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("model_type", "yolo");
    form.add("file", file.getResource());

    try {
      return restClient.post()
          .uri("/detect")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(form)
          .retrieve()
          .body(DetectionResult.class);
    } catch (Exception e) {
      log.warn("AI /detect 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 图像识别不可用: " + e.getMessage());
    }
  }

  /** 时序预测：POST /api/ai/forecast。 */
  public ForecastResult forecast(String deviceCode, String metric, int horizon, List<Double> history) {
    requireEnabled();
    Map<String, Object> payload = Map.of(
        "device_code", deviceCode,
        "metric", metric,
        "horizon", horizon,
        "history", history
    );
    try {
      return restClient.post()
          .uri("/forecast")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(ForecastResult.class);
    } catch (Exception e) {
      log.warn("AI /forecast 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 时序预测不可用: " + e.getMessage());
    }
  }

  /** 异常检测：POST /api/ai/anomaly。 */
  public AnomalyResult anomaly(String deviceCode, List<Double> data) {
    requireEnabled();
    Map<String, Object> payload = Map.of("device_code", deviceCode, "data", data);
    try {
      return restClient.post()
          .uri("/anomaly")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(AnomalyResult.class);
    } catch (Exception e) {
      log.warn("AI /anomaly 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 异常检测不可用: " + e.getMessage());
    }
  }

  /** 融合预警：POST /api/ai/fusion-risk。 */
  public FusionRiskResult fusionRisk(String hazardCode, Map<String, Double> factors) {
    requireEnabled();
    Map<String, Object> payload = Map.of("hazard_code", hazardCode, "factors", factors);
    try {
      return restClient.post()
          .uri("/fusion-risk")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(FusionRiskResult.class);
    } catch (Exception e) {
      log.warn("AI /fusion-risk 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 融合预警不可用: " + e.getMessage());
    }
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw new AiUnavailableException("AI 功能已关闭（ai.enabled=false）");
    }
  }
}
