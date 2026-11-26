package cn.disasterguard.warning.engine;

import cn.disasterguard.ai.entity.AiComprehensiveScore;
import cn.disasterguard.ai.repository.AiComprehensiveScoreRepository;
import cn.disasterguard.warning.entity.WarningEvent;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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
  private static final String RULE_CODE_AI = cn.disasterguard.ruleengine.WarningRuleSyncService.AI_SYSTEM_RULE_CODE;
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
    Map<String, Long> openWarningIds = new HashMap<>();
    for (WarningEvent w : openList) {
      openWarningIds.putIfAbsent(w.getHazardCode(), w.getId());
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int created = 0;
    int skipped = 0;
    int failed = 0;
    for (AiComprehensiveScore s : elevated) {
      Long openId = openWarningIds.get(s.getHazardCode());
      if (openId != null) {
        // R11 P1-B2：AI 分数仍处于 MEDIUM+ 时，续期已有预警的 lastSeenAt。
        // 旧实现只跳过不续期，导致 30 分钟后被 WarningAutoResolveScheduler 判为
        // 「指标未再命中」自动解除，下一轮（10 分钟）又重建，形成解除/重建循环。
        try {
          warningRepository.bumpOccurrence(openId, now);
        } catch (Exception ex) {
          log.warn("AI 预警续期失败 id={} hazard={}: {}", openId, s.getHazardCode(), ex.getMessage());
        }
        skipped++;
        continue;
      }
      try {
        // R3 P1-7：只有真正写库成功才计入 created
        if (createAiWarning(s, now)) {
          created++;
        } else {
          skipped++;
        }
      } catch (Exception ex) {
        failed++;
        log.error("AI 预警创建失败 hazard={} level={}: {}",
            s.getHazardCode(), s.getRiskLevel(), ex.getMessage(), ex);
      }
    }
    if (created > 0 || skipped > 0 || failed > 0) {
      log.info("AI 综合评分联动预警：触发 {} 条，跳过 {} 条，失败 {} 条", created, skipped, failed);
    }
  }

  /**
   * 生成一条 AI 联动预警。
   *
   * @return true 表示确实写库成功；false 表示因并发去重（唯一约束冲突）未写入。
   *         调用方<b>不得</b>把 false 计入"已触发"，否则日志会谎报预警数量。
   * @throws RuntimeException 其他写入失败（外键/非空/连接等）原样抛出，由调用方计入失败数
   */
  private boolean createAiWarning(AiComprehensiveScore s, OffsetDateTime now) {
    WarningEvent event = new WarningEvent();
    event.setWarningCode("WG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    event.setRuleCode(RULE_CODE_AI);
    event.setHazardCode(s.getHazardCode());
    event.setTitle(RULE_NAME + " · " + s.getHazardCode());
    event.setWarningLevel(translateLevel(s.getRiskLevel()));
    event.setTriggerValue(String.format("ai_score=%.2f, model=%s", s.getRiskScore(), s.getModel()));
    event.setStatus("待确认");
    event.setSource("AI_AUTO");
    event.setTriggeredAt(now);
    // R11 P1-B2：必须写 lastSeenAt，否则自动解除调度按 COALESCE(lastSeenAt, triggeredAt) 计时，
    // 且此后无任何路径续期（旧实现直接跳过已存在的 hazard），预警会被反复解除/重建。
    event.setLastSeenAt(now);
    event.setOccurCount(1);
    try {
      warningRepository.save(event);
    } catch (DataIntegrityViolationException ex) {
      if (isUniqueViolation(ex)) {
        // 唯一约束冲突说明已被并发的另一条路径写入，属预期内去重。
        // 但<b>必须返回 false</b>——没有写进去就不能算"已触发"。
        log.debug("AI 预警并发去重，未写入 hazard={}: {}", s.getHazardCode(), ex.getMessage());
        return false;
      }
      // R11 P0-1：外键/非空/类型等约束违约**不是**并发去重。
      // 旧实现把整个 DataIntegrityViolationException 都吞成 debug + return false，
      // 于是 rule_code='AI_COMPREHENSIVE' 缺外键目标行时，AI 预警每轮静默失败、
      // 日志无任何告警。这里必须原样抛出，由调用方 log.error 并计入 failed。
      log.error("AI 预警写入违反约束（非并发去重）hazard={} ruleCode={}: {}",
          s.getHazardCode(), RULE_CODE_AI, ex.getMostSpecificCause().getMessage());
      throw ex;
    }
    log.info("AI 联动预警 -> 生成待确认事件 hazard={}, level={}, score={}",
        s.getHazardCode(), s.getRiskLevel(), s.getRiskScore());
    return true;
  }

  /** 判断是否 PostgreSQL 唯一约束冲突（SQLState 23505），用于区分并发去重与真正的写入错误。 */
  private static boolean isUniqueViolation(DataIntegrityViolationException ex) {
    Throwable cause = ex.getMostSpecificCause();
    return cause instanceof SQLException sqlEx && "23505".equals(sqlEx.getSQLState());
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
