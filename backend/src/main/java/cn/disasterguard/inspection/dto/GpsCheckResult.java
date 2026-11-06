package cn.disasterguard.inspection.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record GpsCheckResult(
    String taskCode,
    String hazardCode,
    BigDecimal distanceM,
    BigDecimal allowedRadius,
    boolean withinFence,
    String fenceStatus,
    BigDecimal lon,
    BigDecimal lat,
    OffsetDateTime checkedAt
) {
}
