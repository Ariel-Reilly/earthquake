package cn.geosentry.warning.dto;

/** 预警更新请求（供 PUT /api/warnings/{id} 使用）。 */
public record WarningUpdateRequest(
    String title,
    String warningLevel,
    String status
) {
}
