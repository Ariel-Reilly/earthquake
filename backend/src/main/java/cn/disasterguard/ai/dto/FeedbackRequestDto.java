package cn.disasterguard.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 反馈请求 DTO（v1.4.0）：值班员对 AI 判定结果给出"实际是否发生"的反馈。
 *
 * <p><b>本 DTO 是双向的</b>：
 * <ul>
 *   <li>入站：前端（{@code AiInsightPage.vue}）POST 到 {@code /api/ai/feedback}，
 *       Java 端用 {@code @RequestBody FeedbackRequestDto} 接收，约定 camelCase。</li>
 *   <li>出站：Java → Python（FastAPI Pydantic），Python 字段名固定 snake_case。</li>
 * </ul>
 *
 * <p>因此每个字段同时声明：
 * <ul>
 *   <li>{@code @JsonProperty("snake_case")} —— 锁定序列化（出站）字段名，与 Python 对齐；</li>
 *   <li>{@code @JsonAlias("camelCase")} —— 兼容入站时前端发的 camelCase。</li>
 * </ul>
 *
 * <p>历史版本仅用 {@code @JsonAlias("camelCase")}（别名 = 字段名，no-op），
 * 导致 Jackson 出站仍输出 camelCase，Python 收到 {@code hazardCode} 等无法 Pydantic 解析 → 422。
 * 本次修复同时声明两端契约。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeedbackRequestDto(
    @JsonProperty("hazard_code") @JsonAlias("hazardCode") String hazardCode,
    @JsonProperty("predicted_level") @JsonAlias("predictedLevel") String predictedLevel,
    @JsonProperty("predicted_score") @JsonAlias("predictedScore") Double predictedScore,
    @JsonProperty("predicted_at") @JsonAlias("predictedAt") String predictedAt,
    @JsonProperty("outcome") @JsonAlias("outcome") String outcome,
    @JsonProperty("occurred") @JsonAlias("occurred") Boolean occurred,
    @JsonProperty("confidence") @JsonAlias("confidence") Double confidence,
    @JsonProperty("feedback_by") @JsonAlias("feedbackBy") String feedbackBy,
    @JsonProperty("note") @JsonAlias("note") String note
) {
}
