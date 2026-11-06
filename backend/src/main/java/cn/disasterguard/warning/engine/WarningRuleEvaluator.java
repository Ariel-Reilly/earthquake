package cn.disasterguard.warning.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;

/**
 * 预警规则表达式求值器。
 *
 * <p>支持两类表达式：
 * <ul>
 *   <li>简单形式：{@code "disp_rate > 5"}（对照现有种子规则）</li>
 *   <li>JSON 结构化（规划）：{@code {"operator":"AND","conditions":[{"metric":"..","comparator":"<",">","value":..}]}}</li>
 * </ul>
 *
 * <p>注意：本类与 {@code ruleengine.RuleEvaluator} 职责不同——
 * <ul>
 *   <li>本类是"字符串表达式 → 阈值匹配"求值器（无 Spring Bean 依赖，纯静态工具），用于预警引擎。</li>
 *   <li>{@code ruleengine.RuleEvaluator} 是"AST 条件树 → 求值"（{@code @Component}，依赖 {@code RuleCompiler}），用于规则引擎。</li>
 * </ul>
 * 两者并存，命名上区分用途，避免误用。
 */
public class WarningRuleEvaluator {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WarningRuleEvaluator() {
  }

  /** 对给定指标快照求值，返回触发时对应的值；未命中返回 empty。 */
  public static Optional<Double> evaluate(String expression, Map<String, Double> metrics) {
    if (expression == null || expression.isBlank()) {
      return Optional.empty();
    }
    String expr = expression.trim();
    if (expr.startsWith("{")) {
      return evaluateJson(expr, metrics);
    }
    return evaluateSimple(expr, metrics);
  }

  /**
   * 求值字符串表达式。
   *
   * <p>v2.0 修复：旧实现只接受 3 个 token（{@code "metric op 50"}），而 data.sql 里的
   * 6 条种子规则全是 {@code "rainfall_24h > 50 AND slope > 30"} 这类复合表达式，
   * split 后 7 个 token → 一律 empty → 规则引擎永远不产生预警。
   * 现在支持：
   * <ul>
   *   <li>单条件：{@code rainfall_24h > 50}</li>
   *   <li>AND / OR 复合：{@code rainfall_24h > 50 AND slope > 30}</li>
   *   <li>右侧引用另一个指标：{@code water_level > warning_level}</li>
   * </ul>
   * 右侧为非数字且不是已知指标（如字符串常量 OFFLINE）时该条件视为不可判定，
   * 复合 AND 下整条规则不命中，不会误报。
   */
  private static Optional<Double> evaluateSimple(String expr, Map<String, Double> metrics) {
    boolean and = true;
    String[] clauses;
    if (expr.contains(" AND ")) {
      clauses = expr.split("\\s+AND\\s+");
    } else if (expr.contains(" OR ")) {
      clauses = expr.split("\\s+OR\\s+");
      and = false;
    } else {
      clauses = new String[]{expr};
    }
    Double matched = null;
    boolean anyOk = false;
    for (String clause : clauses) {
      String[] tokens = clause.trim().split("\\s+");
      if (tokens.length != 3) {
        return Optional.empty();
      }
      Double value = metrics.get(tokens[0]);
      double threshold;
      try {
        threshold = Double.parseDouble(tokens[2]);
      } catch (NumberFormatException e) {
        Double referenced = metrics.get(tokens[2]);
        if (referenced == null) {
          // 字符串常量（OFFLINE 等）无法用数值指标判定，不猜测、不误报
          return Optional.empty();
        }
        threshold = referenced;
      }
      if (value == null) {
        if (and) {
          return Optional.empty();
        }
        continue;
      }
      boolean ok = matches(value, tokens[1], threshold);
      if (and && !ok) {
        return Optional.empty();
      }
      if (ok) {
        anyOk = true;
        matched = value;
      }
    }
    return anyOk ? Optional.ofNullable(matched) : Optional.empty();
  }

  private static Optional<Double> evaluateJson(String expr, Map<String, Double> metrics) {
    // 结构化表达式骨架：解析 { metric, comparator, value }，按 AND 组合。
    try {
      com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(expr);
      com.fasterxml.jackson.databind.JsonNode conditions = node.path("conditions");
      boolean and = !"OR".equalsIgnoreCase(node.path("operator").asText());
      Double matchedValue = null;
      for (com.fasterxml.jackson.databind.JsonNode c : conditions) {
        String metric = c.path("metric").asText();
        String comparator = c.path("comparator").asText();
        double threshold = c.path("value").asDouble();
        Double value = metrics.get(metric);
        if (value == null) {
          if (and) {
            return Optional.empty();
          }
          continue;
        }
        boolean ok = matches(value, comparator, threshold);
        if (and && !ok) {
          return Optional.empty();
        }
        if (!and && ok) {
          return Optional.of(value);
        }
        if (ok) {
          matchedValue = value;
        }
      }
      return Optional.ofNullable(matchedValue);
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private static boolean matches(double value, String operator, double threshold) {
    return switch (operator) {
      case ">", "gt" -> value > threshold;
      case ">=", "gte" -> value >= threshold;
      case "<", "lt" -> value < threshold;
      case "<=", "lte" -> value <= threshold;
      case "==", "=", "eq" -> value == threshold;
      case "!=", "<>", "ne" -> value != threshold;
      default -> false;
    };
  }
}
