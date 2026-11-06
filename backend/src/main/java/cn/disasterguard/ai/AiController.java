package cn.disasterguard.ai;

import cn.disasterguard.ai.dto.AnomalyResult;
import cn.disasterguard.ai.dto.ComprehensiveAnalysisResult;
import cn.disasterguard.ai.dto.DetectionResult;
import cn.disasterguard.ai.dto.DisasterAssessmentRequestDto;
import cn.disasterguard.ai.dto.DisasterAssessmentResponseDto;
import cn.disasterguard.ai.dto.FeedbackRequestDto;
import cn.disasterguard.ai.dto.ForecastResult;
import cn.disasterguard.ai.dto.FusionRiskResult;
import cn.disasterguard.ai.dto.IndependentAssessmentRequestDto;
import cn.disasterguard.ai.dto.IndependentAssessmentResponseDto;
import jakarta.validation.Valid;
import cn.disasterguard.ai.entity.AiComprehensiveScore;
import cn.disasterguard.ai.entity.AiModel;
import cn.disasterguard.ai.repository.AiModelRepository;
import cn.disasterguard.ai.service.AiComprehensiveAnalysisSupport;
import cn.disasterguard.ai.service.AiDeviceHealthService;
import cn.disasterguard.ai.service.AiInsightService;
import cn.disasterguard.ai.service.GeoContextResolver;
import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.DeviceMetricRepository;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import cn.disasterguard.common.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;

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
  private final AiModelRepository modelRepository;

  public AiController(AiServiceClient client,
                      MonitoringDeviceRepository deviceRepository,
                      DeviceMetricRepository metricRepository,
                      HazardPointRepository hazardRepository,
                      GeoContextResolver geoContextResolver,
                      AiComprehensiveAnalysisSupport support,
                      AiInsightService insightService,
                      AiDeviceHealthService deviceHealthService,
                      AiModelRepository modelRepository) {
    this.client = client;
    this.deviceRepository = deviceRepository;
    this.metricRepository = metricRepository;
    this.hazardRepository = hazardRepository;
    this.geoContextResolver = geoContextResolver;
    this.support = support;
    this.insightService = insightService;
    this.deviceHealthService = deviceHealthService;
    this.modelRepository = modelRepository;
  }

  /** 图像识别。 */
  @PostMapping("/detect")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<DetectionResult> detect(
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false, defaultValue = "yolo") String modelType
  ) {
    return ApiResponse.success(client.detect(file));
  }

  /** 时序预测（显式传入历史数据）。 */
  @PostMapping("/forecast")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<ForecastResult> forecast(@RequestBody ForecastRequest request) {
    return ApiResponse.success(client.forecast(request.deviceCode(), request.metric(), request.horizon(), request.history()));
  }

  /** 时序预测（自动从 DB 拉取历史数据）。 */
  @GetMapping("/forecast/auto")
  @PreAuthorize("hasAuthority('ai:insight:view')")
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
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<AnomalyResult> anomaly(@RequestBody AnomalyRequest request) {
    return ApiResponse.success(client.anomaly(request.deviceCode(), request.data()));
  }

  /** 融合预警（显式传入因子）。 */
  @PostMapping("/fusion-risk")
  @PreAuthorize("hasAuthority('ai:insight:view')")
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
  @PreAuthorize("hasAuthority('ai:insight:view')")
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
  @PreAuthorize("hasAuthority('ai:insight:view')")
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
  @PreAuthorize("hasAuthority('ai:insight:view')")
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
  @PreAuthorize("hasAuthority('ai:insight:view')")
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
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> comprehensiveAnalysisRefresh() {
    return ApiResponse.success(insightService.refreshAllNow());
  }

  /** 强制刷新单个 hazard。 */
  @PostMapping("/comprehensive-analysis/refresh-one")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Boolean> comprehensiveAnalysisRefreshOne(@RequestParam String hazardCode) {
    try {
      insightService.refreshOne(hazardCode);
      return ApiResponse.success(true);
    } catch (Exception e) {
      return ApiResponse.fail(500, "刷新失败: " + e.getMessage());
    }
  }


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
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<DisasterAssessmentResponseDto> disasterAssessment(@RequestParam String hazardCode) {
    DisasterAssessmentRequestDto req = support.buildDisasterAssessmentRequest(hazardCode);
    DisasterAssessmentResponseDto result = client.disasterAssessment(req);
    return ApiResponse.success(result);
  }

  /** 灾害评估字典自检（前端探针/调试用）。 */
  @GetMapping("/disaster-assessment/status")
  @PreAuthorize("hasAuthority('ai:insight:view')")
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
  @PreAuthorize("hasAuthority('ai:insight:view')")
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


  /**
   * AI 设备健康评分落库列表（按健康分升序，异常在前）。
   * 给 DevicesPage / DeviceDetailPage / 概览统计 / AiAssistant 排行用。
   */
  @GetMapping("/device-health/all")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<List<Map<String, Object>>> deviceHealthAll() {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var s : deviceHealthService.listAll()) {
      rows.add(deviceHealthService.toAttachmentMap(s));
    }
    return ApiResponse.success(rows);
  }

  /** 单设备 AI 健康评分。 */
  @GetMapping("/device-health")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> deviceHealth(@RequestParam String deviceCode) {
    return deviceHealthService.findByDeviceCode(deviceCode)
        .map(s -> ApiResponse.success(deviceHealthService.toAttachmentMap(s)))
        .orElseGet(() -> ApiResponse.fail(404, "AI 暂未对该设备评分"));
  }

  /** 强制刷新所有设备 AI 健康评分。 */
  @PostMapping("/device-health/refresh")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> deviceHealthRefresh() {
    return ApiResponse.success(deviceHealthService.refreshAllNow());
  }

  /** 强制刷新单台设备 AI 健康评分。 */
  @PostMapping("/device-health/refresh-one")
  @PreAuthorize("hasAuthority('ai:insight:view')")
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
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> chat(@RequestBody ChatRequest request) {
    return ApiResponse.success(insightService.chat(request.message()));
  }

  /** GET 形态兼容（部分前端 fetch 库不便 POST JSON 时使用）。 */
  @GetMapping("/chat")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> chatGet(@RequestParam("q") String message) {
    return ApiResponse.success(insightService.chat(message));
  }

  /**
   * AI 欢迎语 / 推荐提问（POST /api/ai/welcome-prompts）。
   *
   * <p>根据前端传入的上下文（contextType / contextId / title / metadata）生成一组
   * 与当前对象相关的推荐问题。若 contextId 命中真实灾害点，用库中名称替换标题，
   * 让推荐问题指向真实对象；否则退化为上下文标识。返回结构真实可序列化，
   * 并如实标注 {@code generatedBy=rule}（当前为规则生成，尚未接入 LLM）。
   */
  @PostMapping("/welcome-prompts")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> welcomePrompts(
      @RequestBody(required = false) Map<String, Object> context) {
    Map<String, Object> ctx = context == null ? Map.of() : context;
    String contextType = str(ctx.get("contextType"));
    String contextId = str(ctx.get("contextId"));
    String title = str(ctx.get("title"));

    // 数据驱动：上下文指向具体灾害点时，用真实库里的名称构造推荐问题
    String subject = title;
    if (isBlank(subject) && !isBlank(contextId)) {
      HazardPoint hazard = hazardRepository.findByHazardCode(contextId);
      if (hazard != null && !isBlank(hazard.getName())) {
        subject = hazard.getName();
      }
    }
    if (isBlank(subject)) {
      subject = isBlank(contextId) ? "当前态势" : contextId;
    }
    String type = isBlank(contextType) ? "overview" : contextType;

    List<Map<String, Object>> prompts = new ArrayList<>();
    prompts.add(prompt("risk", "分析「" + subject + "」当前的风险等级和主要致灾因子"));
    prompts.add(prompt("detail", "查看「" + subject + "」的详细信息与最新监测数据"));
    prompts.add(prompt("forecast", "「" + subject + "」未来 24 小时的风险趋势如何"));
    prompts.add(prompt("plan", "针对「" + subject + "」推荐对应的应急预案与处置步骤"));
    prompts.add(prompt("report", "为「" + subject + "」生成一份综合研判报告"));

    List<String> quickPrompts = prompts.stream()
        .map(p -> String.valueOf(p.get("text")))
        .toList();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("welcomeText", "你好，我是地脉智巡 AI 助手，正在关注「" + subject + "」。你可以直接问我：");
    data.put("quickPrompts", quickPrompts);
    data.put("prompts", prompts);
    data.put("contextType", type);
    data.put("contextId", contextId);
    data.put("count", prompts.size());
    data.put("generatedAt", OffsetDateTime.now(ZoneOffset.UTC).format(ISO));
    data.put("generatedBy", "rule");
    return ApiResponse.success(data);
  }

  private Map<String, Object> prompt(String intent, String text) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("intent", intent);
    p.put("text", text);
    return p;
  }

  private String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  private boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * 提交 AI 反馈：值班员/系统对照实际事件，给 AI 判定打分。
   *
   * <p>v1.4.0 新增。Java 端只做代理透传；Python ai-service 存到 FeedbackStore，
   * 后续可触发 train_xgboost.py 重新训练。
   *
   * <p>2026-11-05：若请求未带 factors，则按 hazardCode 从 device_metric 取各指标
   * 最新值自动填充。ai-service 的训练集只接受带 factors 的样本，不填的话
   * "人工复核结论"永远进不了训练，系统也就永远没有真实训练数据。
   */
  @PostMapping("/feedback")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> submitFeedback(@RequestBody FeedbackRequestDto request) {
    try {
      FeedbackRequestDto enriched = request;
      boolean needFactors = request.factors() == null || request.factors().isEmpty();
      if (needFactors && request.hazardCode() != null && !request.hazardCode().isBlank()) {
        Map<String, Double> factors = new HashMap<>();
        for (var metric : metricRepository.findLatestByHazardCode(request.hazardCode())) {
          if (metric.getMetricCode() != null && metric.getValue() != null) {
            factors.putIfAbsent(metric.getMetricCode(), metric.getValue());
          }
        }
        if (!factors.isEmpty()) {
          enriched = new FeedbackRequestDto(
              request.hazardCode(), request.predictedLevel(), request.predictedScore(),
              request.predictedAt(), request.outcome(), request.occurred(), request.confidence(),
              request.feedbackBy(), request.note(), factors);
          log.info("AI 反馈自动附加指标快照 hazard={} metrics={}", request.hazardCode(), factors.keySet());
        } else {
          log.warn("AI 反馈未附带特征且设备指标为空 hazard={}，该样本只能用于评估、不参与训练",
              request.hazardCode());
        }
      }
      Map<String, Object> resp = client.submitFeedback(enriched);
      return ApiResponse.success(resp);
    } catch (Exception e) {
      log.warn("提交 AI 反馈失败: {}", e.getMessage());
      return ApiResponse.fail(503, "AI 反馈提交不可用: " + e.getMessage());
    }
  }

  // ============================================================ //
  // ============================================================ //

  /**
   * 当前 XGBoost fusion 模型状态 + 训练指标 + 特征重要性。
   * 用于前端展示「真实模型/启发式」徽标 + 训练时间 + 模型版本。
   */
  @GetMapping("/model/info")
  @PreAuthorize("hasAuthority('ai:insight:view')")
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
  @PreAuthorize("hasAuthority('ai:insight:view')")
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
   * AI 微服务健康探活端点（前端 healthService.js 每 30 秒 ping 一次）。
   *
   * <p>逻辑：
   * <ul>
   *   <li>AI 功能关闭（{@code ai.enabled=false}）→ 直接返回 {@code {status: DISABLED}} + 200</li>
   *   <li>AI 开启 + ai-service 可达 → 透传其健康结果</li>
   *   <li>AI 开启 + ai-service 不可达 → 返回 {@code {status: DEGRADED}} + 200（前端三态机会降到 degraded）</li>
   * </ul>
   *
   * <p>故意不返回 5xx —— 前端探活逻辑基于"连续失败次数"判定三态，5xx 会导致误判。
   * 真实失败信息放在 {@code detail} 字段供运维排查。
   */
  @GetMapping("/health")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> health() {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("service", "disasterguard-ai");
    body.put("checkedAt", java.time.OffsetDateTime.now().toString());
    if (!client.isEnabled()) {
      body.put("status", "DISABLED");
      body.put("detail", "ai.enabled=false，前端使用纯规则模式");
      return ApiResponse.success("ok", body);
    }
    try {
      Map<String, Object> model = client.modelInfo();
      body.put("status", "UP");
      body.put("detail", "ai-service reachable");
      body.put("model", model);
      return ApiResponse.success("ok", body);
    } catch (Exception e) {
      log.debug("AI health: ai-service 不可达: {}", e.getMessage());
      body.put("status", "DEGRADED");
      body.put("detail", e.getMessage() == null ? "ai-service unreachable" : e.getMessage());
      return ApiResponse.success("degraded", body);
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

  // ============================================================ //
  // ============================================================ //

  /**
   * 列出所有模型（来自 {@code ai_model} 真实注册表）。
   * GET /api/ai/models
   */
  @GetMapping("/models")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> listModels() {
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    for (AiModel m : modelRepository.findByOrderByIdAsc()) {
      items.add(toModelEntry(m));
    }
    java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("items", items);
    result.put("total", items.size());
    result.put("dataSource", "ai_model（真实库）");
    return ApiResponse.success(result);
  }

  /**
   * 单个模型详情（含真实评估指标与配置）。
   * GET /api/ai/models/{versionId}
   *
   * <p>{@code versionId} 支持两种形式：模型编码（如 {@code xgboost_fusion}）或数据库 id。
   * 特征重要性 / 训练曲线不在 {@code ai_model} 表中（需模型文件解析），
   * 因此如实返回 {@code available:false}，不再编造 10 条特征权重。
   */
  @GetMapping("/models/{versionId}")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> getModelDetail(@PathVariable String versionId) {
    AiModel m = findModel(versionId);
    if (m == null) {
      return ApiResponse.fail(404, "模型不存在: " + versionId);
    }
    java.util.LinkedHashMap<String, Object> item = new java.util.LinkedHashMap<>();
    item.put("versionId", m.getModelCode());
    item.put("family", m.getModelCode());
    item.put("name", m.getModelName());
    item.put("version", m.getVersion());
    item.put("modelType", m.getModelType());
    item.put("status", m.getStatus());
    item.put("description", m.getDescription());
    item.put("deployedAt", m.getDeployedAt());
    item.put("updatedAt", m.getUpdatedAt());
    item.put("metrics", m.getMetrics() == null ? Map.of() : m.getMetrics());
    item.put("config", m.getConfig() == null ? Map.of() : m.getConfig());

    // 表里没有的：如实标注不可得
    item.put("featureImportance", Map.of("available", false));
    item.put("trainingCurve", Map.of("available", false));
    return ApiResponse.success(item);
  }

  /**
   * 模型统计：按真实 {@code model_type} 分组聚合。
   * GET /api/ai/models/stats
   */
  @GetMapping("/models/stats")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> modelStats() {
    List<AiModel> all = modelRepository.findByOrderByIdAsc();
    Map<String, List<AiModel>> byType = new java.util.LinkedHashMap<>();
    for (AiModel m : all) {
      byType.computeIfAbsent(m.getModelType(), k -> new java.util.ArrayList<>()).add(m);
    }
    List<Map<String, Object>> families = new java.util.ArrayList<>();
    for (Map.Entry<String, List<AiModel>> e : byType.entrySet()) {
      List<AiModel> ms = e.getValue();
      // 优先取 ONLINE 的作为 activeVersion
      AiModel active = ms.stream()
          .filter(m -> "ONLINE".equalsIgnoreCase(m.getStatus()))
          .findFirst()
          .orElse(ms.get(0));
      Map<String, Object> f = new java.util.LinkedHashMap<>();
      f.put("family", e.getKey());
      f.put("modelName", active.getModelName());
      f.put("activeVersion", active.getVersion());
      f.put("latestAccuracy", metricOf(active, "accuracy"));
      f.put("modelCount", ms.size());
      families.add(f);
    }
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("families", families);
    result.put("totalModels", all.size());
    result.put("dataSource", "ai_model（真实库聚合）");
    return ApiResponse.success(result);
  }

  /**
   * 列出训练任务。
   * GET /api/ai/models/training-jobs
   *
   * <p>诚实说明：库里没有训练任务表（{@code retrain_record} 为空且语义为"重训记录"），
   * 因此如实返回空列表，不再编造"2026-09-01 训练完成，准确率 0.923"这类记录。
   */
  @GetMapping("/models/training-jobs")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> listTrainingJobs() {
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("items", List.of());
    result.put("total", 0);
    result.put("available", false);
    result.put("message", "训练任务记录暂未落库（缺训练任务表），因此无历史任务可展示");
    return ApiResponse.success(result);
  }

  /** 查模型：先按 model_code，再按 id。 */
  private AiModel findModel(String versionId) {
    if (versionId == null || versionId.isBlank()) {
      return null;
    }
    AiModel byCode = modelRepository.findByModelCode(versionId).orElse(null);
    if (byCode != null) {
      return byCode;
    }
    try {
      return modelRepository.findById(Long.parseLong(versionId)).orElse(null);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /** 从 metrics JSONB 取指定指标（Number）。 */
  private Double metricOf(AiModel m, String key) {
    if (m.getMetrics() == null) {
      return null;
    }
    Object v = m.getMetrics().get(key);
    return v instanceof Number n ? n.doubleValue() : null;
  }

  /** 实体 → 列表项 DTO。 */
  private Map<String, Object> toModelEntry(AiModel m) {
    Map<String, Object> e = new java.util.LinkedHashMap<>();
    e.put("versionId", m.getModelCode());
    e.put("family", m.getModelCode());
    e.put("name", m.getModelName());
    e.put("version", m.getVersion());
    e.put("modelType", m.getModelType());
    e.put("status", m.getStatus());
    e.put("description", m.getDescription());
    e.put("deployedAt", m.getDeployedAt());
    Map<String, Object> metrics = m.getMetrics() == null ? Map.of() : m.getMetrics();
    e.put("metrics", metrics);
    e.put("accuracy", metrics.get("accuracy"));
    e.put("precision", metrics.get("precision"));
    e.put("recall", metrics.get("recall"));
    // 表里指标名是 f1，前端契约用 f1Score 时做一次映射
    e.put("f1Score", metrics.get("f1"));
    return e;
  }

  /**
   * 创建训练任务。
   * POST /api/ai/models/training-jobs
   *
   * <p>诚实说明：无训练任务表，且未接入训练调度器，因此不落库、不返回假的 job id，
   * 明确返回 {@code available:false} 说明当前不支持下发起训练。
   */
  @PostMapping("/models/training-jobs")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> createTrainingJob(@RequestBody Map<String, Object> request) {
    return ApiResponse.fail(501,
        "训练任务下发尚未实现：无训练任务表与训练调度器，暂不支持创建训练任务");
  }

  /**
   * 开始训练任务。
   * POST /api/ai/models/training-jobs/{id}/start
   *
   * <p>诚实说明：无训练任务表与训练调度器，返回 501，不再假装"启动成功"。
   */
  @PostMapping("/models/training-jobs/{id}/start")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> startTrainingJob(@PathVariable Long id) {
    return ApiResponse.fail(501, "训练任务调度尚未实现（任务 " + id + "）");
  }

  /**
   * 停止训练任务。
   * POST /api/ai/models/training-jobs/{id}/stop
   *
   * <p>诚实说明：无训练任务表与训练调度器，返回 501，不再假装"停止成功"。
   */
  @PostMapping("/models/training-jobs/{id}/stop")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> stopTrainingJob(@PathVariable Long id) {
    return ApiResponse.fail(501, "训练任务调度尚未实现（任务 " + id + "）");
  }

  /**
   * 训练任务日志。
   * GET /api/ai/models/training-jobs/{id}/log
   *
   * <p>诚实说明：无训练日志来源，如实返回空列表 + {@code available:false}，
   * 不再编造 6 条形如"第 100 轮训练完成 loss: 0.1823"的假日志。
   */
  @GetMapping("/models/training-jobs/{id}/log")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> trainingJobLog(
      @PathVariable Long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int size) {
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("items", List.of());
    result.put("total", 0);
    result.put("page", page);
    result.put("size", size);
    result.put("available", false);
    result.put("message", "训练日志暂不可得（未接入训练调度器，无日志来源）");
    return ApiResponse.success(result);
  }

  /**
   * 激活模型版本（改为真实更新 {@code ai_model.status}）。
   * POST /api/ai/models/{versionId}/activate
   */
  @PostMapping("/models/{versionId}/activate")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> activateModelByPath(@PathVariable String versionId) {
    AiModel m = findModel(versionId);
    if (m == null) {
      return ApiResponse.fail(404, "模型不存在: " + versionId);
    }
    m.setStatus("ONLINE");
    m.setDeployedAt(OffsetDateTime.now());
    m.setUpdatedAt(OffsetDateTime.now());
    modelRepository.save(m);
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("versionId", m.getModelCode());
    result.put("version", m.getVersion());
    result.put("status", m.getStatus());
    result.put("deployedAt", m.getDeployedAt());
    return ApiResponse.success("模型已上线", result);
  }

  /**
   * 社交媒体文本分析（情感 / 告警等级 / 关键词 / 位置聚类 / 时间线）。
   *
   * <p>POST /api/ai/social-media/analyze。请求体透传至 ai-service：
   * <pre>
   * { "texts": [{"content":"...","source":"weibo","timestamp":"..."}],
   *   "bbox": [minLon,minLat,maxLon,maxLat],   // 可选
   *   "time_range_hours": 24 }                 // 可选
   * </pre>
   * 返回体带 {@code mock} 布尔位，前端据此显示「演示数据」标识。
   */
  @PostMapping("/social-media/analyze")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> analyzeSocialMedia(@RequestBody Map<String, Object> body) {
    // ai-service 的 texts 为 List[TextItem]，元素必须是对象（含 content 等字段）。
    // 前端/调用方可能只传裸字符串数组，这里统一归一化为 {"content": ...}，避免下游 422。
    Object rawTexts = body.get("texts");
    if (!(rawTexts instanceof List<?> rawList) || rawList.isEmpty()) {
      return ApiResponse.fail(400, "texts 不能为空");
    }
    List<Map<String, Object>> texts = new ArrayList<>();
    for (Object item : rawList) {
      if (item instanceof String s) {
        if (!s.isBlank()) {
          Map<String, Object> t = new LinkedHashMap<>();
          t.put("content", s);
          texts.add(t);
        }
      } else if (item instanceof Map<?, ?> m) {
        Object content = m.get("content");
        if (content != null && !String.valueOf(content).isBlank()) {
          @SuppressWarnings("unchecked")
          Map<String, Object> t = (Map<String, Object>) m;
          texts.add(t);
        }
      }
    }
    if (texts.isEmpty()) {
      return ApiResponse.fail(400, "texts 中没有有效内容");
    }
    List<Double> bbox = null;
    Object rawBbox = body.get("bbox");
    if (rawBbox instanceof List<?> list && !list.isEmpty()) {
      bbox = new ArrayList<>();
      for (Object o : list) {
        if (o instanceof Number n) {
          bbox.add(n.doubleValue());
        }
      }
    }
    Integer hours = null;
    Object rawHours = body.get("time_range_hours");
    if (rawHours instanceof Number n) {
      hours = n.intValue();
    }
    return ApiResponse.success(client.analyzeSocialMedia(texts, bbox, hours));
  }

  /**
   * 激活模型版本（请求体形式 — 旧路径兼容，改为真实更新库）。
   * POST /api/ai/models/activate
   */
  @PostMapping("/models/activate")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Map<String, Object>> activateModel(@RequestBody Map<String, Object> request) {
    String versionId = request == null ? null : (String) request.get("versionId");
    if (versionId == null || versionId.isBlank()) {
      return ApiResponse.fail(400, "缺少 versionId");
    }
    AiModel m = findModel(versionId);
    if (m == null) {
      return ApiResponse.fail(404, "模型不存在: " + versionId);
    }
    m.setStatus("ONLINE");
    m.setDeployedAt(OffsetDateTime.now());
    m.setUpdatedAt(OffsetDateTime.now());
    modelRepository.save(m);
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("versionId", m.getModelCode());
    result.put("version", m.getVersion());
    result.put("status", m.getStatus());
    return ApiResponse.success("模型已上线", result);
  }

  /**
   * 删除模型（真实删除 {@code ai_model} 记录；ONLINE 状态的不允许删除）。
   * DELETE /api/ai/models/{versionId}
   */
  @DeleteMapping("/models/{versionId}")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ApiResponse<Void> deleteModel(@PathVariable String versionId) {
    AiModel m = findModel(versionId);
    if (m == null) {
      return ApiResponse.fail(404, "模型不存在: " + versionId);
    }
    if ("ONLINE".equalsIgnoreCase(m.getStatus())) {
      return ApiResponse.fail(400, "无法删除在线状态的模型，请先下线");
    }
    modelRepository.delete(m);
    return ApiResponse.success("删除成功", null);
  }
}
