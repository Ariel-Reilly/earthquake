package cn.disasterguard.ruleengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.disasterguard.ruleengine.entity.Rule;
import cn.disasterguard.ruleengine.repository.RuleRepository;
import cn.disasterguard.warning.entity.WarningRule;
import cn.disasterguard.warning.repository.WarningRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * 规则管理页 ↔ 预警引擎 双向桥测试（R10 P1-5 / P2-6）。
 *
 * <p>回归重点：data.sql 不再单独播种 rule 表后，全新库首启完全依赖
 * {@link WarningRuleSyncService#syncAllFromWarningRule()} 建行；而 rule.actions /
 * rule.conditions 是 JSON NOT NULL，漏填会让整批镜像失败、页面显示空。
 * 同时钉住「页面改阈值 → 编译表达式 → 写回 warning_rule」这条反向链路。
 */
class WarningRuleSyncServiceTest {

  private WarningRule sourceRule(String code, String expression) {
    WarningRule r = new WarningRule();
    r.setRuleCode(code);
    r.setRuleName("倾角异常蓝色预警");
    r.setDisasterType("collapse");
    r.setMetricCode("tilt_angle");
    r.setExpression(expression);
    r.setWarningLevel("蓝色预警");
    r.setEnabled(true);
    return r;
  }

  /** 构造一个不会真连库的服务：TransactionTemplate 的 commit 用 mock 顶掉。 */
  private WarningRuleSyncService service(RuleRepository ruleRepo,
                                         WarningRuleRepository warningRepo) {
    PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    when(tx.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(mock(TransactionStatus.class));
    return new WarningRuleSyncService(ruleRepo, warningRepo, new ObjectMapper(), tx);
  }

  @Test
  @DisplayName("新库首启：从 warning_rule 建行，JSON NOT NULL 两列必须都有值")
  void createsRuleRowWithRequiredJsonColumns() {
    RuleRepository ruleRepo = mock(RuleRepository.class);
    WarningRuleRepository warningRepo = mock(WarningRuleRepository.class);
    when(warningRepo.findAll()).thenReturn(List.of(sourceRule("R004", "tilt_angle > 5")));
    when(ruleRepo.findByRuleCode("R004")).thenReturn(Optional.empty());

    int changed = service(ruleRepo, warningRepo).syncAllFromWarningRule();

    assertEquals(1, changed);
    ArgumentCaptor<Rule> captor = ArgumentCaptor.forClass(Rule.class);
    verify(ruleRepo, times(1)).save(captor.capture());
    Rule created = captor.getValue();
    assertEquals("R004", created.getRuleCode());
    assertEquals("倾角异常蓝色预警", created.getName());
    assertEquals("blue", created.getLevel());
    assertEquals("tilt_angle > 5", created.getDslText());
    assertNotNull(created.getActions(), "rule.actions 是 NOT NULL，新库首启必须给占位值");
    assertNotNull(created.getConditions(), "rule.conditions 是 NOT NULL，须由表达式编译出来");
    assertTrue(created.getConditions().contains("tilt_angle"));
  }

  @Test
  @DisplayName("内容未变化时不重复写库，避免每次启动刷 version")
  void skipsUnchangedRows() throws Exception {
    RuleRepository ruleRepo = mock(RuleRepository.class);
    WarningRuleRepository warningRepo = mock(WarningRuleRepository.class);
    when(warningRepo.findAll()).thenReturn(List.of(sourceRule("R004", "tilt_angle > 5")));

    Rule existing = new Rule();
    existing.setRuleCode("R004");
    existing.setName("倾角异常蓝色预警");
    existing.setDisasterType("collapse");
    existing.setLevel("blue");
    existing.setDslText("tilt_angle > 5");
    existing.setEnabled(true);
    // 用与服务内部完全相同的 Map.of + ObjectMapper 构造，避免依赖 JSON 键顺序的猜测
    Object five = new java.math.BigDecimal("5");
    java.util.List<java.util.Map<String, Object>> items =
        java.util.List.of(java.util.Map.of("field", "tilt_angle", "op", "gt", "value", five));
    String conditions = new ObjectMapper()
        .writeValueAsString(java.util.Map.of("operator", "AND", "items", items));
    existing.setConditions(conditions);
    when(ruleRepo.findByRuleCode("R004")).thenReturn(Optional.of(existing));

    assertEquals(0, service(ruleRepo, warningRepo).syncAllFromWarningRule());
    verify(ruleRepo, times(0)).save(any());
  }

  @Test
  @DisplayName("页面改阈值 5→6：编译出的表达式写回 warning_rule，下一轮调度即生效")
  void appliesPageEditToWarningRule() {
    RuleRepository ruleRepo = mock(RuleRepository.class);
    WarningRuleRepository warningRepo = mock(WarningRuleRepository.class);
    Rule edited = new Rule();
    edited.setRuleCode("R004");
    edited.setName("倾角异常蓝色预警");
    edited.setDisasterType("collapse");
    edited.setLevel("blue");
    edited.setEnabled(true);
    edited.setConditions("{\"operator\":\"AND\",\"items\":[{\"field\":\"tilt_angle\",\"op\":\"gt\",\"value\":6}]}");
    when(warningRepo.findByRuleCode("R004")).thenReturn(Optional.of(sourceRule("R004", "tilt_angle > 5")));

    WarningRuleSyncService.ApplyResult result =
        service(ruleRepo, warningRepo).applyToWarningRule(edited);

    assertTrue(result.applied());
    assertEquals("tilt_angle > 6", result.expression());
    ArgumentCaptor<WarningRule> captor = ArgumentCaptor.forClass(WarningRule.class);
    verify(warningRepo, times(1)).save(captor.capture());
    assertEquals("tilt_angle > 6", captor.getValue().getExpression());
    assertEquals("蓝色预警", captor.getValue().getWarningLevel());
  }

  @Test
  @DisplayName("求值器不支持的运算符：如实返回未生效，不写恒真表达式")
  void refusesUnsupportedOperator() {
    RuleRepository ruleRepo = mock(RuleRepository.class);
    WarningRuleRepository warningRepo = mock(WarningRuleRepository.class);
    Rule edited = new Rule();
    edited.setRuleCode("R004");
    edited.setConditions("{\"operator\":\"AND\",\"items\":[{\"field\":\"title\",\"op\":\"contains\",\"value\":\"滑坡\"}]}");

    WarningRuleSyncService.ApplyResult result =
        service(ruleRepo, warningRepo).applyToWarningRule(edited);

    assertFalse(result.applied());
    assertTrue(result.message().contains("不支持"));
    verify(warningRepo, times(0)).save(any());
  }
}
