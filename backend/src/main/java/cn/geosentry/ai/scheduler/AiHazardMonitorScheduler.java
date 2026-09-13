package cn.geosentry.ai.scheduler;

import cn.geosentry.ai.AiServiceClient;
import cn.geosentry.hazard.dto.HazardDto;
import cn.geosentry.hazard.service.HazardService;
import cn.geosentry.realtime.RealtimeBroadcastService;
import cn.geosentry.region.entity.Region;
import cn.geosentry.region.repository.RegionRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 实时灾害监测调度器（v1.7.0 全国版）。
 *
 * <p>每 N 秒（默认 90s）跑一次：
 * <ol>
 *   <li>从 region 表动态加载"可监控"区域（town/village 级别且 geo_type 非 PLAIN）；</li>
 *   <li>按 geo_type 决定网格步长：MOUNTAIN=0.01° / HILL=0.02° / COASTAL=0.015° / 默认=0.02°；</li>
 *   <li>对每个 region 中心生成 3×3 网格点（中心 + 八方位），批量发给 Python AI 服务；</li>
 *   <li>Python 端返回 risk_level ≥ HIGH 的高风险点，Java 端调 HazardService.createFromAiDetection 落库；</li>
 *   <li>新创建的灾害通过 WebSocket 推送给前端。</li>
 * </ol>
 *
 * <p>可配置项（application.yml / 环境变量）：
 * <ul>
 *   <li>{@code geosentry.ai.hazard-monitor-enabled} — 是否启用（默认 true）</li>
 *   <li>{@code geosentry.ai.hazard-monitor-interval-ms} — 调度间隔（默认 90000ms）</li>
 *   <li>{@code geosentry.ai.hazard-monitor-initial-delay-ms} — 启动后首次执行延迟（默认 8000ms）</li>
 *   <li>{@code geosentry.ai.hazard-monitor-max-regions-per-tick} — 每次最多扫描 region 数（默认 200，防超载）</li>
 *   <li>{@code geosentry.ai.hazard-monitor-include-plain} — 是否包含 PLAIN 区域（默认 false）</li>
 *   <li>{@code geosentry.ai.hazard-monitor-min-level} — 最小行政级别过滤（默认 town）</li>
 *   <li>{@code geosentry.ai.hazard-monitor-min-risk-level} — AI 检测阈值（默认 HIGH）</li>
 * </ul>
 */
@Component
public class AiHazardMonitorScheduler {

  private static final Logger log = LoggerFactory.getLogger(AiHazardMonitorScheduler.class);

  /** geo_type → 网格步长（度）。值越小扫描越密但计算量越大。 */
  private static final Map<String, Double> GRID_STEP_BY_GEOTYPE = Map.of(
      "MOUNTAIN", 0.01,   // ~1.1 km
      "HILL",     0.02,   // ~2.2 km
      "COASTAL",  0.015   // ~1.6 km
  );
  private static final double DEFAULT_GRID_STEP = 0.02;

  private final AiServiceClient aiClient;
  private final HazardService hazardService;
  private final RealtimeBroadcastService broadcaster;
  private final RegionRepository regionRepository;

  private final boolean enabled;
  private final int maxRegionsPerTick;
  private final boolean includePlain;
  private final String minLevel;
  private final String minRiskLevel;

  public AiHazardMonitorScheduler(
      AiServiceClient aiClient,
      HazardService hazardService,
      RealtimeBroadcastService broadcaster,
      RegionRepository regionRepository,
      @Value("${geosentry.ai.hazard-monitor-enabled:true}") boolean enabled,
      @Value("${geosentry.ai.hazard-monitor-max-regions-per-tick:200}") int maxRegionsPerTick,
      @Value("${geosentry.ai.hazard-monitor-include-plain:false}") boolean includePlain,
      @Value("${geosentry.ai.hazard-monitor-min-level:town}") String minLevel,
      @Value("${geosentry.ai.hazard-monitor-min-risk-level:HIGH}") String minRiskLevel
  ) {
    this.aiClient = aiClient;
    this.hazardService = hazardService;
    this.broadcaster = broadcaster;
    this.regionRepository = regionRepository;
    this.enabled = enabled;
    this.maxRegionsPerTick = maxRegionsPerTick;
    this.includePlain = includePlain;
    this.minLevel = minLevel;
    this.minRiskLevel = minRiskLevel;
  }

