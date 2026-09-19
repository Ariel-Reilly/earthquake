package cn.disasterguard.task.dto;

import java.time.OffsetDateTime;

public record TaskDto(
    Long id,
    String taskCode,
    String name,
    String type,
    String owner,
    String regionCode,
    String status,
    Integer progress,
    OffsetDateTime startTime,
    OffsetDateTime dueTime
) {
}
