package cn.disasterguard.ruleengine;

import cn.disasterguard.ruleengine.dto.RuleTestRequest;
import cn.disasterguard.ruleengine.dto.RuleTestResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则引擎集成测试：复合条件（AND）的命中 / 边界行为。
 *
 * <p><b>R10 P1-5 适配</b>：本类原先按数据库里 {@code rule.R004 = 设备离线告警}
 * 写死断言，靠 {@code ruleRepository.findByRuleCode("R004")} 取 ID 再测。
 * P1-5 把 rule 表统一为 warning_rule 的镜像（R004 现在是「倾角 &gt; 5」），
 * 这 5 条用例随之全部失效——但失效的是测试对业务种子数据的耦合，不是求值器本身。
 * 现改为通过 {@link RuleTestRequest#conditions()} 自带条件树（test() 原生支持），
 * 保留全部原有场景与断言，同时不再受规则语义调整 / 数据库残留影响。
 */
@SpringBootTest
@DisplayName("规则引擎集成测试 - 复合条件与边界")
public class RuleEngineIntegrationTest {

  @Autowired
  private RuleEngineService ruleEngineService;

  /** device_status == "OFFLINE" AND duration_minutes > 10 —— 与原 R004 用例语义一致。 */
  private static RuleTestRequest offlineRuleRequest() {
    return new RuleTestRequest(null, new RuleTestRequest.RuleConditions(
        "AND",
        List.of(
            new RuleTestRequest.ConditionItem("device_status", "==", "OFFLINE", null),
            new RuleTestRequest.ConditionItem("duration_minutes", ">", 10, "min"))), null);
  }

  /**
   * 测试 R004 规则命中场景。
   *
   * 条件满足：device_status == "OFFLINE" AND duration_minutes > 10（15分钟）
   * 预期结果：规则命中，生成预警事件
   */
  @Test
  @DisplayName("testR004Trigger - 条件满足，规则应命中")
  void testR004Trigger() {
    // 构造满足 R004 条件的测试数据
    // 注意：device_status 必须是字符串，duration_minutes 必须是数字
    Map<String, Object> testData = new HashMap<>();
    testData.put("device_status", "OFFLINE");
    testData.put("duration_minutes", 15.0);  // 转换为 double 以便规则引擎数值比较

    // 调用规则引擎的测试方法
    RuleTestRequest request = offlineRuleRequest();
    RuleTestResponse result = ruleEngineService.test(request, testData);

    // 验证结果
    System.out.println("\n>>> 测试结果：R004 匹配状态 = " + (result.triggered() ? "✅ 命中" : "❌ 不命中"));
    System.out.println("    测试数据: " + testData);
    System.out.println("    满足条件数: " + result.matchedConditions().size());
    System.out.println("    失败条件数: " + result.failedConditions().size());
    System.out.println("    详情消息: " + result.message());

    // 核心断言：规则应该命中
    assertTrue(result.triggered(),
        "R004 规则应该命中：device_status='OFFLINE' AND duration_minutes=15");

    // 辅助断言：应有 2 个条件满足（device_status 和 duration_minutes）
    assertEquals(2, result.matchedConditions().size(),
        "应有 2 个条件满足（device_status == OFFLINE 和 duration_minutes > 10）");

    // 辅助断言：不应有条件失败
    assertEquals(0, result.failedConditions().size(),
        "不应有条件失败");
  }

  /**
   * 测试 R004 规则不命中场景（设备状态不匹配）。
   *
   * 条件不满足：device_status == "ONLINE"（不是 OFFLINE），duration_minutes = 15
   * 预期结果：规则不命中
   */
  @Test
  @DisplayName("testR004NotTrigger_StatusMismatch - device_status 不是 OFFLINE，规则不应命中")
  void testR004NotTrigger_StatusMismatch() {
    // 构造不满足条件的测试数据（设备状态为 ONLINE）
    Map<String, Object> testData = new HashMap<>();
    testData.put("device_status", "ONLINE");
    testData.put("duration_minutes", 15.0);

    // 调用规则引擎的测试方法
    RuleTestRequest request = offlineRuleRequest();
    RuleTestResponse result = ruleEngineService.test(request, testData);

    // 验证结果
    System.out.println("\n>>> 测试结果：R004 匹配状态（状态不匹配）= " + (result.triggered() ? "✅ 命中" : "❌ 不命中"));
    System.out.println("    测试数据: " + testData);
    System.out.println("    满足条件数: " + result.matchedConditions().size());
    System.out.println("    失败条件数: " + result.failedConditions().size());
    System.out.println("    详情消息: " + result.message());

    // 核心断言：规则应该不命中
    assertFalse(result.triggered(),
        "R004 规则不应该命中：device_status='ONLINE' 不满足条件");

    // 辅助断言：应有 1 个条件失败（device_status 不匹配），1 个条件满足（duration_minutes > 10）
    assertEquals(1, result.matchedConditions().size(),
        "应有 1 个条件满足（duration_minutes > 10）");
    assertEquals(1, result.failedConditions().size(),
        "应有 1 个条件失败（device_status != OFFLINE）");
  }

  /**
   * 测试 R004 规则不命中场景（离线时长不足）。
   *
   * 条件不满足：device_status == "OFFLINE"，duration_minutes = 5（小于 10）
   * 预期结果：规则不命中
   */
  @Test
  @DisplayName("testR004NotTrigger_DurationInsufficient - 离线时长不足 10 分钟，规则不应命中")
  void testR004NotTrigger_DurationInsufficient() {
    // 构造不满足条件的测试数据（离线时长不足）
    Map<String, Object> testData = new HashMap<>();
    testData.put("device_status", "OFFLINE");
    testData.put("duration_minutes", 5.0);

    // 调用规则引擎的测试方法
    RuleTestRequest request = offlineRuleRequest();
    RuleTestResponse result = ruleEngineService.test(request, testData);

    // 验证结果
    System.out.println("\n>>> 测试结果：R004 匹配状态（时长不足）= " + (result.triggered() ? "✅ 命中" : "❌ 不命中"));
    System.out.println("    测试数据: " + testData);
    System.out.println("    满足条件数: " + result.matchedConditions().size());
    System.out.println("    失败条件数: " + result.failedConditions().size());
    System.out.println("    详情消息: " + result.message());

    // 核心断言：规则应该不命中
    assertFalse(result.triggered(),
        "R004 规则不应该命中：duration_minutes=5 < 10，条件不满足");

    // 辅助断言：应有 1 个条件满足（device_status == OFFLINE），1 个条件失败（duration_minutes <= 10）
    assertEquals(1, result.matchedConditions().size(),
        "应有 1 个条件满足（device_status == OFFLINE）");
    assertEquals(1, result.failedConditions().size(),
        "应有 1 个条件失败（duration_minutes = 5 <= 10）");
  }

  /**
   * 测试 R004 规则边界场景（刚好满足时长条件）。
   *
   * 条件满足：device_status == "OFFLINE"，duration_minutes = 10（恰好等于阈值，应满足 > 10 为否）
   * 预期结果：规则不命中（因为条件是 duration_minutes > 10，10 不大于 10）
   */
  @Test
  @DisplayName("testR004Boundary_ExactThreshold - 离线时长恰好 10 分钟（边界），规则不应命中")
  void testR004Boundary_ExactThreshold() {
    // 构造边界测试数据（恰好 10 分钟）
    Map<String, Object> testData = new HashMap<>();
    testData.put("device_status", "OFFLINE");
    testData.put("duration_minutes", 10.0);

    // 调用规则引擎的测试方法
    RuleTestRequest request = offlineRuleRequest();
    RuleTestResponse result = ruleEngineService.test(request, testData);

    // 验证结果
    System.out.println("\n>>> 测试结果：R004 匹配状态（边界）= " + (result.triggered() ? "✅ 命中" : "❌ 不命中"));
    System.out.println("    测试数据: " + testData);
    System.out.println("    满足条件数: " + result.matchedConditions().size());
    System.out.println("    失败条件数: " + result.failedConditions().size());
    System.out.println("    详情消息: " + result.message());

    // 核心断言：规则应该不命中（边界值不满足 > 10）
    assertFalse(result.triggered(),
        "R004 规则不应该命中：duration_minutes=10 不满足 > 10 的条件");

    // 辅助断言
    assertEquals(1, result.matchedConditions().size());
    assertEquals(1, result.failedConditions().size());
  }

  /**
   * 测试 R004 规则边界场景（刚好超过时长条件）。
   *
   * 条件满足：device_status == "OFFLINE"，duration_minutes = 11（刚好大于 10）
   * 预期结果：规则命中
   */
  @Test
  @DisplayName("testR004Boundary_JustAboveThreshold - 离线时长 11 分钟（刚好超过阈值），规则应命中")
  void testR004Boundary_JustAboveThreshold() {
    // 构造边界测试数据（恰好 11 分钟）
    Map<String, Object> testData = new HashMap<>();
    testData.put("device_status", "OFFLINE");
    testData.put("duration_minutes", 11.0);

    // 调用规则引擎的测试方法
    RuleTestRequest request = offlineRuleRequest();
    RuleTestResponse result = ruleEngineService.test(request, testData);

    // 验证结果
    System.out.println("\n>>> 测试结果：R004 匹配状态（边界+1）= " + (result.triggered() ? "✅ 命中" : "❌ 不命中"));
    System.out.println("    测试数据: " + testData);
    System.out.println("    满足条件数: " + result.matchedConditions().size());
    System.out.println("    失败条件数: " + result.failedConditions().size());
    System.out.println("    详情消息: " + result.message());

    // 核心断言：规则应该命中
    assertTrue(result.triggered(),
        "R004 规则应该命中：duration_minutes=11 > 10，条件满足");

    // 辅助断言
    assertEquals(2, result.matchedConditions().size(),
        "应有 2 个条件满足");
    assertEquals(0, result.failedConditions().size(),
        "不应有条件失败");
  }
}
