package cn.geosentry.ai.dto;

import java.time.OffsetDateTime;

/** AI 分析记录 DTO。 */
public record AiAnalysisRecordDto(
    Long id,
    String businessType,
    String businessCode,
    String modelName,
    String resultJson,
    Double confidence,
    OffsetDateTime createdAt
) {
}
