package cn.disasterguard.notification.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record NotificationRecordDto(
    Long id,
    String bizType,
    String bizId,
    String disasterType,
    String level,
    String title,
    String content,
    List<String> channels,
    String status,
    String createdBy,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<NotificationReceiptDto> receipts,
    Map<String, Object> stats
) {}
