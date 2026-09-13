package cn.geosentry.ruleengine.dto;

import java.util.List;

/**
 * 规则执行日志 DTO
 */
public record RuleExecutionLogDto(
    Long id,
    Long ruleId,
    String ruleName,
    Boolean triggered,
    String inputData,
    String result,
    String executedAt
) {}
