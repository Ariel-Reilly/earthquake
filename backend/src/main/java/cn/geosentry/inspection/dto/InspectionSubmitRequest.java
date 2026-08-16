package cn.geosentry.inspection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record InspectionSubmitRequest(
    @NotBlank(message = "请输入任务编号") String taskCode,
    @NotBlank(message = "请输入灾害点编号") String hazardCode,
    @NotNull(message = "请输入签到经度") BigDecimal lon,
    @NotNull(message = "请输入签到纬度") BigDecimal lat,
    BigDecimal allowedRadius,
    String inspector,
    Boolean abnormalFlag,
    String note,
    List<String> photoIds,
    Map<String, Object> formData
) {
}
