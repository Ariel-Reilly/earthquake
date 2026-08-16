package cn.geosentry.device.dto;

import jakarta.validation.constraints.NotBlank;

public record DeviceUpsertRequest(
    @NotBlank(message = "请输入设备名称") String name,
    @NotBlank(message = "请选择设备类型") String type,
    String hazardCode,
    String regionCode,
    @NotBlank(message = "请选择在线状态") String state,
    Integer battery,
    String signal,
    String point
) {
}
