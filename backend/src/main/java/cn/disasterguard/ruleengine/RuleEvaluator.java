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

    boolean result = compare(field, actualValue, operator, thresholdValue);

    String conditionDesc = formatConditionDesc(field, operator, thresholdValue, unit, actualValue);

    if (result) {
      matched.add(conditionDesc);
    } else {
      failed.add(conditionDesc);
    }

    return result;
  }

  /**
   * 条件比较。
   *
   * <p>R3 P1-8：数值类比较要求 actual 与 threshold <b>都能</b>解析为数字，
   * 任一解析失败则记 warn 并判定为<b>不成立</b>（不触发）。
   *
   * <p>修复前 {@code toDouble} 解析失败静默返回 0.0，危害是双向的：
   * 实测值损坏时 {@code rainfall_24h > 50} 变成 {@code 0 > 50} → <b>漏报</b>；
   * 阈值配错时 {@code 100 > 0} → <b>误报</b>。两者在灾情场景都不可接受。
   *
   * @param field 字段名，用于日志输出
   * @param actual 实际值
   * @param op 操作符
   * @param threshold 阈值
   * @return 比较结果
   */
  private boolean compare(String field, Object actual, String op, Object threshold) {
    if (actual == null) {
      return "==".equals(op) && threshold == null;
    }

    // 非数值运算符不要求解析为数字，保持原有语义
    if ("contains".equals(op)) {
      return threshold != null && actual.toString().contains(threshold.toString());
    }
    if ("in".equals(op)) {
      return threshold instanceof List && ((List<?>) threshold).contains(actual);
    }

    Double actualNum = toDoubleOrNull(actual);
    Double thresholdNum = toDoubleOrNull(threshold);
    if (actualNum == null || thresholdNum == null) {
      log.warn("规则条件数值无法解析，判定为不成立（不触发）：field={}, actual={}, op={}, threshold={}",
          field, actual, op, threshold);
      return false;
    }

    return switch (op) {
      case ">" -> actualNum > thresholdNum;
      case "<" -> actualNum < thresholdNum;
      case ">=" -> actualNum >= thresholdNum;
      case "<=" -> actualNum <= thresholdNum;
      case "==" -> Math.abs(actualNum - thresholdNum) < 0.0001;
      case "!=" -> Math.abs(actualNum - thresholdNum) >= 0.0001;
      default -> {
        // 【修复 P2-4】未知操作符 WARN 日志：记录规则配置错误，但不触发告警（返回 false）
        log.warn("规则评估遇到未知操作符，判定为不成立（不触发）：field={}, op={}, actual={}, threshold={}",
            field, op, actual, threshold);
        yield false;
      }
    };
  }

  /**
   * 解析为 double；无法解析时返回 {@code null}。
   * <b>不要</b>返回 0.0 —— 0.0 是合法业务值，会把"数据缺失/格式损坏"
   * 伪装成"数值为 0"并参与阈值比较。
   */
  private Double toDoubleOrNull(Object value) {
    if (value == null) return null;
    if (value instanceof Number) return ((Number) value).doubleValue();
    try {
      return Double.parseDouble(value.toString().trim());
    } catch (NumberFormatException e) {
      return null;
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
