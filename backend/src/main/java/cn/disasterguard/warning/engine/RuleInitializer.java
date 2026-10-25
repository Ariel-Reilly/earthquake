package cn.disasterguard.warning.engine;

import cn.disasterguard.warning.entity.WarningRule;
import cn.disasterguard.warning.repository.WarningRuleRepository;
import cn.disasterguard.ruleengine.RuleEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 【P1 修复】应用启动时自动初始化演示规则。
 *
 * 如果数据库中没有任何启用的规则，则插入演示规则，确保规则引擎能正常工作。
 * 这是 data.sql 初始化失败时的备用方案。
 *
 * 【时序修复】
 * - @Order(1) 确保本 CommandLineRunner 在其他 runner 之前执行
 * - 插入规则后立刻调用 RuleEngineService.reloadRules() 刷新缓存
 * - RuleEngineService 使用 @DependsOn("ruleInitializer") + @PostConstruct 等本 runner 完成后才初始化
 */
@Component
@Order(1)
public class RuleInitializer implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(RuleInitializer.class);

  private final WarningRuleRepository ruleRepository;
  private final RuleEngineService ruleEngineService;

  public RuleInitializer(WarningRuleRepository ruleRepository, RuleEngineService ruleEngineService) {
    this.ruleRepository = ruleRepository;
    this.ruleEngineService = ruleEngineService;
  }

  @Override
  public void run(String... args) throws Exception {
    try {
      long enabledRuleCount = ruleRepository.countByEnabled(true);
      if (enabledRuleCount == 0) {
        log.warn("【P1 修复】数据库中未检测到启用的规则，正在插入演示规则...");
        insertDemoRules();
        
        // 【关键】插入规则后立刻刷新缓存，而不是等待规则引擎自己去加载
        log.info("【P1 修复】刷新规则缓存...");
        ruleEngineService.reloadRules("RuleInitializer.if");
        
        enabledRuleCount = ruleRepository.countByEnabled(true);
        log.info("【P1 修复】已插入并加载演示规则，当前启用规则数: {}", enabledRuleCount);
      } else {
        long beforeCacheCount = ruleEngineService.getCacheSize();
        log.info("【P1 修复】规则库已就绪，启用规则数: {}，reload 前缓存: {}", 
                 enabledRuleCount, beforeCacheCount);
        
        // 【关键】else 分支也需要刷新缓存，确保启动时规则引擎已加载所有规则
        ruleEngineService.reloadRules("RuleInitializer.else");
        
        long afterCacheCount = ruleEngineService.getCacheSize();
        log.info("【P1 修复】规则库已就绪，启用规则数: {}，reload 前缓存: {}，reload 后缓存: {}",
                 enabledRuleCount, beforeCacheCount, afterCacheCount);
      }
    } catch (Exception e) {
      log.error("【P1 修复】规则初始化失败", e);
    }
  }

  private void insertDemoRules() {
    // 规则 1：降雨 > 50mm 触发黄色预警
    WarningRule rule1 = new WarningRule();
    rule1.setRuleCode("R_DEMO_001");
    rule1.setRuleName("演示规则：降雨阈值黄色");
    rule1.setMetricCode("rainfall_24h");
    rule1.setExpression("rainfall_24h > 50");
    rule1.setWarningLevel("黄色预警");
    rule1.setEnabled(true);
    rule1.setRemark("演示用：降雨量超过50mm触发黄色预警");

    // 规则 2：位移速率 > 5mm/day 触发橙色预警
    WarningRule rule2 = new WarningRule();
    rule2.setRuleCode("R_DEMO_002");
    rule2.setRuleName("演示规则：位移速率橙色");
    rule2.setMetricCode("displacement_rate");
    rule2.setExpression("displacement_rate > 5");
    rule2.setWarningLevel("橙色预警");
    rule2.setEnabled(true);
    rule2.setRemark("演示用：位移速率超过5mm/day触发橙色预警");

    // 规则 3：裂缝宽度 > 10mm 触发红色预警
    WarningRule rule3 = new WarningRule();
    rule3.setRuleCode("R_DEMO_003");
    rule3.setRuleName("演示规则：裂缝宽度红色");
    rule3.setMetricCode("crack_width");
    rule3.setExpression("crack_width > 10");
    rule3.setWarningLevel("红色预警");
    rule3.setEnabled(true);
    rule3.setRemark("演示用：裂缝宽度超过10mm触发红色预警");

    ruleRepository.save(rule1);
    ruleRepository.save(rule2);
    ruleRepository.save(rule3);
    log.info("已保存 3 条演示规则");
  }
}
