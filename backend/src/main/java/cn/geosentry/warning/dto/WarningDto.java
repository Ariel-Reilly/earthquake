package cn.geosentry.warning.dto;

import java.time.OffsetDateTime;

public record WarningDto(
    Long id,
    String warningCode,
    String level,
    String cssClass,
    String title,
    String desc,
    String time,
    String point,
    String status,
    String hazardCode,
    String ruleCode,
    String triggerValue,
    OffsetDateTime triggeredAt,
    String closeReason,
    String closedBy,
    OffsetDateTime confirmedAt,
    OffsetDateTime dispatchedAt,
    OffsetDateTime closedAt
) {
}
