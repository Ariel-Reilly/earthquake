package cn.disasterguard.warning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.disasterguard.warning.engine.WarningRuleEvaluator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WarningRuleEvaluator} 求值语义测试。
 *
 * <p>重点回归：复合表达式（AND / OR）返回的值必须是<b>首个命中条款</b>的值。
 * 调用方（{@code WarningCreateService#doPersist}）用
 * {@code rule.metric_code + "=" + 返回值} 拼 trigger_value，而 {@code metric_code}
 * 通常就是表达式首项。旧实现每次命中都覆盖返回值，导致 AND 规则落库成
 * {@code rainfall_24h=5.85}（5.85 其实是 displacement_rate），
 * 标签与数值不属于同一指标。
 */
class WarningRuleEvaluatorTest {

  private static Map<String, Double> metrics(Object... kv) {
    Map<String, Double> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
    }
    return m;
  }

  @Test
  @DisplayName("单条件规则返回该条件本身的指标值")
  void singleConditionReturnsItsOwnValue() {
    Optional<Double> hit = WarningRuleEvaluator.evaluate("rainfall_24h > 50",
        metrics("rainfall_24h", 53.5));
    assertEquals(Optional.of(53.5), hit);
  }

  @Test
  @DisplayName("AND 复合规则返回首个条件（主指标）的值，而不是最后一个条件的值")
  void andRuleReturnsFirstClauseValue() {
    // R002 实测数据：雨量 80.89、位移 5.72
    Optional<Double> hit = WarningRuleEvaluator.evaluate(
        "rainfall_24h > 80 AND displacement_rate > 5",
        metrics("rainfall_24h", 80.89, "displacement_rate", 5.72));

    assertTrue(hit.isPresent(), "两个条件都满足时应命中");
    assertEquals(80.89, hit.get(), 1e-9,
        "必须返回首个条件 rainfall_24h 的值，否则 trigger_value 会写成 rainfall_24h=5.72");
  }

  @Test
  @DisplayName("AND 规则只要有一个条件不满足就不命中")
  void andRuleRequiresAllClauses() {
    // 水位已达 175.18，但 6 小时雨量只有 96.23，未到 100
    Optional<Double> hit = WarningRuleEvaluator.evaluate(
        "water_level > warning_level AND rainfall_6h > 100",
        metrics("water_level", 175.18, "warning_level", 175.0, "rainfall_6h", 96.23));
    assertTrue(hit.isEmpty(), "第二个条件不满足时整条 AND 规则不应命中");
  }

  @Test
  @DisplayName("右侧引用另一个指标（水位 > 警戒水位）可正常求值")
  void rightHandSideCanReferenceAnotherMetric() {
    Optional<Double> hit = WarningRuleEvaluator.evaluate(
        "water_level > warning_level",
        metrics("water_level", 175.47, "warning_level", 175.0));
    assertEquals(Optional.of(175.47), hit);
  }

  @Test
  @DisplayName("被引用的指标缺失时视为不可判定，不误报")
  void missingReferencedMetricIsNotEvaluable() {
    Optional<Double> hit = WarningRuleEvaluator.evaluate(
        "water_level > warning_level",
        metrics("water_level", 999.0));
    assertTrue(hit.isEmpty(), "缺少警戒水位基准时不能猜一个阈值去报警");
  }

  @Test
  @DisplayName("OR 复合规则返回首个满足条件（至多一个）的值")
  void orRuleReturnsMatchedValue() {
    Optional<Double> hit = WarningRuleEvaluator.evaluate(
        "tilt_angle > 5 OR crack_width_rate > 2",
        metrics("tilt_angle", 3.2, "crack_width_rate", 2.42));
    assertEquals(Optional.of(2.42), hit);
  }
}
