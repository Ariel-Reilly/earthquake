package cn.disasterguard.emergency.dto;

import java.time.OffsetDateTime;

/** 应急事件 DTO。 */
public record EmergencyDto(
    Long id,
    String eventCode,
    String warningCode,
    String hazardCode,
    String title,
    String status,
    String commander,
    String stepName,
    String resultSummary,
    OffsetDateTime startedAt,
    OffsetDateTime closedAt
) {
}
