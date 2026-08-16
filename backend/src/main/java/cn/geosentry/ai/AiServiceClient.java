package cn.geosentry.ai;

import cn.geosentry.ai.dto.AiApiResponse;
import cn.geosentry.ai.dto.AnomalyResult;
import cn.geosentry.ai.dto.DetectionResult;
import cn.geosentry.ai.dto.ForecastResult;
import cn.geosentry.ai.dto.FusionRiskResult;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
 * <p>与 Python FastAPI 服务通过 REST 解耦。AI 服务不可用或未启用时抛出
 * {@link AiUnavailableException}，由上层降级为纯规则模式，不阻塞主流程。
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

  /** 图像识别：multipart 上传，调用 POST /detect。 */
  public DetectionResult detect(MultipartFile file) {
    requireEnabled();
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("model_type", "yolo");
    form.add("file", file.getResource());

    return invoke(() -> {
      return restClient.post()
          .uri("/detect")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(form)
          .retrieve()
          .body(new ParameterizedTypeReference<AiApiResponse<DetectionResult>>() {
          });
    });
  }

  /** 时序预测：POST /forecast。 */
  public ForecastResult forecast(String deviceCode, String metric, int horizon, List<Double> history) {
    requireEnabled();
    Map<String, Object> payload = Map.of(
        "device_code", deviceCode,
        "metric", metric,
        "horizon", horizon,
        "history", history
    );
    return invoke(() -> {
      return restClient.post()
          .uri("/forecast")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(new ParameterizedTypeReference<AiApiResponse<ForecastResult>>() {
          });
    });
  }

  /** 异常检测：POST /anomaly。 */
  public AnomalyResult anomaly(String deviceCode, List<Double> data) {
    requireEnabled();
    Map<String, Object> payload = Map.of("device_code", deviceCode, "data", data);
    return invoke(() -> {
      return restClient.post()
          .uri("/anomaly")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(new ParameterizedTypeReference<AiApiResponse<AnomalyResult>>() {
          });
    });
  }

  /** 融合预警：POST /fusion-risk。 */
  public FusionRiskResult fusionRisk(String hazardCode, Map<String, Double> factors) {
    requireEnabled();
    Map<String, Object> payload = Map.of("hazard_code", hazardCode, "factors", factors);
    return invoke(() -> {
      return restClient.post()
          .uri("/fusion-risk")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(new ParameterizedTypeReference<AiApiResponse<FusionRiskResult>>() {
          });
    });
  }

  /** 统一执行 AI 请求：任何连接/解析异常都转为 AiUnavailableException，便于上层降级。 */
  private <T> T invoke(Supplier<AiApiResponse<T>> call) {
    try {
      return process(call.get());
    } catch (AiUnavailableException e) {
      throw e;
    } catch (Exception e) {
      log.warn("调用 AI 服务失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 服务不可用: " + e.getMessage());
    }
  }

  private <T> T process(AiApiResponse<T> response) {
    if (response == null || response.code() != 0) {
      throw new AiUnavailableException("AI 服务返回异常: " + (response == null ? "empty" : response.message()));
    }
    return response.data();
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw new AiUnavailableException("AI 功能已关闭（ai.enabled=false）");
    }
  }
}
