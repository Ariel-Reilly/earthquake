package cn.disasterguard.warning.engine;

import cn.disasterguard.device.entity.DeviceMetric;
import cn.disasterguard.device.repository.DeviceMetricRepository;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.warning.entity.WarningEvent;
import cn.disasterguard.warning.entity.WarningRule;
import cn.disasterguard.warning.repository.WarningEventRepository;
import cn.disasterguard.warning.repository.WarningRuleRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预警规则引擎：定时拉取最新设备指标，对启用的规则求值，命中且未重复触发时生成预警事件。
 *
 * <p>预警创建逻辑委托给独立的 {@link WarningCreateService}（保证 @Transactional 通过代理生效）。
 * 生成的预警初始状态为 <b>待确认</b>，由人工复核后手动确认/派发——AI 输出不能直接推送给用户。
 */
@Component
public class RuleEngineScheduler {

  private static final Logger log = LoggerFactory.getLogger(RuleEngineScheduler.class);

  private final WarningRuleRepository ruleRepository;
  private final WarningEventRepository warningRepository;
  private final DeviceMetricRepository metricRepository;
  private final MonitoringDeviceRepository deviceRepository;
  private final WarningCreateService warningCreateService;
  private final boolean enabled;

  public RuleEngineScheduler(WarningRuleRepository ruleRepository,
                             WarningEventRepository warningRepository,
                             DeviceMetricRepository metricRepository,
                             MonitoringDeviceRepository deviceRepository,
                             WarningCreateService warningCreateService,
                             @Value("${disasterguard.warning.rule-engine-enabled:true}") boolean enabled) {
    this.ruleRepository = ruleRepository;
    this.warningRepository = warningRepository;
    this.metricRepository = metricRepository;
    this.deviceRepository = deviceRepository;
    this.warningCreateService = warningCreateService;
    this.enabled = enabled;
  }

  /**
   * 重入保护（R3 P1-11）：cron 默认每 30 秒触发，而一轮求值涉及规则遍历
   * + 指标查询 + 预警落库，耗时可能超过间隔。修复前没有任何并发保护，
   * 上一轮未结束时下一轮会并发进入，导致重复求值、重复计数与重复写库。
   */
  private final java.util.concurrent.atomic.AtomicBoolean running =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  @Scheduled(cron = "${disasterguard.warning.rule-engine-cron:*/30 * * * * *}")
  public void evaluate() {
    if (!enabled) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      log.warn("规则引擎上一轮尚未结束，本轮跳过（cron 间隔可能小于单轮耗时）");
      return;
    }
    try {
      doEvaluate();
    } catch (Exception ex) {
      // 调度器顶层兜底：任何未捕获异常都不应终止下一轮调度
      log.error("规则引擎调度异常，本轮跳过: {}", ex.getMessage(), ex);
    } finally {
      running.set(false);
    }
  }

  private void doEvaluate() {
    List<WarningRule> rules = ruleRepository.findByEnabledTrue();
    if (rules.isEmpty()) {
      return;
    }
    // 使用轻量投影查询代替 findAll()，避免全量实体加载
    List<Object[]> devicePairs = deviceRepository.findDeviceCodeHazardCodePairs();
    if (devicePairs.isEmpty()) {
      return;
    }

    // 构建 deviceCode -> hazardCode 映射
    Map<String, String> deviceHazard = new HashMap<>();
    List<String> deviceCodes = new ArrayList<>(devicePairs.size());
    for (Object[] pair : devicePairs) {
      String deviceCode = (String) pair[0];
      String hazardCode = (String) pair[1];
      deviceCodes.add(deviceCode);
      if (hazardCode != null) {
        deviceHazard.put(deviceCode, hazardCode);
      }
    }

    List<DeviceMetric> latest = metricRepository.findLatestByDeviceCodes(deviceCodes);
    // hazard_code -> { metric_code -> value }
    Map<String, Map<String, Double>> hazardMetrics = new HashMap<>();
    for (DeviceMetric m : latest) {
      String hazard = m.getHazardCode() != null ? m.getHazardCode() : deviceHazard.get(m.getDeviceCode());
      if (hazard != null) {
        hazardMetrics.computeIfAbsent(hazard, k -> new HashMap<>()).put(m.getMetricCode(), m.getValue());
      }
    }

    // 预取所有未关闭预警，构建 ruleCode|hazardCode 集合（O(1) 判断，避免循环内 N×M 次 exists 查询）
    List<String> ruleCodes = rules.stream().map(WarningRule::getRuleCode).toList();
    List<String> hazardCodes = new ArrayList<>(hazardMetrics.keySet());
    Set<String> openWarningKeys = new java.util.HashSet<>();
    if (!ruleCodes.isEmpty() && !hazardCodes.isEmpty()) {
      List<WarningEvent> openWarnings = warningRepository.findOpenWarnings(
          ruleCodes, hazardCodes, List.of("已关闭", "已忽略", "已解除"));
      for (WarningEvent w : openWarnings) {
        openWarningKeys.add(w.getRuleCode() + "|" + w.getHazardCode());
      }
    }

    for (WarningRule rule : rules) {
      for (Map.Entry<String, Map<String, Double>> entry : hazardMetrics.entrySet()) {
        String hazardCode = entry.getKey();
        Map<String, Double> metrics = entry.getValue();
        if (!metrics.containsKey(rule.getMetricCode())) {
          continue;
        }
        Optional<Double> hit = WarningRuleEvaluator.evaluate(rule.getExpression(), metrics);
        if (hit.isEmpty()) {
          continue;
        }
        if (openWarningKeys.contains(rule.getRuleCode() + "|" + hazardCode)) {
          continue;
        }
        // 委托给独立 Service，通过代理保证 @Transactional 生效
        try {
          warningCreateService.createWarning(
              rule.getRuleCode(), rule.getRuleName(), hazardCode,
              rule.getWarningLevel(), rule.getMetricCode(), hit.get(), metrics);
          // 成功后标记为已触发，避免同一次调度内重复命中
          openWarningKeys.add(rule.getRuleCode() + "|" + hazardCode);
        } catch (Exception ex) {
          // 单条预警创建失败不应中断整个调度周期
          log.warn("规则 [{}] 创建预警失败 hazard={}: {}", rule.getRuleCode(), hazardCode, ex.getMessage());
        }
      }
    }
  }
}
