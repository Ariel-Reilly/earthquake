package cn.disasterguard.warning.engine;

import cn.disasterguard.ai.entity.AiComprehensiveScore;
import cn.disasterguard.ai.repository.AiComprehensiveScoreRepository;
import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.warning.entity.WarningEvent;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 综合分析 → 预警联动：把高分灾害点（MEDIUM+）自动转成 warning_event（source=AI_AUTO）。
 *
 * <p>AI 综合分析是后台"单一可信源"——一旦分析结果风险等级达到 MEDIUM，自动生成预警事件。
 * 与 {@link RuleEngineScheduler}（规则引擎派发）并行：规则引擎看的是设备指标瞬时值，
 * 这个组件看的是 AI 综合多维度评分。
 *
 * <p>避免重复触发：同一 hazard_code 只要还存在未关闭的 AI_COMPREHENSIVE 预警就不再生成；
 * 原预警被关闭/解除/忽略后，若分数仍属 MEDIUM+ 才会重新生成一条（保证风险不丢失）。
 */
@Component
public class AiWarningScheduler {

  private static final Logger log = LoggerFactory.getLogger(AiWarningScheduler.class);

  /** AI 等级到预警等级的映射。MEDIUM → 黄色预警，HIGH → 橙色预警，CRITICAL → 红色预警。 */
  private static final String RULE_CODE_AI = "AI_COMPREHENSIVE";
  private static final String RULE_NAME = "AI 综合风险评分（图像+降水+地理+设备）";

  private final AiComprehensiveScoreRepository scoreRepository;
  private final WarningEventRepository warningRepository;
  private final boolean enabled;

  public AiWarningScheduler(AiComprehensiveScoreRepository scoreRepository,
                            WarningEventRepository warningRepository,
                            @Value("${disasterguard.ai.comprehensive-to-warning-enabled:true}") boolean enabled) {
    this.scoreRepository = scoreRepository;
    this.warningRepository = warningRepository;
    this.enabled = enabled;
  }

  @Scheduled(cron = "${disasterguard.ai.comprehensive-to-warning-cron:0 */10 * * * *}")
  public void evaluate() {
    if (!enabled) return;
    try {
      doEvaluate();
    } catch (Exception ex) {
      log.error("AI 综合预警联动调度异常，本轮跳过: {}", ex.getMessage(), ex);
    }
  }

  private void doEvaluate() {
    List<AiComprehensiveScore> elevated = scoreRepository.findByRiskLevelIn(
        List.of("MEDIUM", "HIGH", "CRITICAL"));
    if (elevated.isEmpty()) return;

    // 一次性预取所有相关 hazard 的当前 open 预警（避免逐条 N+1 查询）
    List<String> hazardCodes = elevated.stream().map(AiComprehensiveScore::getHazardCode).toList();
    List<WarningEvent> openList = warningRepository.findOpenWarnings(
        List.of(RULE_CODE_AI),
        hazardCodes,
        List.of("已关闭", "已忽略", "已解除")
    );
    Set<String> openHazards = new HashSet<>();
    for (WarningEvent w : openList) {
      openHazards.add(w.getHazardCode());
    }

    int created = 0;
    int skipped = 0;
    for (AiComprehensiveScore s : elevated) {
      if (openHazards.contains(s.getHazardCode())) {
        skipped++;
        continue;
      }
      try {
        createAiWarning(s);
        created++;
      } catch (Exception ex) {
        log.warn("AI 预警创建失败 hazard={}: {}", s.getHazardCode(), ex.getMessage());
      }
    }
    if (created > 0 || skipped > 0) {
      log.info("AI 综合评分联动预警：触发 {} 条，去重跳过 {} 条", created, skipped);
    }
  }

  private void createAiWarning(AiComprehensiveScore s) {
    WarningEvent event = new WarningEvent();
    event.setWarningCode("WG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    event.setRuleCode(RULE_CODE_AI);
    event.setHazardCode(s.getHazardCode());
    event.setTitle(RULE_NAME + " · " + s.getHazardCode());
    event.setWarningLevel(translateLevel(s.getRiskLevel()));
    event.setTriggerValue(String.format("ai_score=%.2f, model=%s", s.getRiskScore(), s.getModel()));
    event.setStatus("待确认");
    event.setSource("AI_AUTO");
    event.setTriggeredAt(OffsetDateTime.now(ZoneOffset.UTC));
    try {
      warningRepository.save(event);
    } catch (Exception ex) {
      // 唯一约束冲突说明已被并发另一条路径写入，继续
      log.debug("AI 预警 DB 写入失败，可能并发触发 hazard={}: {}", s.getHazardCode(), ex.getMessage());
      if (!(ex instanceof AppException)) {
        throw ex;
      }
    }
    log.info("AI 联动预警 -> 生成待确认事件 hazard={}, level={}, score={}",
        s.getHazardCode(), s.getRiskLevel(), s.getRiskScore());
  }

  private static String translateLevel(String aiLevel) {
    if (aiLevel == null) return "黄色预警";
    return switch (aiLevel) {
      case "CRITICAL" -> "红色预警";
      case "HIGH" -> "橙色预警";
      case "MEDIUM" -> "黄色预警";
      case "LOW" -> "蓝色预警";
      default -> "黄色预警";
    };
  }

  /** 暴露一个手动入口，用于测试或"立即联动"按钮。 */
  public int evaluateNow() {
    int before = countOpen();
    doEvaluate();
    return countOpen() - before;
  }

  private int countOpen() {
    return warningRepository.findOpenWarnings(
        List.of(RULE_CODE_AI), List.of(), List.of("已关闭", "已忽略", "已解除")
    ).size();
  }
}
