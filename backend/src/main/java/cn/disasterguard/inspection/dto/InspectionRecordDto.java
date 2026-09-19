package cn.disasterguard.inspection.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record InspectionRecordDto(
    Long id,
    String recordCode,
    String taskCode,
    String hazardCode,
    String inspector,
    OffsetDateTime inspectAt,
    BigDecimal signInLon,
    BigDecimal signInLat,
    BigDecimal distanceM,
    boolean abnormalFlag,
    String note,
    List<String> photoIds,
    Map<String, Object> formData
) {
}
