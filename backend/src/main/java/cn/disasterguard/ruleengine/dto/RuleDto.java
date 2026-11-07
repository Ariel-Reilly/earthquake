package cn.disasterguard.ruleengine.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
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
    String updatedAt,
    /**
     * R10 P1-5：预警引擎（warning_rule）里真正生效的表达式。
     * null / 空 表示这条规则只在模板库，不会触发预警。
     */
    String warningRuleExpression,
    /** 是否已下发预警引擎并处于启用状态（页面据此标注"已生效/未生效"）。 */
    Boolean warningRuleActive
) {
  /**
   * 规则条件
   * 支持 AND/OR 组合条件
   */
  public record RuleConditions(
      @JsonAlias("logic")
      String operator,  // AND / OR
      @JsonAlias("conditions")
      List<ConditionItem> items
  ) {}

  /**
   * 单个条件项
   */
  public record ConditionItem(
      String field,     // 字段名
      @JsonAlias("operator")
      String op,        // 操作符: >, <, >=, <=, ==, !=, contains, in
      Object value,     // 比较值
      String unit       // 单位（可选，仅用于日志展示）
  ) {}

  /**
   * 规则动作
   */
  public record RuleAction(
      String type,      // CREATE_EVENT, PUSH_CONFIRM, SEND_NOTIFICATION
      Map<String, Object> params
  ) {}
}
