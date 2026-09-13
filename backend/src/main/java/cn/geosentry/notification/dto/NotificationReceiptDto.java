package cn.geosentry.notification.dto;

import java.time.OffsetDateTime;

public record NotificationReceiptDto(
    Long id,
    Long recordId,
    String channel,
    String target,
    String status,
    String errorMsg,
    Integer retryCount,
    OffsetDateTime sentAt,
    OffsetDateTime deliveredAt,
    OffsetDateTime createdAt
) {}
