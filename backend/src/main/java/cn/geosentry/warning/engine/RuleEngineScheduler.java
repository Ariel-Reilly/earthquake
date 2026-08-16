package cn.geosentry.warning.engine;

import cn.geosentry.ai.AiServiceClient;
import cn.geosentry.ai.dto.FusionRiskResult;
import cn.geosentry.ai.service.AiAnalysisService;
import cn.geosentry.device.entity.DeviceMetric;
import cn.geosentry.device.entity.MonitoringDevice;
import cn.geosentry.device.repository.DeviceMetricRepository;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import cn.geosentry.warning.entity.RiskScoreLog;
import cn.geosentry.warning.entity.WarningEvent;
import cn.geosentry.warning.entity.WarningRule;
import cn.geosentry.warning.repository.RiskScoreLogRepository;
import cn.geosentry.warning.repository.WarningEventRepository;
import cn.geosentry.warning.repository.WarningRuleRepository;
import cn.geosentry.warning.service.WarningService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预警规则引擎：定时拉取最新设备指标，对启用的规则求值，命中且未重复触发时生成预警事件。
 *
 * <p>可选联动 AI 融合评分（规则命中后用 {@link AiServiceClient#fusionRisk} 校验风险等级，并落 risk_score_log）。
 * 生成的预警初始状态为 <b>待确认</b>，由人工复核后手动确认/派发——AI 输出不能直接推送给用户。
 */
@Component
public class RuleEngineScheduler {

  private static final Logger log = LoggerFactory.getLogger(RuleEngineScheduler.class);

  private final WarningRuleRepository ruleRepository;
  private final WarningEventRepository warningRepository;
  private final DeviceMetricRepository metricRepository;
  private final MonitoringDeviceRepository deviceRepository;
  private final WarningService warningService;
  private final AiServiceClient aiClient;
  private final AiAnalysisService aiAnalysisService;
  private final RiskScoreLogRepository riskScoreLogRepository;
  private final ObjectMapper objectMapper;

  private final boolean enabled;

  public RuleEngineScheduler(WarningRuleRepository ruleRepository,
                             WarningEventRepository warningRepository,
                             DeviceMetricRepository metricRepository,
                             MonitoringDeviceRepository deviceRepository,
                             WarningService warningService,
                             AiServiceClient aiClient,
                             AiAnalysisService aiAnalysisService,
                             RiskScoreLogRepository riskScoreLogRepository,
                             ObjectMapper objectMapper,
                             @Value("${geosentry.warning.rule-engine-enabled:true}") boolean enabled) {
    this.ruleRepository = ruleRepository;
    this.warningRepository = warningRepository;
    this.metricRepository = metricRepository;
    this.deviceRepository = deviceRepository;
    this.warningService = warningService;
    this.aiClient = aiClient;
    this.aiAnalysisService = aiAnalysisService;
    this.riskScoreLogRepository = riskScoreLogRepository;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
  }

  @Scheduled(cron = "${geosentry.warning.rule-engine-cron:*/30 * * * * *}")
  public void evaluate() {
    if (!enabled) {
      return;
    }
    List<WarningRule> rules = ruleRepository.findByEnabledTrue();
    if (rules.isEmpty()) {
      return;
    }
    List<MonitoringDevice> devices = deviceRepository.findAll();
    if (devices.isEmpty()) {
      return;
    }
    Map<String, String> deviceHazard = new HashMap<>();
    List<String> deviceCodes = devices.stream()
        .map(MonitoringDevice::getDeviceCode)
        .toList();
    devices.forEach(d -> deviceHazard.put(d.getDeviceCode(), d.getHazardCode()));

    List<DeviceMetric> latest = metricRepository.findLatestByDeviceCodes(deviceCodes);
    // hazard_code -> { metric_code -> value }
    Map<String, Map<String, Double>> hazardMetrics = new HashMap<>();
    for (DeviceMetric m : latest) {
      String hazard = m.getHazardCode() != null ? m.getHazardCode() : deviceHazard.get(m.getDeviceCode());
      if (hazard != null) {
        hazardMetrics.computeIfAbsent(hazard, k -> new HashMap<>()).put(m.getMetricCode(), m.getValue());
      }
    }

    for (WarningRule rule : rules) {
      for (Map.Entry<String, Map<String, Double>> entry : hazardMetrics.entrySet()) {
        String hazardCode = entry.getKey();
        Map<String, Double> metrics = entry.getValue();
        if (!metrics.containsKey(rule.getMetricCode())) {
          continue;
        }
        Optional<Double> hit = RuleEvaluator.evaluate(rule.getExpression(), metrics);
        if (hit.isEmpty()) {
          continue;
        }
        if (hasOpenWarning(rule.getRuleCode(), hazardCode)) {
          continue;
        }
        createWarning(rule, hazardCode, hit.get(), metrics);
      }
    }
  }

  private boolean hasOpenWarning(String ruleCode, String hazardCode) {
    return warningRepository.existsByRuleCodeAndHazardCodeAndStatusNot(ruleCode, hazardCode, "已关闭");
  }

  private void createWarning(WarningRule rule, String hazardCode, double triggerValue, Map<String, Double> metrics) {
    String title = rule.getRuleName() == null ? rule.getRuleCode() : rule.getRuleName();
    String level = rule.getWarningLevel();
    boolean aiInvolved = false;
    boolean aiDegraded = false;
    try {
      if (aiClient.isEnabled()) {
        Map<String, Double> factors = new HashMap<>(metrics);
        FusionRiskResult fusion = aiClient.fusionRisk(hazardCode, factors);
        aiAnalysisService.saveRecord("fusion", hazardCode, fusion.model(), fusion);
        // 写 risk_score_log（用于模型迭代回溯）
        RiskScoreLog riskLog = new RiskScoreLog();
        riskLog.setHazardCode(hazardCode);
        // ai-service risk_score 为 0-100 百分比，与 data.sql 种子数据一致
        riskLog.setScore(fusion.riskScore());
        riskLog.setRiskLevel(fusion.riskLevel());
        // ai-service 返回 mock=true 标记模型缺失降级 → source 仍为 AI_FUSED 但带 degraded=true
        riskLog.setSource(fusion.degraded() ? "AI_DEGRADED" : "AI_FUSED");
        try {
          riskLog.setFactors(objectMapper.writeValueAsString(
              Map.of("input", factors, "confidence", fusion.confidence(), "degraded", fusion.degraded())));
        } catch (JsonProcessingException ignore) {
          log.warn("factors 序列化失败，risk_score_log 写空对象 hazardCode={}: {}", hazardCode, ignore.getMessage());
          riskLog.setFactors("{}");
        }
        riskLog.setCalculatedAt(OffsetDateTime.now());
        riskScoreLogRepository.save(riskLog);

        // AI 返回 mock 降级结果时，标记为 aiDegraded（而非 aiInvolved）
        if (fusion.degraded()) {
          aiDegraded = true;
        } else if ("CRITICAL".equalsIgnoreCase(fusion.riskLevel())) {
          level = "红色预警";
          aiInvolved = true;
        } else if ("HIGH".equalsIgnoreCase(fusion.riskLevel())) {
          level = "橙色预警";
          aiInvolved = true;
        } else if ("MEDIUM".equalsIgnoreCase(fusion.riskLevel()) && level.contains("蓝")) {
          level = "黄色预警";
          aiInvolved = true;
        }
      }
    } catch (Exception e) {
      log.warn("AI 融合评分不可用，沿用规则等级: {}", e.getMessage());
      // 降级时也写一条 risk_score_log 标记 RULE_ONLY，便于后续评估 AI 覆盖率
      try {
        RiskScoreLog riskLog = new RiskScoreLog();
        riskLog.setHazardCode(hazardCode);
        riskLog.setScore(0.0);
        riskLog.setRiskLevel("RULE_ONLY");
        riskLog.setSource("RULE_ONLY");
        riskLog.setFactors("{\"degraded\":true,\"reason\":\"ai-unavailable\"}");
        riskLog.setCalculatedAt(OffsetDateTime.now());
        riskScoreLogRepository.save(riskLog);
      } catch (Exception ignored) {
        log.debug("降级日志写入失败（不影响主流程）", ignored);
      }
      aiDegraded = true;
    }

    // 生成预警：初始状态=待确认（AI/规则输出均不自动派发，强制人工复核）
    WarningEvent event = new WarningEvent();
    event.setWarningCode("WG-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    event.setRuleCode(rule.getRuleCode());
    event.setHazardCode(hazardCode);
    event.setTitle(title);
    event.setWarningLevel(level);
    event.setTriggerValue(rule.getMetricCode() + "=" + triggerValue);
    event.setStatus("待确认");
    String source;
    if (aiDegraded) {
      source = "AI_DEGRADED";
    } else if (aiInvolved) {
      source = "AI_AUTO";
    } else {
      source = "RULE_ENGINE";
    }
    event.setSource(source);
    event.setTriggeredAt(OffsetDateTime.now());
    warningRepository.save(event);

    log.info("规则 [{}] 触发 -> 生成预警(待人工复核) hazard={}, value={}, finalLevel={}, source={}",
        rule.getRuleCode(), hazardCode, triggerValue, level, event.getSource());
  }
}
