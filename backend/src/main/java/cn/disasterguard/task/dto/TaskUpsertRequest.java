package cn.disasterguard.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TaskUpsertRequest(
    @NotBlank(message = "请输入任务名称") String name,
    @NotBlank(message = "请选择任务类型") String type,
    String owner,
    String regionCode,
    @NotBlank(message = "请选择任务状态") String status,
    @NotNull(message = "请输入完成进度") Integer progress
) {
}
