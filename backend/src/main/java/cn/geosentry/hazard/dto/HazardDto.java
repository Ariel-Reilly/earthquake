package cn.geosentry.hazard.dto;

import java.time.OffsetDateTime;

public record HazardDto(
    Long id,
    String hazardCode,
    String name,
    String type,
    String level,
    String regionCode,
    String owner,
    String status,
    Double lon,
    Double lat,
    String threatObject,
    OffsetDateTime lastInspectionAt,
    String distance
) {
}
