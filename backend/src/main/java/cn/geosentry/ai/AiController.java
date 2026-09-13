package cn.geosentry.ai;

import cn.geosentry.ai.dto.AnomalyResult;
import cn.geosentry.ai.dto.ComprehensiveAnalysisResult;
import cn.geosentry.ai.dto.DetectionResult;
import cn.geosentry.ai.dto.DisasterAssessmentRequestDto;
import cn.geosentry.ai.dto.DisasterAssessmentResponseDto;
import cn.geosentry.ai.dto.FeedbackRequestDto;
import cn.geosentry.ai.dto.ForecastResult;
import cn.geosentry.ai.dto.FusionRiskResult;
import cn.geosentry.ai.dto.IndependentAssessmentRequestDto;
import cn.geosentry.ai.dto.IndependentAssessmentResponseDto;
import jakarta.validation.Valid;
import cn.geosentry.ai.entity.AiComprehensiveScore;
import cn.geosentry.ai.service.AiComprehensiveAnalysisSupport;
import cn.geosentry.ai.service.AiDeviceHealthService;
import cn.geosentry.ai.service.AiInsightService;
import cn.geosentry.ai.service.GeoContextResolver;
import cn.geosentry.device.entity.MonitoringDevice;
import cn.geosentry.device.repository.DeviceMetricRepository;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import cn.geosentry.hazard.entity.HazardPoint;
import cn.geosentry.hazard.repository.HazardPointRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
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

  private static final Logger log = LoggerFactory.getLogger(AiController.class);
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  private final AiServiceClient client;
  private final MonitoringDeviceRepository deviceRepository;
  private final DeviceMetricRepository metricRepository;
  private final HazardPointRepository hazardRepository;
  private final GeoContextResolver geoContextResolver;
  private final AiComprehensiveAnalysisSupport support;
  private final AiInsightService insightService;
  private final AiDeviceHealthService deviceHealthService;

  public AiController(AiServiceClient client,
                      MonitoringDeviceRepository deviceRepository,
                      DeviceMetricRepository metricRepository,
                      HazardPointRepository hazardRepository,
                      GeoContextResolver geoContextResolver,
                      AiComprehensiveAnalysisSupport support,
                      AiInsightService insightService,
                      AiDeviceHealthService deviceHealthService) {
    this.client = client;
    this.deviceRepository = deviceRepository;
    this.metricRepository = metricRepository;
    this.hazardRepository = hazardRepository;
    this.geoContextResolver = geoContextResolver;
    this.support = support;
    this.insightService = insightService;
    this.deviceHealthService = deviceHealthService;
  }

  /** 图像识别。 */
  @PostMapping("/detect")
  public ApiResponse<DetectionResult> detect(
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false, defaultValue = "yolo") String modelType
  ) {
    return ApiResponse.success(client.detect(file));
  }

  /** 时序预测（显式传入历史数据）。 */
  @PostMapping("/forecast")
  public ApiResponse<ForecastResult> forecast(@RequestBody ForecastRequest request) {
    return ApiResponse.success(client.forecast(request.deviceCode(), request.metric(), request.horizon(), request.history()));
  }

  /** 时序预测（自动从 DB 拉取历史数据）。 */
  @GetMapping("/forecast/auto")
  public ApiResponse<ForecastResult> forecastAuto(
      @RequestParam String deviceCode,
      @RequestParam(defaultValue = "displacement") String metric,
      @RequestParam(defaultValue = "7") int horizon,
      @RequestParam(defaultValue = "30") int historyLimit) {
    boolean synthetic = false;
    List<Double> history = metricRepository.findRecentValues(deviceCode, metric, historyLimit);
    if (history.isEmpty()) {
      log.warn("AI forecast/auto: device={}, metric={} 无历史数据，使用合成数据降级", deviceCode, metric);
      // 无历史数据时生成一段合成序列（确保 AI 服务能返回有效结果）
      synthetic = true;
      List<Double> syntheticSeq = new ArrayList<>();
      for (int i = 0; i < 30; i++) {
        syntheticSeq.add(10.0 + 0.1 * i + Math.sin(i * 0.3) * 0.5);
      }
      history = syntheticSeq;
    } else {
      // findRecentValues 返回 DESC 顺序，需反转为 ASC 供 AI 预测
      List<Double> asc = new ArrayList<>(history);
      java.util.Collections.reverse(asc);
      history = asc;
    }
    ForecastResult result = client.forecast(deviceCode, metric, horizon, history);
    if (synthetic) {
      result.setDegraded(true);  // 通知前端：本次预测基于合成历史数据，置信度不可信
    }
    return ApiResponse.success(result);
  }

  /** 异常检测。 */
  @PostMapping("/anomaly")
  public ApiResponse<AnomalyResult> anomaly(@RequestBody AnomalyRequest request) {
    return ApiResponse.success(client.anomaly(request.deviceCode(), request.data()));
  }

  /** 融合预警（显式传入因子）。 */
  @PostMapping("/fusion-risk")
  public ApiResponse<FusionRiskResult> fusionRisk(@RequestBody FusionRiskRequest request) {
    return ApiResponse.success(client.fusionRisk(request.hazardCode(), request.factors()));
  }

  /**
   * 融合预警（自动从 DB 聚合真实因子）。
   *
   * <p>从 hazard 关联的 monitoring_device 中取最近一条指标（disp_rate / crack_width / rain_1h 等），
   * 结合 hazard_point 的风险等级映射 site_grade，构造完整因子后调用 AI 融合评分。
   * 若 hazard 无关联设备或无指标数据，使用合成因子兜底（仍调用 AI，保证前端能看到结果）。
   */
  @GetMapping("/fusion-risk/auto")
  public ApiResponse<FusionRiskResult> fusionRiskAuto(@RequestParam String hazardCode) {
    Map<String, Double> factors = new LinkedHashMap<>();

    // 1. site_grade: 从 hazard_point 的 riskLevel 映射
    HazardPoint hazard = hazardRepository.findByHazardCode(hazardCode);
    if (hazard != null && hazard.getRiskLevel() != null) {
      factors.put("site_grade", riskLevelToGrade(hazard.getRiskLevel()));
    } else {
      factors.put("site_grade", 2.0); // 默认中等
    }

    // 2. 聚合关联设备的最近指标
    List<MonitoringDevice> devices = deviceRepository.findByHazardCode(hazardCode);
    if (!devices.isEmpty()) {
      List<String> deviceCodes = devices.stream().map(MonitoringDevice::getDeviceCode).toList();
      var latestMetrics = metricRepository.findLatestByDeviceCodes(deviceCodes);

      // 按 metric_code 聚合（多设备同一指标取最大值，避免低的设备覆盖高的）
      Map<String, Double> maxByMetric = new HashMap<>();
      for (var m : latestMetrics) {
        String code = m.getMetricCode();
        double val = m.getValue();
        maxByMetric.merge(code, val, Math::max);
      }

      // 映射到 AI 因子名（与 ai-service fusion.py 默认权重对齐）
      if (maxByMetric.containsKey("disp_rate")) {
        factors.put("displacement_rate", maxByMetric.get("disp_rate"));
      }
      if (maxByMetric.containsKey("disp_total")) {
        factors.put("displacement_total", maxByMetric.get("disp_total"));
      }
      if (maxByMetric.containsKey("crack_width")) {
        factors.put("crack_width", maxByMetric.get("crack_width"));
      }
      if (maxByMetric.containsKey("rain_1h")) {
        // 优先用 DB 聚合真实雨量；无数据时降级为 rain_1h 原值
        Double rain24h = metricRepository.sumByHazardRecentHours(hazardCode, "rain_1h", 24);
        Double rain7d = metricRepository.sumByHazardRecentHours(hazardCode, "rain_1h", 24 * 7);
        factors.put("rainfall_24h", rain24h != null ? rain24h : maxByMetric.get("rain_1h"));
        factors.put("rainfall_7d", rain7d != null ? rain7d : maxByMetric.get("rain_1h"));
      }
    }

    // 3. 填充缺失因子（合成兜底值，确保 AI 服务能返回有效结果）
    if (!factors.containsKey("displacement_rate")) factors.put("displacement_rate", 0.0);
    if (!factors.containsKey("displacement_total")) factors.put("displacement_total", 0.0);
    if (!factors.containsKey("rainfall_24h")) factors.put("rainfall_24h", 0.0);
    if (!factors.containsKey("rainfall_7d")) factors.put("rainfall_7d", 0.0);
    if (!factors.containsKey("crack_width")) factors.put("crack_width", 0.0);
    if (!factors.containsKey("earthquake_mag")) factors.put("earthquake_mag", 0.0);

    log.debug("AI fusion-risk/auto: hazard={}, factors={}", hazardCode, factors);
    return ApiResponse.success(client.fusionRisk(hazardCode, factors));
  }

  /** 将 hazard_point.riskLevel（橙色预警等）映射为 1-4 的 site_grade。 */
  public static double riskLevelToGrade(String riskLevel) {
    if (riskLevel.contains("红")) return 4.0;
    if (riskLevel.contains("橙")) return 3.0;
    if (riskLevel.contains("黄")) return 2.0;
    if (riskLevel.contains("蓝") || riskLevel.contains("提醒")) return 1.0;
    return 2.0;
  }

  /**
   * 综合风险分析（自动从 DB 聚合三维度因子 + 设备指标）。 — 单点实时推理
   *
   * <p>不读落库表（即使已有缓存也强制跑一遍）；前端"AI 综合分析"页面或点击"重新分析"时调用。
   */
  @GetMapping("/comprehensive-analysis")
  public ApiResponse<ComprehensiveAnalysisResult> comprehensiveAnalysis(
      @RequestParam String hazardCode
  ) {
    Map<String, Object> factors = support.aggregateFactors(hazardCode);
    HazardPoint hazard = hazardRepository.findByHazardCode(hazardCode);
    String hazardName = hazard != null ? hazard.getName() : hazardCode;

    log.debug("AI comprehensive-analysis: hazard={}, factors={}", hazardCode, factors);
    ComprehensiveAnalysisResult result = client.comprehensiveAnalysis(hazardCode, factors);

    String analyzedAt = OffsetDateTime.now(ZoneOffset.UTC).format(ISO);
    ComprehensiveAnalysisResult enriched = new ComprehensiveAnalysisResult(
        result.hazardCode(),
        hazardName,
        analyzedAt,
        result.overall(),
        enrichDimensions(result.dimensions()),
        result.reasoning(),
        result.suggestedActions(),
        result.model(),
        result.degraded()
    );
    return ApiResponse.success(enriched);
  }

  /**
   * 综合分析落库列表（按 AI 综合分数倒序）。
   * 给地脉智巡助手 + 概览页面 + 排行榜组件使用；5 分钟一定时刷新（来自 scheduler）。
   */
  @GetMapping("/comprehensive-analysis/all")
  public ApiResponse<List<Map<String, Object>>> comprehensiveAnalysisAll() {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (AiComprehensiveScore s : insightService.listAll()) {
      rows.add(insightService.toAttachmentMap(s));
    }
    return ApiResponse.success(rows);
  }

  /**
   * 综合分析落库单点查询（不触发实时推理）。
   * 给 HazardDetailPage 顶部"AI 综合研判"区块用 —— 直接读落库缓存，避免每次进入详情页都跑一次 AI。
   * 缓存缺失（未到下一次落库周期）时返回 404，前端可选择触发 /refresh-one 强制跑一次。
   */
  @GetMapping("/comprehensive-analysis/cached")
  public ApiResponse<Map<String, Object>> comprehensiveAnalysisCached(
      @RequestParam String hazardCode
  ) {
    return insightService.findByHazardCode(hazardCode)
        .map(s -> ApiResponse.success(insightService.toAttachmentMap(s)))
        .orElseGet(() -> ApiResponse.fail(404, "AI 暂未对该灾害点评分（等待 5 分钟落库周期，或点 '立即重算'）"));
  }

  /**
   * 强制刷新所有 hazard 的综合分析（管理端"一键分析"按钮）。
   *
   * @return 包含 analyzedCount/failedCount/failedHazards/beforeCount/afterCount 的 Map
   */
  @PostMapping("/comprehensive-analysis/refresh")
  public ApiResponse<Map<String, Object>> comprehensiveAnalysisRefresh() {
    return ApiResponse.success(insightService.refreshAllNow());
  }

  /** 强制刷新单个 hazard。 */
  @PostMapping("/comprehensive-analysis/refresh-one")
  public ApiResponse<Boolean> comprehensiveAnalysisRefreshOne(@RequestParam String hazardCode) {
    try {
      insightService.refreshOne(hazardCode);
      return ApiResponse.success(true);
    } catch (Exception e) {
      return ApiResponse.fail(500, "刷新失败: " + e.getMessage());
    }
  }

  // ----------------------- 灾害评估 v1.3.0 -----------------------

  /**
   * 单独灾害评估：基于 hazard 实体 + DB 因子聚合调 Python 灾害评估端点。
   *
   * <p>不读 ai_comprehensive_score 落库表；不写回。用于：
   * <ul>
   *   <li>前端"AI 单独测算"按钮（与调度器 5 分钟一次落库互补）</li>
   *   <li>AI 助手"灾害类型是什么"对话意图</li>
   * </ul>
   *
   * <p>设备维度可选 —— 无设备时 Python 端会按 geographic+precipitation 权重自动测算并标 device_available=false。
   */
  @GetMapping("/disaster-assessment")
  public ApiResponse<DisasterAssessmentResponseDto> disasterAssessment(@RequestParam String hazardCode) {
    DisasterAssessmentRequestDto req = support.buildDisasterAssessmentRequest(hazardCode);
    DisasterAssessmentResponseDto result = client.disasterAssessment(req);
    return ApiResponse.success(result);
  }

  /** 灾害评估字典自检（前端探针/调试用）。 */
  @GetMapping("/disaster-assessment/status")
  public ApiResponse<Map<String, Object>> disasterAssessmentStatus() {
    Map<String, Object> dict = new LinkedHashMap<>();
    dict.put("disasterTypes", List.of("滑坡", "崩塌", "泥石流", "地裂缝", "地面沉降", "塌陷"));
    dict.put("concernLabels", Map.of(
        "LOW", "稳定",
        "MEDIUM", "关注",
        "HIGH", "预警",
        "CRITICAL", "异常严重"));
    dict.put("riskLevels", List.of("LOW", "MEDIUM", "HIGH", "CRITICAL"));
    return ApiResponse.success(dict);
  }

  // ----------------------- v1.7.0 全国独立测算 -----------------------

  /**
   * 全国独立测算：基于坐标 + 可选天气/地理因子，不依赖接入设备/已知灾害点档案。
   *
   * <p>POST /api/ai/disaster-assessment/independent
   *
   * <p>典型场景：
   * <ul>
   *   <li>运维/值班员在全国任意位置快速评估风险</li>
   *   <li>对接外部系统（如气象预警联动）直接推入坐标获取 AI 研判</li>
   *   <li>离线/无设备环境下仍输出关注/预警/异常严重的七维度结论</li>
   * </ul>
   *
   * <p>天气/地理因子全部可选；全缺时自动用季节性模拟值填充，保证 AI 始终能输出结论。
   *
   * @param request 独立测算请求（含坐标列表 + 可选因子 + 配置参数）
   * @return 每个点的完整七维度评估结论（riskLevel / concernLabel / disasterType / reasoning / suggestedActions）
   */
  @PostMapping("/disaster-assessment/independent")
  public ApiResponse<IndependentAssessmentResponseDto> independentAssessment(
      @Valid @RequestBody IndependentAssessmentRequestDto request
  ) {
    List<Map<String, Object>> points = request.points().stream()
        .map(pt -> {
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("lon", pt.lon());
          p.put("lat", pt.lat());
          if (pt.regionCode() != null) p.put("region_code", pt.regionCode());
          if (pt.regionName() != null) p.put("region_name", pt.regionName());
          if (pt.rainfall24h() != null) p.put("rainfall_24h", pt.rainfall24h());
          if (pt.rainfall7d() != null) p.put("rainfall_7d", pt.rainfall7d());
          if (pt.temperature() != null) p.put("temperature", pt.temperature());
          if (pt.humidity() != null) p.put("humidity", pt.humidity());
          if (pt.elevationM() != null) p.put("elevation_m", pt.elevationM());
          if (pt.slopeDeg() != null) p.put("slope_deg", pt.slopeDeg());
          if (pt.soilGrade() != null) p.put("soil_grade", pt.soilGrade());
          if (pt.vegetationCover() != null) p.put("vegetation_cover", pt.vegetationCover());
          if (pt.distanceToWaterM() != null) p.put("distance_to_water_m", pt.distanceToWaterM());
          if (pt.geologyGrade() != null) p.put("geology_grade", pt.geologyGrade());
          if (pt.geologyLabel() != null) p.put("geology_label", pt.geologyLabel());
          return p;
        })
        .toList();

    log.debug("AI 独立测算: {} 个点, minRisk={}, mockWeather={}",
        points.size(),
        request.minRiskLevel() != null ? request.minRiskLevel() : "MEDIUM",
        request.mockWeather() != null ? request.mockWeather() : true);

    IndependentAssessmentResponseDto result = client.independentAssessment(
        points,
        request.minRiskLevel(),
        request.mockWeather() == null || request.mockWeather()
    );
    return ApiResponse.success(result);
  }

  // ----------------------- 设备健康评分 -----------------------

  /**
   * AI 设备健康评分落库列表（按健康分升序，异常在前）。
   * 给 DevicesPage / DeviceDetailPage / 概览统计 / AiAssistant 排行用。
   */
  @GetMapping("/device-health/all")
  public ApiResponse<List<Map<String, Object>>> deviceHealthAll() {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var s : deviceHealthService.listAll()) {
      rows.add(deviceHealthService.toAttachmentMap(s));
    }
    return ApiResponse.success(rows);
  }

  /** 单设备 AI 健康评分。 */
  @GetMapping("/device-health")
  public ApiResponse<Map<String, Object>> deviceHealth(@RequestParam String deviceCode) {
    return deviceHealthService.findByDeviceCode(deviceCode)
        .map(s -> ApiResponse.success(deviceHealthService.toAttachmentMap(s)))
        .orElseGet(() -> ApiResponse.fail(404, "AI 暂未对该设备评分"));
  }

  /** 强制刷新所有设备 AI 健康评分。 */
  @PostMapping("/device-health/refresh")
  public ApiResponse<Map<String, Object>> deviceHealthRefresh() {
    return ApiResponse.success(deviceHealthService.refreshAllNow());
  }

  /** 强制刷新单台设备 AI 健康评分。 */
  @PostMapping("/device-health/refresh-one")
  public ApiResponse<Map<String, Object>> deviceHealthRefreshOne(@RequestParam String deviceCode) {
    try {
      var row = deviceHealthService.refreshOne(deviceCode);
      return ApiResponse.success(deviceHealthService.toAttachmentMap(row));
    } catch (IllegalArgumentException e) {
      return ApiResponse.fail(404, e.getMessage());
    } catch (Exception e) {
      return ApiResponse.fail(500, "刷新失败: " + e.getMessage());
    }
  }

  /**
   * 自然语言聊天入口（POST /api/ai/chat）。
   *
   * <p>demo 阶段用关键词分发到具体分析能力。生产可替换为 LLM（OpenAI/Moonshot）。
   */
  @PostMapping("/chat")
  public ApiResponse<Map<String, Object>> chat(@RequestBody ChatRequest request) {
    return ApiResponse.success(insightService.chat(request.message()));
  }

  /** GET 形态兼容（部分前端 fetch 库不便 POST JSON 时使用）。 */
  @GetMapping("/chat")
  public ApiResponse<Map<String, Object>> chatGet(@RequestParam("q") String message) {
    return ApiResponse.success(insightService.chat(message));
  }

  /**
   * 提交 AI 反馈：值班员/系统对照实际事件，给 AI 判定打分。
   *
   * <p>v1.4.0 新增。Java 端只做代理透传；Python ai-service 存到 FeedbackStore，
   * 后续可触发 train_xgboost.py 重新训练。
   */
  @PostMapping("/feedback")
  public ApiResponse<Map<String, Object>> submitFeedback(@RequestBody FeedbackRequestDto request) {
    try {
      Map<String, Object> resp = client.submitFeedback(request);
      return ApiResponse.success(resp);
    } catch (Exception e) {
      log.warn("提交 AI 反馈失败: {}", e.getMessage());
      return ApiResponse.fail(503, "AI 反馈提交不可用: " + e.getMessage());
    }
  }

  // ============================================================ //
  // v1.5.0：真实 XGBoost 模型信息端点（透传到 Python ai-service）
  // ============================================================ //

  /**
   * 当前 XGBoost fusion 模型状态 + 训练指标 + 特征重要性。
   * 用于前端展示「真实模型/启发式」徽标 + 训练时间 + 模型版本。
   */
  @GetMapping("/model/info")
  public ApiResponse<Map<String, Object>> modelInfo() {
    try {
      Map<String, Object> info = client.modelInfo();
      return ApiResponse.success(info);
    } catch (Exception e) {
      log.warn("查询 AI 模型信息失败: {}", e.getMessage());
      return ApiResponse.fail(503, "AI 模型信息不可用: " + e.getMessage());
    }
  }

  /** 重新加载磁盘模型（训练后立即热加载）。 */
  @PostMapping("/model/reload")
  public ApiResponse<Map<String, Object>> modelReload() {
    try {
      Map<String, Object> info = client.modelReload();
      return ApiResponse.success(info);
    } catch (Exception e) {
      log.warn("重载 AI 模型失败: {}", e.getMessage());
      return ApiResponse.fail(503, "AI 模型重载失败: " + e.getMessage());
    }
  }

  /**
   * 给 Python 返回的 dimensions 注入 Java 端已知的 image_source（兜底）。
   */
  private Map<String, Object> enrichDimensions(Map<String, Object> dims) {
    if (dims == null) return Map.of();
    Map<String, Object> copy = new LinkedHashMap<>(dims);
    if (copy.get("image") instanceof Map<?, ?> img) {
      Map<String, Object> imgCopy = new LinkedHashMap<>(copyImageMap(img));
      if (!imgCopy.containsKey("source")) imgCopy.put("source", "device_camera");
      copy.put("image", imgCopy);
    }
    return copy;
  }

  private Map<String, Object> copyImageMap(Map<?, ?> img) {
    Map<String, Object> m = new LinkedHashMap<>();
    img.forEach((k, v) -> {
      if (k instanceof String) m.put((String) k, v);
    });
    return m;
  }

  public record ForecastRequest(String deviceCode, String metric, int horizon, List<Double> history) {
  }

  public record AnomalyRequest(String deviceCode, List<Double> data) {
  }

  public record FusionRiskRequest(String hazardCode, Map<String, Double> factors) {
  }

  public record ChatRequest(String message) {
  }
}
