package cn.disasterguard.hazard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record HazardUpsertRequest(
    @NotBlank(message = "请输入灾害点名称") String name,
    @NotBlank(message = "请选择灾害类型") String type,
    @NotBlank(message = "请选择风险等级") String level,
    String regionCode,
    String owner,
    String status,
    @NotNull(message = "请输入经度") Double lon,
    @NotNull(message = "请输入纬度") Double lat,
    String threatObject
) {
}
