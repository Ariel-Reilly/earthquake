package cn.disasterguard.ai;

import cn.disasterguard.config.AiProperties;
import cn.disasterguard.ai.dto.AnomalyResult;
import cn.disasterguard.ai.dto.ComprehensiveAnalysisResult;
import cn.disasterguard.ai.dto.DeviceHealthRequestDto;
import cn.disasterguard.ai.dto.DeviceHealthResponseDto;
import cn.disasterguard.ai.dto.DetectionResult;
import cn.disasterguard.ai.dto.DisasterAssessmentRequestDto;
import cn.disasterguard.ai.dto.DisasterAssessmentResponseDto;
import cn.disasterguard.ai.dto.FeedbackRequestDto;
import cn.disasterguard.ai.dto.ForecastResult;
import cn.disasterguard.ai.dto.FusionRiskResult;
import cn.disasterguard.ai.dto.IndependentAssessmentResponseDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
    // 显式配置连接/读取超时（ai.timeout-ms），避免 AI 服务挂起时长时间阻塞调用方线程。
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    long timeoutMs = Math.max(1000L, properties.timeoutMs());
    factory.setConnectTimeout((int) Math.min(timeoutMs, 10_000));
    factory.setReadTimeout((int) timeoutMs);
    this.restClient = RestClient.builder()
        .baseUrl(properties.serviceBaseUrl())
        .requestFactory(factory)
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

  /**
   * 综合风险分析：POST /api/ai/comprehensive。
   *
   * <p>接收图像/降水/地理/设备四维因子，返回多维度明细 + 推理 + 建议的报告。
   * 字段是 snake_case（Python Pydantic 风格），通过 {@link ComprehensiveAnalysisResult} 上的
   * {@code @JsonAlias} 映射到 camelCase。
   */
  public ComprehensiveAnalysisResult comprehensiveAnalysis(
      String hazardCode, Map<String, Object> factors
  ) {
    requireEnabled();
    Map<String, Double> numericFactors = filterNumericFactors(factors);
    if (numericFactors.size() < factors.size()) {
      log.debug("AI /comprehensive: stripped {} non-numeric factors from payload",
          factors.size() - numericFactors.size());
    }
    Map<String, Object> payload = Map.of(
        "hazard_code", hazardCode,
        "factors", numericFactors
    );
    try {
      return restClient.post()
          .uri("/comprehensive")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(ComprehensiveAnalysisResult.class);
    } catch (Exception e) {
      log.warn("AI /comprehensive 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 综合分析不可用: " + e.getMessage());
    }
  }

  /**
   * 灾害评估：POST /api/ai/disaster-assessment/score。
   *
   * <p>本端点用于 Java 端落库 / 助手查询 / 单独测算场景：
   * 不强制依赖监测设备，能独立基于"当地地理状况 + 当地天气状况"输出
   * risk_level（LOW/MEDIUM/HIGH/CRITICAL） + concern_label（稳定/关注/预警/异常严重）+
   * disaster_type（滑坡/崩塌/泥石流/地裂缝/地面沉降/塌陷）。
   *
   * <p>设备因子存在时合并到 device 维度，参与加权打分；缺失时 Python 端自动调整权重并标
   * {@code device_available=false}，Java 端把该标志落库到 {@code ai_comprehensive_score.device_available}。
   */
  public DisasterAssessmentResponseDto disasterAssessment(DisasterAssessmentRequestDto request) {
    requireEnabled();
    // Python 端 DisasterAssessmentRequest.factors: Dict[str, float] 严格类型；
    // Java 端 aggregateFactors() 会注入 "image_source" / "land_use_type" 这类字符串元数据，
    // 必须先剥掉，否则 Pydantic 422 (float_parsing) 全量失败。
    Map<String, Object> rawFactors = request.factors() == null ? Map.of() : request.factors();
    Map<String, Double> numericFactors = filterNumericFactors(rawFactors);
    if (numericFactors.size() < rawFactors.size()) {
      log.debug("AI /disaster-assessment/score: stripped {} non-numeric factors from payload",
          rawFactors.size() - numericFactors.size());
    }
    Map<String, Object> numericFactorsAsObjects = new java.util.LinkedHashMap<>(numericFactors);
    DisasterAssessmentRequestDto sanitized = new DisasterAssessmentRequestDto(
        request.hazard(), numericFactorsAsObjects
    );
    try {
      return restClient.post()
          .uri("/disaster-assessment/score")
          .contentType(MediaType.APPLICATION_JSON)
          .body(sanitized)
          .retrieve()
          .body(DisasterAssessmentResponseDto.class);
    } catch (Exception e) {
      log.warn("AI /disaster-assessment/score 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 灾害评估不可用: " + e.getMessage());
    }
  }

  /**
   * 把 factors Map 里所有能转 double 的条目保留，字符串/布尔/复杂对象一律丢弃。
   *
   * <p>原因：Python ai-service 的 Pydantic schema 把 {@code factors} 声明为 {@code Dict[str, float]}
   * （严格要求数值）。但 Java 端 {@code AiComprehensiveAnalysisSupport.aggregateFactors()} 会往
   * factors 里塞 {@code image_source}（字符串：sentinel2/copernicus/...）和
   * {@code land_use_type}（字符串：urban/forest/...）这类描述性元数据，
   * 这些字段 Pydantic 会以 422 float_parsing 拒绝，导致整个 AI 调用失败。
   *
   * <p>这些元数据是有用的诊断信息，但不是 AI 模型的数值输入，剥离它们是最小变更。
   *
   * @return 只含可转 {@code Double} 的条目；null 值保留为 {@code 0.0}（避免空因子）。
   */
  private Map<String, Double> filterNumericFactors(Map<String, Object> raw) {
    Map<String, Double> out = new HashMap<>();
    if (raw == null) return out;
    for (Map.Entry<String, Object> e : raw.entrySet()) {
      Object v = e.getValue();
      if (v == null) {
        out.put(e.getKey(), 0.0);
        continue;
      }
      if (v instanceof Number n) {
        out.put(e.getKey(), n.doubleValue());
        continue;
      }
      if (v instanceof String s) {
        try {
          out.put(e.getKey(), Double.parseDouble(s.trim()));
        } catch (NumberFormatException ignored) {
          // 非数值字符串（如 image_source="copernicus_sentinel2"），丢弃
        }
      }
      // Boolean / List / Map 一律丢弃
    }
    return out;
  }

  /**
   * 设备健康评分：POST /api/ai/device-health/score。
   *
   * <p>本端点由 Python ai-service 完全接管"AI 算法"：
   * 多指标异常检测 + 位移时序预测 + 加权融合 + reasoning/actions 生成。
   * Java 侧只透传请求 + 落库，不在本地保留任何评分启发式。
   */
  public DeviceHealthResponseDto deviceHealthScore(DeviceHealthRequestDto request) {
    requireEnabled();
    try {
      return restClient.post()
          .uri("/device-health/score")
          .contentType(MediaType.APPLICATION_JSON)
          .body(request)
          .retrieve()
          .body(DeviceHealthResponseDto.class);
    } catch (Exception e) {
      log.warn("AI /device-health/score 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 设备健康评分不可用: " + e.getMessage());
    }
  }

  /**
   * 提交 AI 反馈：POST /api/ai/feedback（v1.4.0）。
   *
   * <p>值班员/系统对 AI 判定结果给出"实际是否发生"的对照，让算法能持续学习。
   * Java 端只透传；Python 端存到 FeedbackStore，后续可触发重训。
   */
  public Map<String, Object> submitFeedback(FeedbackRequestDto request) {
    requireEnabled();
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> resp = restClient.post()
          .uri("/feedback")
          .contentType(MediaType.APPLICATION_JSON)
          .body(request)
          .retrieve()
          .body(Map.class);
      return resp;
    } catch (Exception e) {
      log.warn("AI /feedback 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 反馈提交不可用: " + e.getMessage());
    }
  }

  /**
   * 社交媒体文本分析：情感极性 / 告警等级 / 灾害关键词 / 位置聚类 / 时间线。
   *
   * <p>调用 ai-service 的 {@code POST /api/ai/social-media/analyze}。入参为待分析文本列表，
   * 返回结构中带 {@code mock} 标志位，前端据此展示「演示数据」标识。
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> analyzeSocialMedia(List<Map<String, Object>> texts,
                                                List<Double> bbox,
                                                Integer timeRangeHours) {
    requireEnabled();
    Map<String, Object> payload = new HashMap<>();
    payload.put("texts", texts);
    if (bbox != null && !bbox.isEmpty()) {
      payload.put("bbox", bbox);
    }
    if (timeRangeHours != null) {
      payload.put("time_range_hours", timeRangeHours);
    }
    try {
      Map<String, Object> resp = restClient.post()
          .uri("/social-media/analyze")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(Map.class);
      return resp;
    } catch (Exception e) {
      log.warn("AI /social-media/analyze 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 社交媒体分析不可用: " + e.getMessage());
    }
  }

  // ============================================================ //
  // ============================================================ //

  /**
   * 查询当前 XGBoost fusion 模型状态：是否加载、训练时间、验证集指标、
   * 特征重要性、历史版本列表。供前端「真实模型/启发式」徽标使用。
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> modelInfo() {
    requireEnabled();
    try {
      return restClient.get()
          .uri("/model/info")
          .retrieve()
          .body(Map.class);
    } catch (Exception e) {
      log.warn("AI /model/info 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 模型信息不可用: " + e.getMessage());
    }
  }

  /** 重新加载磁盘模型（训练后立即热加载）。 */
  @SuppressWarnings("unchecked")
  public Map<String, Object> modelReload() {
    requireEnabled();
    try {
      return restClient.post()
          .uri("/model/reload")
          .retrieve()
          .body(Map.class);
    } catch (Exception e) {
      log.warn("AI /model/reload 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 模型重载不可用: " + e.getMessage());
    }
  }

  // ============================================================ //
  // ============================================================ //

  /**
   * 批量灾害监测：把监控网格点一次性发给 Python ai-service，
   * 返回 risk_level ≥ HIGH 的高风险点（供 Java 落库为 hazard_point）。
   *
   * <p>Python 端：POST /api/ai/disaster-assessment/detect-batch
   *
   * @param points 监控点列表（最多 64 个），每个含 lon/lat/region_code/weather
   * @param minRiskLevel HIGH / MEDIUM / LOW（默认 HIGH）
   * @return 包含 detected_count + detections 列表的 Map（detections 项已 Serializable）
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> detectHazardsBatch(List<Map<String, Object>> points, String minRiskLevel) {
    requireEnabled();
    Map<String, Object> payload = new HashMap<>();
    payload.put("points", points);
    payload.put("min_risk_level", minRiskLevel);
    try {
      return restClient.post()
          .uri("/disaster-assessment/detect-batch")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(Map.class);
    } catch (Exception e) {
      log.warn("AI /disaster-assessment/detect-batch 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 批量灾害监测不可用: " + e.getMessage());
    }
  }

  /**
   * v1.7.0 全国独立测算：POST /api/ai/disaster-assessment/independent。
   *
   * <p>不依赖接入设备/已知灾害点档案，仅需坐标 + 可选天气/地理因子。
   * 用于运维/值班员在全国任意位置快速评估风险，或对接外部系统。
   *
   * @param points  独立测算点列表（最多 32 个）
   * @param minRiskLevel 返回阈值（默认 MEDIUM）
   * @param mockWeather  天气因子缺失时是否自动填充季节性模拟值（默认 true）
   * @return 包含 totalPoints / detectedCount / results 列表的响应
   */
  public IndependentAssessmentResponseDto independentAssessment(
      List<Map<String, Object>> points,
      String minRiskLevel,
      boolean mockWeather
  ) {
    requireEnabled();
    Map<String, Object> payload = new HashMap<>();
    payload.put("points", points);
    payload.put("min_risk_level", minRiskLevel != null ? minRiskLevel : "MEDIUM");
    payload.put("mock_weather", mockWeather);
    try {
      return restClient.post()
          .uri("/disaster-assessment/independent")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(IndependentAssessmentResponseDto.class);
    } catch (Exception e) {
      log.warn("AI /disaster-assessment/independent 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 独立测算不可用: " + e.getMessage());
    }
  }

  /**
   * 按区域代码自动派生监控网格 + 批量检测。
   *
   * <p>Python 端：POST /api/ai/disaster-assessment/detect-regions
   * 每个 region 派生 3x3 网格（中心 + 八方位），grid_step_deg 控制步长。
   *
   * <p><strong>v1.7.0 已废弃</strong>：请改用 {@link #detectHazardsBatch(List, String)}，
   * 由 Java 调度器自行计算网格点（region 表含 center_lon/center_lat，不再依赖 Python 端写死坐标）。
   *
   * @param regionCodes 区域代码列表
   * @param gridStepDeg 网格步长（度，默认 0.02° ≈ 2.2 km）
   * @param weather 统一天气快照
   * @param minRiskLevel HIGH / MEDIUM / LOW
   * @deprecated v1.7.0 由 Java 调度器直接调用 detectHazardsBatch，不再需要此方法
   */
  @Deprecated
  @SuppressWarnings("unchecked")
  public Map<String, Object> detectHazardsForRegions(
      List<String> regionCodes,
      double gridStepDeg,
      Map<String, Object> weather,
      String minRiskLevel
  ) {
    requireEnabled();
    Map<String, Object> payload = new HashMap<>();
    payload.put("region_codes", regionCodes);
    payload.put("grid_step_deg", gridStepDeg);
    payload.put("weather", weather == null ? Map.of() : weather);
    payload.put("min_risk_level", minRiskLevel);
    try {
      return restClient.post()
          .uri("/disaster-assessment/detect-regions")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(Map.class);
    } catch (Exception e) {
      log.warn("AI /disaster-assessment/detect-regions 调用失败: {}", e.getMessage());
      throw new AiUnavailableException("AI 区域批量灾害监测不可用: " + e.getMessage());
    }
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw new AiUnavailableException("AI 功能已关闭（ai.enabled=false）");
    }
  }
}
