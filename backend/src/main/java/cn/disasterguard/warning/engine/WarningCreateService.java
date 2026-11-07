package cn.disasterguard.warning.engine;

import cn.disasterguard.ai.AiServiceClient;
import cn.disasterguard.ai.dto.FusionRiskResult;
import cn.disasterguard.ai.service.AiAnalysisService;
import cn.disasterguard.event.service.EventStateService;
import cn.disasterguard.realtime.RealtimeBroadcastService;
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
 *
 * <p>⚠️ R10 P1-2：光抽出独立 Service 还不够——{@code createWarning()} 调用
 * 同一个类里的 {@code doPersist()} 依然是 this 调用、依然绕过代理。现在改为
 * 注入自身代理 {@code self} 后再调，事务边界才真正成立（有单元测试与日志佐证）。
 */
@Service
public class WarningCreateService {

  private static final Logger log = LoggerFactory.getLogger(WarningCreateService.class);

  private final WarningEventRepository warningRepository;
  private final RiskScoreLogRepository riskScoreLogRepository;
  private final AiServiceClient aiClient;
  private final AiAnalysisService aiAnalysisService;
  private final ObjectMapper objectMapper;
  private final EventStateService eventStateService;
  private final RealtimeBroadcastService realtimeBroadcastService;

  /**
   * 自身代理引用（R10 P1-2）。
   *
   * <p>{@code createWarning()} 与 {@code doPersist()} 在同一个类里，直接写
   * {@code doPersist(...)} 是 this 调用，不经过 Spring 的 AOP 代理，方法上的
   * {@code @Transactional} **完全不生效**——两个 Repository 各自独立提交，
   * risk_score_log 写失败会留下孤儿预警。
   *
   * <p>注入自身的代理（{@code @Lazy} 打破构造期循环）后，调用走代理，
   * 事务边界才真的成立。这与 {@code DeviceDataService} 已有的 {@code self} 写法一致。
   */
  private final WarningCreateService self;

