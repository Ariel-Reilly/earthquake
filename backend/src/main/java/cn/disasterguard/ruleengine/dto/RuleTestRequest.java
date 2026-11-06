package cn.disasterguard.ruleengine.dto;

import java.util.Map;

/**
 * 规则测试请求 DTO
 */
public record RuleTestRequest(
    Long ruleId,        // 规则 ID（优先）
    RuleConditions conditions,  // 直接传入条件（当 ruleId 为空时）
    Map<String, Object> inputData  // 输入数据
) {
  public record RuleConditions(
      String operator,
      java.util.List<ConditionItem> items
  ) {}

  public record ConditionItem(
      String field,
      String op,
      Object value,
      String unit
  ) {}
}
