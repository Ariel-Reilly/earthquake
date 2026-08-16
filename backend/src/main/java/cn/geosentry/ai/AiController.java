package cn.geosentry.ai;

import cn.geosentry.ai.dto.AnomalyResult;
import cn.geosentry.ai.dto.DetectionResult;
import cn.geosentry.ai.dto.ForecastResult;
import cn.geosentry.ai.dto.FusionRiskResult;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import cn.geosentry.common.api.ApiResponse;

@RestController
@RequestMapping("/api/ai")
public class AiController {

  private final AiServiceClient client;

  public AiController(AiServiceClient client) {
    this.client = client;
  }

  /** 图像识别。 */
  @PostMapping("/detect")
  public ApiResponse<DetectionResult> detect(
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false, defaultValue = "yolo") String modelType
  ) {
    return ApiResponse.success(client.detect(file));
  }

  /** 时序预测。 */
  @PostMapping("/forecast")
  public ApiResponse<ForecastResult> forecast(@RequestBody ForecastRequest request) {
    return ApiResponse.success(client.forecast(request.deviceCode(), request.metric(), request.horizon(), request.history()));
  }

  /** 异常检测。 */
  @PostMapping("/anomaly")
  public ApiResponse<AnomalyResult> anomaly(@RequestBody AnomalyRequest request) {
    return ApiResponse.success(client.anomaly(request.deviceCode(), request.data()));
  }

  /** 融合预警。 */
  @PostMapping("/fusion-risk")
  public ApiResponse<FusionRiskResult> fusionRisk(@RequestBody FusionRiskRequest request) {
    return ApiResponse.success(client.fusionRisk(request.hazardCode(), request.factors()));
  }

  public record ForecastRequest(String deviceCode, String metric, int horizon, List<Double> history) {
  }

  public record AnomalyRequest(String deviceCode, List<Double> data) {
  }

  public record FusionRiskRequest(String hazardCode, Map<String, Double> factors) {
  }
}
