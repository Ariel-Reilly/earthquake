package cn.disasterguard.warning.engine;

import cn.disasterguard.ai.AiServiceClient;
import cn.disasterguard.ai.dto.FusionRiskResult;
import cn.disasterguard.ai.service.AiAnalysisService;
import cn.disasterguard.warning.entity.RiskScoreLog;
import cn.disasterguard.warning.entity.WarningEvent;
import cn.disasterguard.warning.repository.RiskScoreLogRepository;
import cn.disasterguard.warning.repository.WarningEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预警创建服务：独立于 RuleEngineScheduler，通过 Spring 代理生效 @Transactional。
 *
 * <p>两阶段设计：
 * <ol>
 *   <li>AI 融合评分（事务外）：HTTP 调用 Python AI 服务，保存分析记录。
 *       此阶段不依赖 DB 主事务，AI 失败不影响后续规则级预警创建。</li>
 *   <li>DB 写入（事务内）：创建 warning_event + risk_score_log，在同一事务保证一致性。</li>
 * </ol>
 *
 * <p>RuleEngineScheduler 内部同类调用 {@code this.createWarning()} 会绕过 AOP 代理，
 * 导致事务不生效。将此逻辑抽到此独立 Service，由 Scheduler 通过注入调用，
 * 保证 warning 主记录保存失败时 risk_score_log 不会成为孤儿。
 */
@Service
public class WarningCreateService {

  private static final Logger log = LoggerFactory.getLogger(WarningCreateService.class);

  private final WarningEventRepository warningRepository;
  private final RiskScoreLogRepository riskScoreLogRepository;
  private final AiServiceClient aiClient;
  private final AiAnalysisService aiAnalysisService;
  private final ObjectMapper objectMapper;

  public WarningCreateService(WarningEventRepository warningRepository,
                              RiskScoreLogRepository riskScoreLogRepository,
                              AiServiceClient aiClient,
                              AiAnalysisService aiAnalysisService,
                              ObjectMapper objectMapper) {
    this.warningRepository = warningRepository;
    this.riskScoreLogRepository = riskScoreLogRepository;
    this.aiClient = aiClient;
    this.aiAnalysisService = aiAnalysisService;
    this.objectMapper = objectMapper;
  }

  /**
   * 创建预警事件 + AI 融合评分日志（两阶段设计）。
   *
   * <p>阶段 1：AI 融合评分（在事务外执行，避免 AI/DB 任一故障导致两败俱伤）。
   * <p>阶段 2：DB 写入（在 @Transactional 内，保证主记录与风险日志原子性）。
   *
   * <p>risk_score_log 依赖 warning_event 的主键，因此 warning 先保存，
   * 若 riskLog 写入失败则整个事务回滚，warning 也不会落库。
   */
  public void createWarning(String ruleCode, String ruleName, String hazardCode,
                            String warningLevel, String metricCode, double triggerValue,
                            Map<String, Double> metrics) {

    // ====== 阶段 1：AI 融合评分（事务外，AI 异常安全降级）======
    String title = ruleName == null ? ruleCode : ruleName;
    String level = warningLevel == null ? "黄色预警" : warningLevel;
    boolean aiAvailable = false;
    FusionRiskResult fusion = null;
    Map<String, Double> aiFactors = new HashMap<>(metrics);
    String aiSource = "RULE_ONLY";
    String finalLevel = level;

    if (aiClient.isEnabled()) {
      try {
        fusion = aiClient.fusionRisk(hazardCode, aiFactors);
        aiAnalysisService.saveRecord("fusion", hazardCode, fusion.model(), fusion);
        aiAvailable = true;
        if (!fusion.degraded()) {
          switch (fusion.riskLevel()) {
            case "CRITICAL" -> finalLevel = "红色预警";
            case "HIGH" -> finalLevel = "橙色预警";
            case "MEDIUM" -> {
              if (level.contains("蓝")) finalLevel = "黄色预警";
            }
            default -> {}
          }
          aiSource = "AI_AUTO";
        } else {
          aiSource = "AI_DEGRADED";
        }
      } catch (Exception e) {
        log.warn("AI 融合评分不可用，沿用规则等级 hazard={}: {}", hazardCode, e.getMessage());
        // AI 降级：继续用规则等级创建预警（阶段 2），不丢失预警
      }
    }

    // ====== 阶段 2：DB 写入（在独立事务中，保证原子性）======
    try {
      doPersist(ruleCode, hazardCode, title, finalLevel, metricCode, triggerValue,
          aiSource, aiAvailable, fusion, aiFactors);
    } catch (Exception e) {
      log.warn("预警 DB 写入失败 ruleCode={}, hazardCode={}: {}", ruleCode, hazardCode, e.getMessage());
    }
  }

  /** 纯 DB 写入事务：创建 warning_event + risk_score_log（两表原子写入）。 */
  @Transactional
  public void doPersist(String ruleCode, String hazardCode, String title, String level,
                        String metricCode, double triggerValue, String source,
                        boolean aiAvailable, FusionRiskResult fusion, Map<String, Double> factors) {

    WarningEvent event = new WarningEvent();
    event.setWarningCode("WG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    event.setRuleCode(ruleCode);
    event.setHazardCode(hazardCode);
    event.setTitle(title);
    event.setWarningLevel(level);
    event.setTriggerValue(metricCode + "=" + triggerValue);
    event.setStatus("待确认");
    event.setSource(source);
    event.setTriggeredAt(OffsetDateTime.now());

    warningRepository.save(event);

    // risk_score_log：无论 AI 是否可用都写一条，保证后续 AI 分析可追溯
    RiskScoreLog riskLog;
    if (aiAvailable && fusion != null) {
      riskLog = buildRiskLog(hazardCode, fusion, factors);
    } else {
      riskLog = new RiskScoreLog();
      riskLog.setHazardCode(hazardCode);
      riskLog.setScore(0.0);
      riskLog.setRiskLevel("RULE_ONLY");
      riskLog.setSource("RULE_ONLY");
      riskLog.setFactors("{\"degraded\":true,\"reason\":\"ai-unavailable\"}");
      riskLog.setCalculatedAt(OffsetDateTime.now());
    }
    riskScoreLogRepository.save(riskLog);

    log.info("规则 [{}] 触发 -> 生成预警(待人工复核) hazard={}, value={}, finalLevel={}, source={}",
        ruleCode, hazardCode, triggerValue, level, source);
  }

  private RiskScoreLog buildRiskLog(String hazardCode, FusionRiskResult fusion, Map<String, Double> factors) {
    RiskScoreLog riskLog = new RiskScoreLog();
    riskLog.setHazardCode(hazardCode);
    riskLog.setScore(fusion.riskScore());
    riskLog.setRiskLevel(fusion.riskLevel());
    riskLog.setSource(fusion.degraded() ? "AI_DEGRADED" : "AI_FUSED");
    try {
      riskLog.setFactors(objectMapper.writeValueAsString(
          Map.of("input", factors, "confidence", fusion.confidence(), "degraded", fusion.degraded())));
    } catch (JsonProcessingException ignore) {
      log.warn("factors 序列化失败，risk_score_log 写空对象 hazardCode={}: {}", hazardCode, ignore.getMessage());
      riskLog.setFactors("{}");
    }
    riskLog.setCalculatedAt(OffsetDateTime.now());
    return riskLog;
  }
}
