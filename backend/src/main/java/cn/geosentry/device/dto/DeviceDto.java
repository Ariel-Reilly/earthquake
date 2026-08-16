package cn.geosentry.device.dto;

import java.time.OffsetDateTime;

public record DeviceDto(
    Long id,
    String deviceCode,
    String name,
    String type,
    String hazardCode,
    String regionCode,
    String state,
    Integer battery,
    String signal,
    OffsetDateTime lastReportAt,
    String last,
    String point
) {
}
