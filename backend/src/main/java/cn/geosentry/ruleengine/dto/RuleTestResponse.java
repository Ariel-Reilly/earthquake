package cn.geosentry.ruleengine.dto;

import java.util.List;

/**
 * 规则测试响应 DTO
 */
public record RuleTestResponse(
    boolean triggered,
    String message,
    List<String> matchedConditions,
    List<String> failedConditions,
    List<RuleActionResult> actionResults
) {
  public record RuleActionResult(
      String type,
      boolean executed,
      String result,
      String error
  ) {}
}
