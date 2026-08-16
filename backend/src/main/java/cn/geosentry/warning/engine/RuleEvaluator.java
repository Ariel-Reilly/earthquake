package cn.geosentry.warning.engine;

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
 */
public class RuleEvaluator {

  private RuleEvaluator() {
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

  private static Optional<Double> evaluateSimple(String expr, Map<String, Double> metrics) {
    String[] tokens = expr.split("\\s+");
    if (tokens.length != 3) {
      return Optional.empty();
    }
    String metric = tokens[0];
    String operator = tokens[1];
    double threshold;
    try {
      threshold = Double.parseDouble(tokens[2]);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    Double value = metrics.get(metric);
    if (value == null) {
      return Optional.empty();
    }
    return matches(value, operator, threshold) ? Optional.of(value) : Optional.empty();
  }

  private static Optional<Double> evaluateJson(String expr, Map<String, Double> metrics) {
    // 结构化表达式骨架：解析 { metric, comparator, value }，按 AND 组合。
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(expr);
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
      default -> false;
    };
  }
}
