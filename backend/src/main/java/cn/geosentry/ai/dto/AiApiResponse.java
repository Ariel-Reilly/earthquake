package cn.geosentry.ai.dto;

/** AI 服务统一响应包装。 */
public record AiApiResponse<T>(int code, String message, T data) {
}
