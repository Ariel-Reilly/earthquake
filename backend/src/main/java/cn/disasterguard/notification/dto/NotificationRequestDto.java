package cn.disasterguard.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

public record NotificationRequestDto(
    @NotBlank(message = "业务类型不能为空")
    String bizType,

    String bizId,

    String disasterType,

    String level,

    @NotBlank(message = "标题不能为空")
    String title,

    @NotBlank(message = "内容不能为空")
    String content,

    @NotEmpty(message = "目标用户不能为空")
    List<String> targetUsers,

    String targetArea,

    List<String> channels,

    String templateId,

    String priority,

    Map<String, Object> variables
) {}
