package cn.disasterguard.ruleengine.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record RuleDto(
    Long id,
    String ruleCode,
    @NotBlank(message = "规则名称不能为空")
    String name,
    String disasterType,
    String level,
    Integer priority,
    RuleConditions conditions,
    List<RuleAction> actions,
    String dslText,
    Integer version,
    Boolean enabled,
    String createdBy,
    String createdAt,
    String updatedAt
) {
  /**
   * 规则条件
   * 支持 AND/OR 组合条件
   */
  public record RuleConditions(
      String operator,  // AND / OR
      List<ConditionItem> items
  ) {}

  /**
   * 单个条件项
   */
  public record ConditionItem(
      String field,     // 字段名
      String op,         // 操作符: >, <, >=, <=, ==, !=, contains, in
      Object value,     // 比较值
      String unit       // 单位（可选）
  ) {}

  /**
   * 规则动作
   */
  public record RuleAction(
      String type,      // CREATE_EVENT, PUSH_CONFIRM, SEND_NOTIFICATION
      Map<String, Object> params
  ) {}
}
