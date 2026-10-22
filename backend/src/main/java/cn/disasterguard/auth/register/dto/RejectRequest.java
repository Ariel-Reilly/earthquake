package cn.disasterguard.auth.register.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 拒绝申请请求体。
 *
 * @param reason 拒绝原因
 */
public record RejectRequest(
    @NotBlank(message = "拒绝原因不能为空")
    @Size(max = 255, message = "拒绝原因长度不能超过 255")
    String reason
) {
}