  public WarningCreateService(WarningEventRepository warningRepository,
                              RiskScoreLogRepository riskScoreLogRepository,
                              AiServiceClient aiClient,
                              AiAnalysisService aiAnalysisService,
                              ObjectMapper objectMapper,
                              EventStateService eventStateService,
                              RealtimeBroadcastService realtimeBroadcastService,
                              @org.springframework.context.annotation.Lazy WarningCreateService self) {
    this.warningRepository = warningRepository;
    this.riskScoreLogRepository = riskScoreLogRepository;
    this.aiClient = aiClient;
    this.aiAnalysisService = aiAnalysisService;
    this.objectMapper = objectMapper;
    this.eventStateService = eventStateService;
    this.realtimeBroadcastService = realtimeBroadcastService;
    this.self = self;
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
        // 先认定 AI 有效：审计记录的持久化失败不应把已经拿到的评分丢掉。
        // 旧顺序是 saveRecord 在前，一旦审计记录写库异常（如 jsonb 绑定 / 模型外键），
        // aiAvailable 会停在 false，整条链路被迫退化成 RULE_ONLY。
        aiAvailable = true;
        try {
          aiAnalysisService.saveRecord("fusion", hazardCode, fusion.model(), fusion);
        } catch (Exception saveEx) {
          log.warn("AI 融合结果已获取但审计记录写入失败 hazard={}: {}", hazardCode, saveEx.getMessage());
        }
        if (!fusion.degraded()) {
          double confidence = fusion.confidence();
          boolean highConfidence = confidence > 0.7;
          
          switch (fusion.riskLevel()) {
            case "CRITICAL" -> {
              finalLevel = "红色预警";
              log.info("AI 融合升级预警为红色 hazard={}, confidence={}", hazardCode, confidence);
            }
            case "HIGH" -> {
              finalLevel = "橙色预警";
              log.info("AI 融合升级预警为橙色 hazard={}, confidence={}", hazardCode, confidence);
            }
            case "MEDIUM" -> {
              if (level.contains("蓝")) {
                finalLevel = "黄色预警";
                log.info("AI 融合升级预警为黄色 hazard={}, confidence={}", hazardCode, confidence);
              }
            }
            case "LOW" -> {
              // ✅ 【新增降级逻辑】AI 置信度高且评估为低风险，允许降为蓝色
              if (highConfidence && !level.contains("红") && !level.contains("橙")) {
                finalLevel = "蓝色预警";
                log.info("AI 融合降级预警为蓝色 hazard={}, confidence={}, ruleLevel={}, aiScore={}", 
                    hazardCode, confidence, level, fusion.riskScore());
              }
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

    try {
      // 必须走 self 代理：同类直调会绕过 @Transactional（R10 P1-2）
      if (self != null) {
        self.doPersist(ruleCode, hazardCode, title, finalLevel, metricCode, triggerValue,
            aiSource, aiAvailable, fusion, aiFactors);
      } else {
        // 极端情况（如单元测试里手工 new）退化为直接调用，仅影响原子性，不影响功能
        log.warn("WarningCreateService.self 未注入，本次预警写入无事务保证");
        doPersist(ruleCode, hazardCode, title, finalLevel, metricCode, triggerValue,
            aiSource, aiAvailable, fusion, aiFactors);
      }
    } catch (Exception e) {
      log.warn("预警 DB 写入失败 ruleCode={}, hazardCode={}: {}", ruleCode, hazardCode, e.getMessage());
    }
  }

  /**
   * 纯 DB 写入事务：创建 warning_event + risk_score_log（两表原子写入）。
   *
   * <p>返回生成的预警编号，供调用方回传 / 打日志时使用。
   * 由 void 改为 String 对既有调用方（忽略返回值）是源码兼容的。
   *
   * @return 本次生成的预警编号（如 WG-1A2B3C4D）
   */
  @Transactional
  public String doPersist(String ruleCode, String hazardCode, String title, String finalLevel,
                          String metricCode, double triggerValue, String source,
                          boolean aiAvailable, FusionRiskResult fusion, Map<String, Double> factors) {

    WarningEvent event = new WarningEvent();
    event.setWarningCode("WG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    event.setRuleCode(ruleCode);
    event.setHazardCode(hazardCode);
    event.setTitle(title);
    event.setWarningLevel(finalLevel);
    event.setTriggerValue(metricCode + "=" + triggerValue);
    event.setStatus("待确认");
    event.setSource(source);
    event.setTriggeredAt(OffsetDateTime.now());
    // R10 P1-4：初始化复现计数与最近命中时间，自动解除调度器据此判定静默超时
    event.setOccurCount(1);
    event.setLastSeenAt(event.getTriggeredAt());
    event.setAutoResolved(false);

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

    // 【修复 A2】关联统一事件：创建统一事件并保存 eventCode 到预警实体。
    // 用隔离事务版本：统一事件失败只丢关联，不能把本事务标记成 rollback-only
    // 从而连带回滚刚写入的预警（R10 P1-2 修复后事务真实生效才暴露出的耦合）。
    try {
      String eventCode = eventStateService.createFromAiDetectionIsolated(hazardCode, title);
      event.setUnifiedEventCode(eventCode);
      warningRepository.save(event);
      log.info("预警已关联统一事件 warningCode={}, eventCode={}", event.getWarningCode(), eventCode);
    } catch (Exception e) {
      log.warn("创建统一事件失败，但预警仍已创建 warningCode={}: {}", event.getWarningCode(), e.getMessage());
    }

    // 【修复 B2】实时推送预警到前端。
    // 必须在事务提交之后再推：旧实现直接在事务内广播，一旦事务回滚，
    // 前端已经收到并展示了库里并不存在的预警（R10 P1-2 的连带问题）。
    broadcastAfterCommit(event);

    log.info("规则 [{}] 触发 -> 生成预警(待人工复核) hazard={}, value={}, finalLevel={}, source={}",
        ruleCode, hazardCode, triggerValue, finalLevel, source);
    return event.getWarningCode();
  }

  /** 事务提交后再广播；无事务上下文时立即广播。 */
  private void broadcastAfterCommit(WarningEvent event) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void afterCommit() {
              safeBroadcast(event);
            }
          });
      return;
    }
    safeBroadcast(event);
  }

  private void safeBroadcast(WarningEvent event) {
    try {
      realtimeBroadcastService.broadcastWarning(event);
      log.info("预警已推送到 WebSocket warningCode={}", event.getWarningCode());
    } catch (Exception e) {
      log.warn("WebSocket 推送预警失败，但预警仍已创建 warningCode={}: {}", event.getWarningCode(), e.getMessage());
    }
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
