package cn.geosentry.ruleengine.dto;

/**
 * 字段字典项 DTO
 */
public record FieldDictDto(
    Long id,
    String field,
    String type,
    String source,
    String unit,
    String description
) {}
