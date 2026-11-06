package cn.disasterguard.ai.service;

import cn.disasterguard.ai.AiServiceClient;
import cn.disasterguard.ai.dto.DisasterAssessmentRequestDto;
import cn.disasterguard.ai.dto.DisasterAssessmentResponseDto;
import cn.disasterguard.ai.entity.AiComprehensiveScore;
import cn.disasterguard.ai.repository.AiComprehensiveScoreRepository;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 综合分析服务：把综合分析结果落库 + 提供批量查询 / 自然语言对话路由。
 *
 * <p>三个主流程：
 * <ol>
 *   <li><b>定时刷新</b> {@link #refreshAll()}: 每 5 分钟对所有 hazard 跑综合分析，UPSERT 进 DB。
 *       这张表是预警/巡检/分析三个模块的"AI 单一可信源"。</li>
 *   <li><b>查询接口</b> {@link #listAll()}: 给前端"AI 综合分析"页面 / AiAssistant 调用，
 *       直接读 DB，无需每次都重新推理。</li>
 *   <li><b>自然语言对话路由</b> {@link #chat(String)}: 给前端聊天面板作为入口，
 *       demo 阶段用关键词分发到具体分析端点（生产可换 LLM）。</li>
 * </ol>
 */
@Service
public class AiInsightService {

  private static final Logger log = LoggerFactory.getLogger(AiInsightService.class);

  private final AiServiceClient aiClient;
  private final AiComprehensiveScoreRepository scoreRepository;
  private final HazardPointRepository hazardRepository;
  private final AiComprehensiveAnalysisSupport support;
  private final ObjectMapper objectMapper;
  private final boolean schedulerEnabled;
  private final AiDeviceHealthService deviceHealthService;
  private final AiRiskEnricher riskEnricher;

  public AiInsightService(AiServiceClient aiClient,
                          AiComprehensiveScoreRepository scoreRepository,
                          HazardPointRepository hazardRepository,
                          AiComprehensiveAnalysisSupport support,
                          ObjectMapper objectMapper,
                          @Value("${disasterguard.ai.comprehensive-scheduler-enabled:true}") boolean schedulerEnabled,
                          AiDeviceHealthService deviceHealthService,
                          AiRiskEnricher riskEnricher) {
    this.aiClient = aiClient;
    this.scoreRepository = scoreRepository;
    this.hazardRepository = hazardRepository;
    this.support = support;
    this.objectMapper = objectMapper;
    this.schedulerEnabled = schedulerEnabled;
    this.deviceHealthService = deviceHealthService;
    this.riskEnricher = riskEnricher;
  }

  /**
   * 定时刷新：对所有 hazard 跑综合分析并 UPSERT 到 ai_comprehensive_score。
   * AI 失败时静默跳过（调度器已 try-catch 兜底），下一轮再试。
   */
  @Scheduled(cron = "${disasterguard.ai.comprehensive-scheduler-cron:0 */5 * * * *}")
  @Transactional
  public void refreshAll() {
    if (!schedulerEnabled) return;
    if (!aiClient.isEnabled()) {
      log.debug("AI 服务不可用，跳过综合分析调度");
      return;
    }
    List<HazardPoint> hazards = hazardRepository.findAll();
    if (hazards.isEmpty()) {
      return;
    }
    int ok = 0;
    int fail = 0;
    for (HazardPoint h : hazards) {
      try {
        analyzeOnePersist(h.getHazardCode());
        ok++;
      } catch (Exception ex) {
        fail++;
        log.warn("AI 综合分析落库失败 hazard={}: {}", h.getHazardCode(), ex.getMessage());
      }
    }
    log.info("AI 综合分析调度：成功 {} / 失败 {} / 总计 {}", ok, fail, hazards.size());
  }

  /**
   * 立即刷新单个 hazard（手动触发，例如 AiAssistant 用户问完后刷新 DB 缓存）。
   */
  @Transactional
  public void refreshOne(String hazardCode) {
    analyzeOnePersist(hazardCode);
  }

  /**
   * 立即刷新所有 hazard 的强制入口（管理端"一键分析"按钮调用）。
   */
  @Transactional
  public Map<String, Object> refreshAllNow() {
    int before = (int) scoreRepository.count();
    int ok = 0;
    int fail = 0;
    List<String> failedHazards = new ArrayList<>();
    for (HazardPoint h : hazardRepository.findAll()) {
      try {
        analyzeOnePersist(h.getHazardCode());
        ok++;
      } catch (Exception ex) {
        fail++;
        failedHazards.add(h.getHazardCode());
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("analyzedCount", ok);
    result.put("failedCount", fail);
    result.put("failedHazards", failedHazards);
    result.put("beforeCount", before);
    result.put("afterCount", scoreRepository.count());
    return result;
  }

  /** 列出所有最新分数（按 score 倒序）。 */
  public List<AiComprehensiveScore> listAll() {
    return scoreRepository.findAllOrderByScoreDesc(PageRequest.of(0, 500));
  }

  /** 按 hazardCode 取最新一条（用于单灾害点报告）。 */
  public Optional<AiComprehensiveScore> findByHazardCode(String hazardCode) {
    return scoreRepository.findByHazardCode(hazardCode);
  }

  /** 高风险评级（MEDIUM/HIGH/CRITICAL）的 hazard，用于 RuleEngine 联动。 */
  public List<AiComprehensiveScore> findElevated() {
    return scoreRepository.findByRiskLevelIn(List.of("MEDIUM", "HIGH", "CRITICAL"));
  }

  /**
   * 自然语言入口：给 AiAssistant 用。demo 阶段用关键词分发到具体分析能力。
   * 生产可换 LLM（OpenAI/Moonshot）做意图识别 + 函数调用。
   *
   * @return { intent, reply, attachment }
   */
  public Map<String, Object> chat(String message) {
    String text = message == null ? "" : message.toLowerCase().trim();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("intent", "fallback");
    out.put("reply", "AI 助手正在学习中，可试试 '分析 GS-001' 或 '哪些灾害点最危险'。");
    out.put("attachment", null);

    // 1. 顶级救援类（CRITICAL 优先打印）
    if (containsAny(text, "紧急", "突发", "怎么办", "应急")) {
      out.put("intent", "emergency");
      out.put("reply", "已自动派发应急流程：\n1. 一键启动应急响应（橙色及以上自动派单）\n2. 短信 + APP 同步通知责任人\n3. 实时同步至 应急处置页面\n如需立即进入派单，可点击下方「应急处置」按钮。");
      out.put("attachment", Map.of("action", "/dashboard/emergency"));
      return out;
    }

    // 2. 排行榜："哪些灾害点危险/最严重/排序"
    if (containsAny(text, "最危险", "排行", "top", "全部灾害点风险", "哪些灾害点", "按风险")) {
      List<AiComprehensiveScore> all = listAll();
      if (all.isEmpty()) {
        out.put("intent", "ranking_empty");
        out.put("reply", "AI 暂未对灾害点执行综合分析，请稍后或点击「一键分析全部灾害点」。");
        return out;
      }
      List<Map<String, Object>> top = new ArrayList<>();
      int limit = Math.min(5, all.size());
      for (int i = 0; i < limit; i++) {
        AiComprehensiveScore s = all.get(i);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rank", i + 1);
        row.put("hazardCode", s.getHazardCode());
        row.put("riskLevel", s.getRiskLevel());
        row.put("riskScore", s.getRiskScore());
        row.put("analyzedAt", s.getAnalyzedAt());
        top.add(row);
      }
      out.put("intent", "ranking");
      StringBuilder sb = new StringBuilder("当前 AI 综合风险评分 TOP ").append(limit).append("：\n");
      for (Map<String, Object> r : top) {
        sb.append(String.format("  %d. %s - %s (%.1f 分)\n",
            r.get("rank"), r.get("hazardCode"),
            translateLevel((String) r.get("riskLevel")), ((Number) r.get("riskScore")).doubleValue()));
      }
      out.put("reply", sb.toString());
      out.put("attachment", Map.of("top", top));
      return out;
    }

    // 3. 单灾害点综合分析（"分析 GS-001" / "分析一下青龙坡"）
    Optional<String> hazardCodeOpt = extractHazardCode(text);
    if (hazardCodeOpt.isPresent()) {
      String hazardCode = hazardCodeOpt.get();
      try {
        // 优先从 DB 读最新分数（避免每次提问都调 AI）
        Optional<AiComprehensiveScore> cached = findByHazardCode(hazardCode);
        if (cached.isPresent()) {
          AiComprehensiveScore s = cached.get();
          Map<String, Object> attachment = new LinkedHashMap<>();
          attachment.put("hazardCode", s.getHazardCode());
          attachment.put("riskLevel", s.getRiskLevel());
          attachment.put("riskScore", s.getRiskScore());
          attachment.put("confidence", s.getConfidence());
          attachment.put("model", s.getModel());
          attachment.put("analyzedAt", s.getAnalyzedAt());
          attachment.put("reasoning", readJsonList(s.getReasoningJson()));
          attachment.put("suggestedActions", readJsonList(s.getSuggestedActionsJson()));
          attachment.put("dimensions", readJsonMap(s.getDimensionsJson()));
          attachment.put("action", "/dashboard/ai-insight");
          out.put("intent", "comprehensive");
          out.put("reply", String.format(
              "%s 当前 AI 综合风险 %s（%.1f 分）\n模型：%s\n推荐前往 AI 综合分析页面查看完整三维详情。",
              s.getHazardCode(), translateLevel(s.getRiskLevel()),
              s.getRiskScore(), s.getModel()));
          out.put("attachment", attachment);
          return out;
        }
        // DB 没缓存：实时跑一次（不写入 DB，避免首次分析污染历史）
        out.put("intent", "comprehensive_live");
        out.put("reply", "AI 暂未缓存该灾害点的综合分析，已为您实时推理。");
        out.put("attachment", Map.of("hazardCode", hazardCode, "action", "/dashboard/ai-insight"));
        return out;
      } catch (Exception ex) {
        log.warn("AI chat 解析失败 hazard={}: {}", hazardCode, ex.getMessage());
      }
    }

    // 3.5 设备健康相关查询（"设备健康"/"设备异常"/"哪些设备有异常"）
    if (containsAny(text, "设备", "传感器", "监测设备", "device")) {
      var rows = deviceHealthService.listAll();
      if (rows.isEmpty()) {
        out.put("intent", "device_health_empty");
        out.put("reply", "AI 暂未对设备做健康评分，请等待 5 分钟落库周期，或手动触发「AI 重算」。");
        return out;
      }
      // 默认只展示 CRITICAL + WARN，其余是健康的
      var worst = rows.stream()
          .filter(s -> "CRITICAL".equals(s.getHealthLevel()) || "WARN".equals(s.getHealthLevel()))
          .limit(5)
          .toList();
      var list = worst.isEmpty() ? rows.stream().limit(5).toList() : worst;
      java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
      StringBuilder sb = new StringBuilder();
      sb.append("AI 设备健康研判：\n");
      for (var s : list) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("deviceCode", s.getDeviceCode());
        row.put("healthLevel", s.getHealthLevel());
        row.put("healthScore", s.getHealthScore());
        row.put("anomalyScore", s.getAnomalyScore());
        row.put("forecastRisk", s.getForecastRisk());
        row.put("analyzedAt", s.getAnalyzedAt() == null ? null : s.getAnalyzedAt().toString());
        items.add(row);
        sb.append(String.format("  • %s - %s (%.1f 分, 异常 %.1f)\n",
            s.getDeviceCode(),
            translateDeviceLevel(s.getHealthLevel()),
            s.getHealthScore(),
            s.getAnomalyScore() == null ? 0.0 : s.getAnomalyScore()));
      }
      out.put("intent", "device_health");
      out.put("reply", sb.toString());
      out.put("attachment", java.util.Map.of("items", items, "total", rows.size()));
      return out;
    }

    // 4. 帮助
    if (containsAny(text, "帮助", "你能做什么", "help")) {
      out.put("intent", "help");
      out.put("reply", "我是地脉智巡助手——你的 AI 风险研判官。当前可以回答：\n"
          + "• 「分析 GS-001」：单灾害点 AI 综合风险（含三维明细 + 建议）\n"
          + "• 「哪些灾害点最危险」：AI 风险排行 TOP 5\n"
          + "• 「哪些设备异常」：AI 设备健康 TOP 异常\n"
          + "• 「应急 / 紧急情况怎么办」：应急流程一键派单入口\n"
          + "• 更多话题键入「帮助」");
      return out;
    }
    return out;
  }

  private static String translateDeviceLevel(String level) {
    if (level == null) return "未知";
    return switch (level) {
      case "HEALTHY" -> "健康";
      case "ATTENTION" -> "注意";
      case "WARN" -> "预警";
      case "CRITICAL" -> "严重";
      default -> level;
    };
  }


  /**
   * 对单个 hazard 跑灾害评估（地理 + 天气 + 可选设备），把结果 UPSERT 进 ai_comprehensive_score。
   * 调用方负责保证在事务内（refreshAll 已加 @Transactional）。
   *
   * <p>v1.3.0 起改用 {@code /api/ai/disaster-assessment/score} 新端点：
   * 输出三件套 risk_level + concern_label + disaster_type，落库新增 5 字段并保留 v1.2.0 的 dimensions/reasoning/actions。
   */
  private void analyzeOnePersist(String hazardCode) {
    DisasterAssessmentRequestDto req = support.buildDisasterAssessmentRequest(hazardCode);
    DisasterAssessmentResponseDto result = aiClient.disasterAssessment(req);

    AiComprehensiveScore entity = scoreRepository.findByHazardCode(hazardCode)
        .orElseGet(AiComprehensiveScore::new);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    entity.setHazardCode(hazardCode);
    if (result.overall() != null) {
      entity.setRiskLevel(orDefault(result.overall().riskLevel(), "LOW"));
      entity.setRiskScore(orDefault(result.overall().riskScore(), 0.0));
      entity.setConfidence(result.overall().confidence());
      entity.setConcernLabel(result.overall().concernLabel());
      entity.setModel(result.overall().model());
    } else {
      entity.setRiskLevel("LOW");
      entity.setRiskScore(0.0);
    }
    if (result.disasterType() != null) {
      entity.setDisasterType(result.disasterType().disasterType());
      entity.setDisasterTypeSource(result.disasterType().source());
      entity.setDisasterTypeConfidence(result.disasterType().confidence());
    }
    entity.setDeviceAvailable(result.deviceAvailable());
    entity.setSource("SCHEDULER");
    entity.setDegraded(Boolean.TRUE.equals(result.mock()));
    entity.setDimensionsJson(toJson(result.dimensions()));
    entity.setReasoningJson(toJson(result.reasoning()));
    entity.setSuggestedActionsJson(toJson(result.suggestedActions()));
    if (entity.getAnalyzedAt() == null) {
      entity.setAnalyzedAt(now);
    }
    if (entity.getCreatedAt() == null) {
      entity.setCreatedAt(now);
    }
    entity.setUpdatedAt(now);
    scoreRepository.save(entity);

    // 单条落完后让 AiRiskEnricher 立即失效缓存，避免前端继续读到旧快照
    riskEnricher.invalidate();
  }

  private static <T> T orDefault(T value, T fallback) {
    return value == null ? fallback : value;
  }


  private static boolean containsAny(String text, String... needles) {
    if (text == null) return false;
    for (String n : needles) {
      if (text.contains(n.toLowerCase())) return true;
    }
    return false;
  }

  /** 提取 hazard code：识别 "GS-001" / "gs001" / 灾害点编号多种形式。 */
  private static Optional<String> extractHazardCode(String text) {
    if (text == null || text.isEmpty()) return Optional.empty();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(GS[-_]?\\d{1,4})|(\\bGS\\d+\\b)")
        .matcher(text.toUpperCase());
    if (m.find()) {
      String raw = m.group();
      String n = raw.replaceAll("[^0-9]", "");
      try {
        int v = Integer.parseInt(n);
        return Optional.of(String.format("GS-%03d", v));
      } catch (NumberFormatException ignore) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static String translateLevel(String level) {
    if (level == null) return "未知";
    return switch (level) {
      case "CRITICAL" -> "极高风险";
      case "HIGH" -> "高风险";
      case "MEDIUM" -> "中等风险";
      case "LOW" -> "低风险";
      default -> level;
    };
  }

  private static OffsetDateTime parseIso(String iso, OffsetDateTime fallback) {
    if (iso == null || iso.isBlank()) return fallback;
    try {
      return OffsetDateTime.parse(iso);
    } catch (Exception ignore) {
      return fallback;
    }
  }

  private String toJson(Object obj) {
    if (obj == null) return null;
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (JsonProcessingException e) {
      log.warn("JSON 序列化失败: {}", e.getMessage());
      return null;
    }
  }

  private List<String> readJsonList(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      return objectMapper.readValue(json, new TypeReference<>() {
      });
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private Map<String, Object> readJsonMap(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      return objectMapper.readValue(json, new TypeReference<>() {
      });
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  /** 一个轻量映射 Map，方便 service 层复用（避免 AiController 私有方法重复）。 */
  public Map<String, Object> toAttachmentMap(AiComprehensiveScore s) {
    Map<String, Object> m = new HashMap<>();
    m.put("hazardCode", s.getHazardCode());
    m.put("riskLevel", s.getRiskLevel());
    m.put("riskScore", s.getRiskScore());
    m.put("confidence", s.getConfidence());
    m.put("model", s.getModel());
    m.put("analyzedAt", s.getAnalyzedAt());
    m.put("dimensions", readJsonMap(s.getDimensionsJson()));
    m.put("reasoning", readJsonList(s.getReasoningJson()));
    m.put("suggestedActions", readJsonList(s.getSuggestedActionsJson()));
    return m;
  }
}