  /** 主调度：每 N 秒跑一次，覆盖 region 监控点 + AI 评估 + 落库 + WS 推送。 */
  @Scheduled(fixedDelayString = "${geosentry.ai.hazard-monitor-interval-ms:90000}",
             initialDelayString = "${geosentry.ai.hazard-monitor-initial-delay-ms:8000}")
  public void monitor() {
    if (!enabled) {
      log.debug("AI 灾害监测调度已禁用（hazard-monitor-enabled=false）");
      return;
    }
    if (!aiClient.isEnabled()) {
      log.debug("AI 服务不可用，跳过灾害监测调度");
      return;
    }
    try {
      tick();
    } catch (Exception ex) {
      log.warn("AI 灾害监测 tick 异常: {}", ex.getMessage());
    }
  }

  /**
   * 实际跑一次（暴露为 public，方便 AiStartupWarmup 在启动时调用一次"冷启动出首批点"）。
   *
   * @return 本次执行结果统计 Map
   */
  public Map<String, Object> tick() {
    long t0 = System.currentTimeMillis();

    // Step 1: 从 DB 动态加载监控区域
    List<Region> regions = loadMonitorableRegions();
    if (regions.isEmpty()) {
      log.info("AI 灾害监测 tick：无有效监控区域（region 表为空或无符合条件记录）");
      return Map.of("ok", 0, "regions", 0, "reason", "no_regions");
    }

    // Step 2: 构造监控网格点（按 geo_type 决定步长）
    List<Map<String, Object>> points = buildGridPoints(regions);
    if (points.isEmpty()) {
      return Map.of("ok", 0, "regions", regions.size(), "reason", "no_points");
    }

    // Step 3: 当前天气快照（生产应从 weatherService/气象 API 拉）
    Map<String, Object> weather = currentWeatherSnapshot();

    // Step 4: 调用 AI 批量检测
    Map<String, Object> aiResp;
    try {
      aiResp = aiClient.detectHazardsBatch(points, minRiskLevel);
    } catch (Exception ex) {
      log.warn("AI 批量灾害检测调用失败: {}", ex.getMessage());
      return Map.of("ok", 0, "regions", regions.size(), "points", points.size(), "reason", ex.getMessage());
    }

    if (aiResp == null) {
      return Map.of("ok", 0, "regions", regions.size(), "points", points.size(), "reason", "ai_null");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> detections = (List<Map<String, Object>>) aiResp.getOrDefault("detections", List.of());

    int created = 0;
    int deduped = 0;
    List<HazardDto> broadcastQueue = new ArrayList<>();

    for (Map<String, Object> det : detections) {
      try {
        String regionCode = str(det.get("region_code"));
        String disasterType = str(det.get("hazard_type"));
        Double lon = num(det.get("lon"));
        Double lat = num(det.get("lat"));
        String riskLevel = translateAiRiskLevel(str(det.get("risk_level")));
        Double confidence = num(det.get("confidence"));
        if (confidence == null) confidence = num(det.get("detection_confidence"));

        HazardDto hazard = hazardService.createFromAiDetection(
            regionCode, disasterType, lon, lat, riskLevel, confidence
        );
        if (hazard == null) {
          deduped++;
          continue;
        }
        created++;
        broadcastQueue.add(hazard);
      } catch (Exception ex) {
        log.warn("AI 监测点入库失败 region={} type={}: {}",
            det.get("region_code"), det.get("hazard_type"), ex.getMessage());
      }
    }

    // Step 5: WebSocket 推送新建的灾害
    for (HazardDto h : broadcastQueue) {
      try {
        broadcaster.broadcastHazard(h);
      } catch (Exception ex) {
        log.warn("WebSocket 广播 AI 灾害失败 hazardCode={}: {}", h.hazardCode(), ex.getMessage());
      }
    }

    long elapsed = System.currentTimeMillis() - t0;
    log.info("AI 灾害监测 tick 完成: 监控 {} 个区域 / {} 网格点 / 发现 {} 高风险 / 入库 {} / 去重 {} / 耗时 {}ms",
        regions.size(), points.size(), detections.size(), created, deduped, elapsed);

    Map<String, Object> result = new HashMap<>();
    result.put("ok", created);
    result.put("deduped", deduped);
    result.put("monitored_regions", regions.size());
    result.put("grid_points", points.size());
    result.put("ai_detections", detections.size());
    result.put("elapsed_ms", elapsed);
    return result;
  }

  // ======================================================================== //
  // 私有辅助方法
  // ======================================================================== //

  /**
   * 从 region 表动态加载"可监控"区域。
   *
   * <p>过滤逻辑：
   * <ul>
   *   <li>行政级别 ≥ minLevel（默认 town，即 town/village）</li>
   *   <li>geo_type 非 PLAIN（除非 includePlain=true）</li>
   *   <li>最多取 maxRegionsPerTick 条（避免单次请求点数爆炸）</li>
   * </ul>
   */
  private List<Region> loadMonitorableRegions() {
    // 行政级别排序优先级：village > town > district > city > county > province
    List<String> levels;
    switch (minLevel) {
      case "village" -> levels = List.of("village");
      case "town"    -> levels = List.of("town", "village");
      case "district" -> levels = List.of("district", "town", "village");
      case "city"    -> levels = List.of("city", "district", "town", "village");
      default        -> levels = List.of("town", "village");
    }

    List<Region> all = regionRepository.findAll().stream()
        .filter(r -> levels.contains(r.getRegionLevel()))
        .filter(r -> includePlain || !"PLAIN".equals(r.getGeoType()))
        .limit(maxRegionsPerTick)
        .toList();

    log.debug("加载监控区域: 过滤条件 level={} includePlain={} → 共 {} 个",
        minLevel, includePlain, all.size());
    return all;
  }

  /**
   * 按 region 中心坐标生成 3×3 网格点列表。
   *
   * <p>每个 region 生成 9 个点（中心 + 八方位），步长由 geo_type 决定。
   * 输出格式与 Python detect-batch 端点对齐：lon / lat / region_code / weather。
   */
  private List<Map<String, Object>> buildGridPoints(List<Region> regions) {
    List<Map<String, Object>> points = new ArrayList<>();
    Map<String, Object> weather = currentWeatherSnapshot();

    for (Region r : regions) {
      double step = GRID_STEP_BY_GEOTYPE.getOrDefault(r.getGeoType(), DEFAULT_GRID_STEP);
      double cx = r.getCenterLon() != null ? r.getCenterLon() : 105.0;
      double cy = r.getCenterLat() != null ? r.getCenterLat() : 35.0;
      String code = r.getRegionCode();

      for (double dx : List.of(-step, 0.0, step)) {
        for (double dy : List.of(-step, 0.0, step)) {
          points.add(Map.of(
              "lon", round6(cx + dx),
              "lat", round6(cy + dy),
              "region_code", code,
              "region_name", r.getRegionName(),
              "geo_type", r.getGeoType(),
              "grid_step_deg", step,
              "weather", weather
          ));
        }
      }
    }
    return points;
  }

  /**
   * 当前天气快照。生产应从 weatherService / 中央气象台 API 实时拉取。
   * v1.7.0 demo：用季节性模拟值 + 随机扰动，保证 AI 模型能稳定跑出变化结果。
   */
  private Map<String, Object> currentWeatherSnapshot() {
    long dayOfYear = OffsetDateTime.now().getDayOfYear();
    // 春夏季（4-9 月）多雨，冬季干燥
    double seasonal = Math.sin((dayOfYear - 80) / 365.0 * 2 * Math.PI);
    double rain24h = seasonal > 0 ? 25.0 + seasonal * 80 : seasonal * 15;
    double rain7d = seasonal > 0 ? 80.0 + seasonal * 220 : seasonal * 50;
    Map<String, Object> w = new HashMap<>();
    w.put("rainfall_24h", Math.max(0.0, rain24h));
    w.put("rainfall_7d", Math.max(0.0, rain7d));
    w.put("temperature", 22.0 + seasonal * 8);
    w.put("humidity", 0.65 + seasonal * 0.15);
    return w;
  }

  /** 把 AI 端 LOW/MEDIUM/HIGH/CRITICAL 翻译成业务显示的"高风险 / 关注 / 正常"。 */
  private static String translateAiRiskLevel(String aiLevel) {
    if (aiLevel == null) return "关注";
    return switch (aiLevel.toUpperCase()) {
      case "CRITICAL", "HIGH" -> "高风险";
      case "MEDIUM" -> "关注";
      case "LOW" -> "正常";
      default -> aiLevel;
    };
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  private static Double num(Object v) {
    if (v == null) return null;
    if (v instanceof Number n) return n.doubleValue();
    try {
      return Double.parseDouble(String.valueOf(v));
    } catch (Exception ignore) {
      return null;
    }
  }

  private static double round6(double v) {
    return Math.round(v * 1_000_000.0) / 1_000_000.0;
  }
}
