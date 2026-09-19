package cn.disasterguard.inspection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record GpsCheckRequest(
    @NotBlank(message = "请输入任务编号") String taskCode,
    @NotBlank(message = "请输入灾害点编号") String hazardCode,
    @NotNull(message = "请输入定位经度") BigDecimal lon,
    @NotNull(message = "请输入定位纬度") BigDecimal lat,
    BigDecimal allowedRadius,
    String inspector
) {
}
