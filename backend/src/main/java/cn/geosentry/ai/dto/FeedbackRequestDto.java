package cn.geosentry.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 反馈请求 DTO（v1.4.0）：值班员对 AI 判定结果给出"实际是否发生"的反馈。
 *
 * <p>字段命名沿用 snake_case（Python 风格），用 {@link JsonAlias} 让前端传 camelCase 也兼容。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeedbackRequestDto(
    @JsonAlias("hazardCode") String hazardCode,
    @JsonAlias("predictedLevel") String predictedLevel,
    @JsonAlias("predictedScore") Double predictedScore,
    @JsonAlias("predictedAt") String predictedAt,
    @JsonAlias("outcome") String outcome,
    @JsonAlias("occurred") Boolean occurred,
    @JsonAlias("confidence") Double confidence,
    @JsonAlias("feedbackBy") String feedbackBy,
    @JsonAlias("note") String note
) {
}