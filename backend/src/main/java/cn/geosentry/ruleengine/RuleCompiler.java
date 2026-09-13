package cn.geosentry.ruleengine;

import cn.geosentry.ruleengine.dto.RuleDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 规则编译器：DSL 解析与条件树构建
 */
@Component
public class RuleCompiler {

  private static final Logger log = LoggerFactory.getLogger(RuleCompiler.class);

  private final ObjectMapper objectMapper;

  // DSL 操作符到评估函数的映射
  private static final Map<String, String> OP_LABELS = Map.of(
      ">", "大于",
      "<", "小于",
      ">=", "大于等于",
      "<=", "小于等于",
      "==", "等于",
      "!=", "不等于",
      "contains", "包含",
      "in", "在范围内"
  );

  public RuleCompiler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * 从条件对象构建条件树
   */
  public ConditionNode buildConditionTree(RuleDto.RuleConditions conditions) {
    if (conditions == null || conditions.items() == null || conditions.items().isEmpty()) {
      return null;
    }
    return buildNode(conditions);
  }

  private ConditionNode buildNode(RuleDto.RuleConditions conditions) {
    List<ConditionNode> children = new ArrayList<>();
    for (RuleDto.ConditionItem item : conditions.items()) {
      children.add(new ConditionNode(item.field(), item.op(), item.value(), item.unit()));
    }
    return new ConditionNode(conditions.operator(), children);
  }

  /**
   * 解析 DSL 文本为条件树
   *
   * DSL 示例:
   * rainfall_24h > 50 && slope > 30 && displacement_rate > 5 && soil_moisture > 80
   */
  public ConditionNode parseDsl(String dslText) {
    if (dslText == null || dslText.isBlank()) {
      return null;
    }

    try {
      // 分割 AND/OR
      List<String> conditions = splitByLogicalOps(dslText);

      List<ConditionNode> nodes = new ArrayList<>();
      for (String cond : conditions) {
        nodes.add(parseSingleCondition(cond.trim()));
      }

      if (nodes.isEmpty()) return null;
      if (nodes.size() == 1) return nodes.get(0);

      return new ConditionNode("AND", nodes);
    } catch (Exception e) {
      log.error("DSL 解析失败: {}", e.getMessage());
      return null;
    }
  }

  private List<String> splitByLogicalOps(String dsl) {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean inQuote = false;

    for (int i = 0; i < dsl.length(); i++) {
      char c = dsl.charAt(i);

      if (c == '\'' || c == '"') {
        inQuote = !inQuote;
        current.append(c);
      } else if (!inQuote) {
        if (c == '(') {
          depth++;
          current.append(c);
        } else if (c == ')') {
          depth--;
          current.append(c);
        } else if (depth == 0) {
          // 检查 AND/OR
          if (i + 3 <= dsl.length() && dsl.substring(i, i + 3).equals("AND")) {
            result.add(current.toString().trim());
            current = new StringBuilder();
            i += 2;
            continue;
          }
          if (i + 2 <= dsl.length() && dsl.substring(i, i + 2).equals("OR")) {
            result.add(current.toString().trim());
            current = new StringBuilder();
            i += 1;
            continue;
          }
          current.append(c);
        } else {
          current.append(c);
        }
      } else {
        current.append(c);
      }
    }

    String last = current.toString().trim();
    if (!last.isEmpty()) {
      result.add(last);
    }

    return result;
  }

  private ConditionNode parseSingleCondition(String cond) {
    // 解析 field > value 或 field >= value 等格式
    Pattern pattern = Pattern.compile("([\\w_]+)\\s*(>=|<=|>|<|==|!=|contains|in)\\s*([\\d.]+|\\[[^\\]]+\\]|'[^']*'|\"[^\"]*\")");
    Matcher matcher = pattern.matcher(cond);

    if (matcher.find()) {
      String field = matcher.group(1);
      String op = matcher.group(2);
      String valueStr = matcher.group(3);

      // 解析值
      Object value = parseValue(valueStr);

      return new ConditionNode(field, op, value, null);
    }

    log.warn("无法解析条件: {}", cond);
    return new ConditionNode("ERROR", cond, null, null);
  }

  private Object parseValue(String valueStr) {
    valueStr = valueStr.trim();

    // 数组格式: [1, 2, 3]
    if (valueStr.startsWith("[")) {
      List<String> items = new ArrayList<>();
      String content = valueStr.substring(1, valueStr.length() - 1);
      for (String item : content.split(",")) {
        items.add(item.trim());
      }
      return items;
    }

    // 字符串格式
    if (valueStr.startsWith("'") || valueStr.startsWith("\"")) {
      return valueStr.substring(1, valueStr.length() - 1);
    }

    // 数字格式
    try {
      if (valueStr.contains(".")) {
        return Double.parseDouble(valueStr);
      } else {
        return Integer.parseInt(valueStr);
      }
    } catch (NumberFormatException e) {
      return valueStr;
    }
  }

  /**
   * 从条件树生成 DSL 文本
   */
  public String toDsl(ConditionNode node) {
    if (node == null) return "";

    if (node.isLeaf()) {
      return formatLeafCondition(node);
    }

    List<String> childDsls = node.getChildren().stream()
        .map(this::toDsl)
        .collect(Collectors.toList());

    return "(" + String.join(" " + node.getOperator() + " ", childDsls) + ")";
  }

  private String formatLeafCondition(ConditionNode node) {
    if (node.getValue() == null) {
      return node.getField();
    }

    Object value = node.getValue();
    String valueStr;

    if (value instanceof String) {
      valueStr = "'" + value + "'";
    } else if (value instanceof List) {
      valueStr = "[" + String.join(", ", ((List<?>) value).stream().map(String::valueOf).toList()) + "]";
    } else {
      valueStr = String.valueOf(value);
    }

    return node.getField() + " " + node.getOperator() + " " + valueStr;
  }

  /**
   * 获取操作符标签
   */
  public String getOpLabel(String op) {
    return OP_LABELS.getOrDefault(op, op);
  }

  /**
   * 获取所有可用操作符
   */
  public List<Map<String, String>> getAvailableOperators() {
    return List.of(
        Map.of("value", ">", "label", "大于"),
        Map.of("value", "<", "label", "小于"),
        Map.of("value", ">=", "label", "大于等于"),
        Map.of("value", "<=", "label", "小于等于"),
        Map.of("value", "==", "label", "等于"),
        Map.of("value", "!=", "label", "不等于"),
        Map.of("value", "contains", "label", "包含"),
        Map.of("value", "in", "label", "在范围内")
    );
  }

  /**
   * 条件节点
   */
  public static class ConditionNode {
    private final String operator;
    private final Object value;
    private final String unit;
    private final List<ConditionNode> children;
    private final String field;

    // 内部节点（组合条件）
    public ConditionNode(String operator, List<ConditionNode> children) {
      this.operator = operator;
      this.children = children;
      this.value = null;
      this.unit = null;
      this.field = null;
    }

    // 叶子节点（单条件）
    public ConditionNode(String field, String operator, Object value, String unit) {
      this.field = field;
      this.operator = operator;
      this.value = value;
      this.unit = unit;
      this.children = null;
    }

    public boolean isLeaf() {
      return children == null;
    }

    public String getOperator() { return operator; }
    public Object getValue() { return value; }
    public String getUnit() { return unit; }
    public List<ConditionNode> getChildren() { return children; }
    public String getField() { return field; }
  }
}
