package cn.disasterguard.ruleengine;

import cn.disasterguard.ruleengine.dto.RuleTestRequest;
import cn.disasterguard.ruleengine.dto.RuleTestResponse;
import cn.disasterguard.ruleengine.entity.Rule;
import cn.disasterguard.ruleengine.repository.RuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则引擎集成测试 - R004 规则验证。
 *
 * 目标：验证 R004 规则（设备离线告警）能否在底层正确命中。
 * R004 条件：device_status == "OFFLINE" AND duration_minutes > 10
 * 预期预警级别：蓝色预警
 */
@SpringBootTest
@DisplayName("规则引擎集成测试 - R004 验证")
public class RuleEngineIntegrationTest {

  @Autowired
  private RuleEngineService ruleEngineService;

  @Autowired
  private RuleRepository ruleRepository;

  private Long r004RuleId;

  /**
   * 在所有测试前，查询 R004 规则的实际 ID。
   */
  @BeforeEach
  void setUp() {
    Rule r004Rule = ruleRepository.findByRuleCode("R004")
        .orElseThrow(() -> new RuntimeException("R004 规则不存在，请检查数据库初始化"));
    r004RuleId = r004Rule.getId();
    System.out.println("【测试初始化】R004 规则 ID = " + r004RuleId);
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
    RuleTestRequest request = new RuleTestRequest(r004RuleId, null, null);
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
    RuleTestRequest request = new RuleTestRequest(r004RuleId, null, null);
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
    RuleTestRequest request = new RuleTestRequest(r004RuleId, null, null);
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
    RuleTestRequest request = new RuleTestRequest(r004RuleId, null, null);
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
    RuleTestRequest request = new RuleTestRequest(r004RuleId, null, null);
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
