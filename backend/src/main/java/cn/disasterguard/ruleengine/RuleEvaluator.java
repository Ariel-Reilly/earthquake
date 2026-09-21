package cn.disasterguard.ruleengine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 规则评估器：执行规则条件判断
 */
@Component
public class RuleEvaluator {

  private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);

  private final RuleCompiler compiler;

  public RuleEvaluator(RuleCompiler compiler) {
    this.compiler = compiler;
  }

  /**
   * 评估条件树
   *
   * @param node     条件树
   * @param data     输入数据（字段 → 值）
   * @return 评估结果
   */
  public EvaluationResult evaluate(RuleCompiler.ConditionNode node, Map<String, Object> data) {
    if (node == null) {
      return new EvaluationResult(true, List.of(), List.of(), "无条件，默认通过");
    }

    List<String> matched = new ArrayList<>();
    List<String> failed = new ArrayList<>();

    boolean result = evaluateNode(node, data, matched, failed);

    String message;
    if (result) {
      message = String.format("条件满足（%d/%d）", matched.size(), matched.size() + failed.size());
    } else {
      message = String.format("条件不满足（满足 %d 项，失败 %d 项）", matched.size(), failed.size());
    }

    return new EvaluationResult(result, matched, failed, message);
  }

  private boolean evaluateNode(RuleCompiler.ConditionNode node, Map<String, Object> data,
                              List<String> matched, List<String> failed) {
    if (node.isLeaf()) {
      return evaluateCondition(node, data, matched, failed);
    }

    List<Boolean> childResults = new ArrayList<>();
    for (RuleCompiler.ConditionNode child : node.getChildren()) {
      childResults.add(evaluateNode(child, data, matched, failed));
    }

    String operator = node.getOperator().toUpperCase();
    if ("AND".equals(operator)) {
      return childResults.stream().allMatch(Boolean::booleanValue);
    } else if ("OR".equals(operator)) {
      return childResults.stream().anyMatch(Boolean::booleanValue);
    } else {
      // 默认 AND
      return childResults.stream().allMatch(Boolean::booleanValue);
    }
  }

  private boolean evaluateCondition(RuleCompiler.ConditionNode node, Map<String, Object> data,
                                    List<String> matched, List<String> failed) {
    String field = node.getField();
    Object actualValue = data.get(field);

    // 如果字段不存在，视为 0 或 false
    if (actualValue == null) {
      actualValue = 0;
    }

    Object thresholdValue = node.getValue();
    String operator = node.getOperator();
    String unit = node.getUnit();

    boolean result = compare(actualValue, operator, thresholdValue);

    String conditionDesc = formatConditionDesc(field, operator, thresholdValue, unit, actualValue);

    if (result) {
      matched.add(conditionDesc);
    } else {
      failed.add(conditionDesc);
    }

    return result;
  }

  private boolean compare(Object actual, String op, Object threshold) {
    if (actual == null) {
      return "==".equals(op) && threshold == null;
    }

    double actualNum = toDouble(actual);
    double thresholdNum = toDouble(threshold);

    return switch (op) {
      case ">" -> actualNum > thresholdNum;
      case "<" -> actualNum < thresholdNum;
      case ">=" -> actualNum >= thresholdNum;
      case "<=" -> actualNum <= thresholdNum;
      case "==" -> Math.abs(actualNum - thresholdNum) < 0.0001;
      case "!=" -> Math.abs(actualNum - thresholdNum) >= 0.0001;
      case "contains" -> actual.toString().contains(threshold.toString());
      case "in" -> threshold instanceof List && ((List<?>) threshold).contains(actual);
      default -> false;
    };
  }

  private double toDouble(Object value) {
    if (value == null) return 0.0;
    if (value instanceof Number) return ((Number) value).doubleValue();
    try {
      return Double.parseDouble(value.toString());
    } catch (NumberFormatException e) {
      return 0.0;
    }
  }

  private String formatConditionDesc(String field, String op, Object threshold, String unit, Object actual) {
    String thresholdStr = threshold instanceof Double
        ? String.format("%.2f", threshold)
        : String.valueOf(threshold);
    String actualStr = actual instanceof Double
        ? String.format("%.2f", actual)
        : String.valueOf(actual);
    String unitStr = unit != null ? " " + unit : "";

    return String.format("%s %s %s%s（实际: %s）",
        field, compiler.getOpLabel(op), thresholdStr, unitStr, actualStr);
  }

  /**
   * 评估结果
   */
  public record EvaluationResult(
      boolean passed,
      List<String> matchedConditions,
      List<String> failedConditions,
      String message
  ) {}
}
