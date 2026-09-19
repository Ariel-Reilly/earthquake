package cn.disasterguard.ruleengine.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * 创建/更新规则的请求 DTO
 */
public record RuleCreateRequest(
    String ruleCode,  // 更新时传入，创建时自动生成

    @NotBlank(message = "规则名称不能为空")
    String name,

    String disasterType,

    String level,

    Integer priority,

    RuleConditions conditions,

    List<RuleAction> actions,

    String dslText,

    Boolean enabled
) {
  public record RuleConditions(
      String operator,
      List<ConditionItem> items
  ) {}

  public record ConditionItem(
      String field,
      String op,
      Object value,
      String unit
  ) {}

  public record RuleAction(
      String type,
      Map<String, Object> params
  ) {}
}
