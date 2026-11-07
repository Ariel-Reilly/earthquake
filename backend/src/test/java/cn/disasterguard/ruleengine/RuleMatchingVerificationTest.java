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
 * 规则引擎实际匹配验证测试。
 *
 * <p><b>R10 P1-5 适配</b>：原先按数据库主键 {@code 4L=R004 设备离线}、
 * {@code 1L=R001 雨量+坡度} 写死，属于把测试绑在业务种子数据上。
 * P1-5 之后 rule 表由 warning_rule 镜像生成（R004=倾角&gt;5、R001=雨量&gt;50），
 * 主键虽未变但语义已变，用例必然失败。现改为自带条件树，验证目标回归到
 * 「编译器 + 求值器对复合条件的判定」本身。
 */
@SpringBootTest
@DisplayName("规则引擎匹配验证")
public class RuleMatchingVerificationTest {

  @Autowired
  private RuleEngineService ruleEngineService;

  @Autowired
  private RuleCompiler ruleCompiler;

  /** device_status == "OFFLINE" AND duration_minutes > 10。 */
  private static RuleTestRequest offlineRuleRequest() {
    return new RuleTestRequest(null, new RuleTestRequest.RuleConditions(
        "AND",
        List.of(
            new RuleTestRequest.ConditionItem("device_status", "==", "OFFLINE", null),
            new RuleTestRequest.ConditionItem("duration_minutes", ">", 10, "min"))), null);
  }

  /** rainfall_24h > 50 AND slope > 30。 */
  private static RuleTestRequest rainfallSlopeRuleRequest() {
    return new RuleTestRequest(null, new RuleTestRequest.RuleConditions(
        "AND",
        List.of(
            new RuleTestRequest.ConditionItem("rainfall_24h", ">", 50, "mm"),
            new RuleTestRequest.ConditionItem("slope", ">", 30, "deg"))), null);
  }

  @Test
  @DisplayName("规则 R004：设备离线条件满足，应命中")
  void testRuleR004_Match() {
    Map<String, Object> testData = new HashMap<>();
    testData.put("device_status", "OFFLINE");
    testData.put("duration_minutes", 15);

    RuleTestRequest request = offlineRuleRequest();
    RuleTestResponse result = ruleEngineService.test(request, testData);

    System.out.println("\n【规则 R004 - 命中场景】");
    System.out.println("测试数据: " + testData);
    System.out.println("匹配状态: " + (result.triggered() ? "✅ 命中" : "❌ 不命中"));
    System.out.println("消息: " + result.message());
    System.out.println("满足条件: " + result.matchedConditions());
    System.out.println("失败条件: " + result.failedConditions());

    assertTrue(result.triggered(), "规则应该命中");
    assertEquals(2, result.matchedConditions().size(), "应有 2 个条件满足");
  }

  @Test
  @DisplayName("规则 R004：设备状态不是 OFFLINE，应不命中")
  void testRuleR004_NoMatch_Status() {
    Map<String, Object> testData = new HashMap<>();
    testData.put("device_status", "ONLINE");
    testData.put("duration_minutes", 15);

    RuleTestRequest request = offlineRuleRequest();
    RuleTestResponse result = ruleEngineService.test(request, testData);

    System.out.println("\n【规则 R004 - 不命中场景 1】");
    System.out.println("测试数据: " + testData);
    System.out.println("匹配状态: " + (result.triggered() ? "✅ 命中" : "❌ 不命中"));

    assertFalse(result.triggered(), "规则应该不命中");
    assertEquals(1, result.failedConditions().size(), "应有 1 个条件失败");
  }

  @Test
  @DisplayName("规则 R004：设备离线时间不够，应不命中")
  void testRuleR004_NoMatch_Duration() {
    Map<String, Object> testData = new HashMap<>();
    testData.put("device_status", "OFFLINE");
    testData.put("duration_minutes", 5);

    RuleTestRequest request = offlineRuleRequest();
    RuleTestResponse result = ruleEngineService.test(request, testData);

    System.out.println("\n【规则 R004 - 不命中场景 2】");
    System.out.println("测试数据: " + testData);
    System.out.println("匹配状态: " + (result.triggered() ? "✅ 命中" : "❌ 不命中"));

    assertFalse(result.triggered(), "规则应该不命中");
    assertEquals(1, result.failedConditions().size(), "应有 1 个条件失败");
  }

  @Test
  @DisplayName("规则 R001：数值条件满足，应命中")
  void testRuleR001_Match() {
    Map<String, Object> testData = new HashMap<>();
    testData.put("rainfall_24h", 60);
    testData.put("slope", 35);

    RuleTestRequest request = rainfallSlopeRuleRequest();
    RuleTestResponse result = ruleEngineService.test(request, testData);

    System.out.println("\n【规则 R001 - 命中场景】");
    System.out.println("测试数据: " + testData);
    System.out.println("匹配状态: " + (result.triggered() ? "✅ 命中" : "❌ 不命中"));

    assertTrue(result.triggered(), "规则应该命中");
    assertEquals(2, result.matchedConditions().size(), "应有 2 个条件满足");
  }

  @Test
  @DisplayName("规则 R001：条件不满足，应不命中")
  void testRuleR001_NoMatch() {
    Map<String, Object> testData = new HashMap<>();
    testData.put("rainfall_24h", 40);
    testData.put("slope", 35);

    RuleTestRequest request = rainfallSlopeRuleRequest();
    RuleTestResponse result = ruleEngineService.test(request, testData);

    System.out.println("\n【规则 R001 - 不命中场景】");
    System.out.println("测试数据: " + testData);
    System.out.println("匹配状态: " + (result.triggered() ? "✅ 命中" : "❌ 不命中"));

    assertFalse(result.triggered(), "规则应该不命中");
    assertEquals(1, result.failedConditions().size(), "应有 1 个条件失败");
  }

  @Test
  @DisplayName("未知操作符：应返回 false 且记录 WARN 日志")
  void testUnknownOperator() {
    System.out.println("\n【测试未知操作符 - 应记录 WARN 日志】");
    
    // 构造包含未知操作符的条件树
    RuleCompiler.ConditionNode unknownOpNode = new RuleCompiler.ConditionNode(
        "rainfall_24h",          // field
        "UNKNOWN_OP",            // 未知操作符
        50,                      // value
        "mm"                     // unit
    );

    RuleEvaluator evaluator = new RuleEvaluator(ruleCompiler);
    Map<String, Object> testData = new HashMap<>();
    testData.put("rainfall_24h", 60);

    RuleEvaluator.EvaluationResult result = evaluator.evaluate(unknownOpNode, testData);

    System.out.println("条件结果: " + (result.passed() ? "通过" : "不通过（符合预期）"));
    System.out.println("消息: " + result.message());

    assertFalse(result.passed(), "未知操作符条件应该不通过");
    assertEquals(0, result.matchedConditions().size(), "不应有满足的条件");
    assertEquals(1, result.failedConditions().size(), "应有 1 个失败的条件");
    
    System.out.println("✅ 验证成功：未知操作符被正确处理，日志应已记录 WARN");
  }
}

