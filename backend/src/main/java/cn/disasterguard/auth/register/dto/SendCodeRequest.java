package cn.disasterguard.auth.register.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 验证码下发请求体。
 *
 * @param type   渠道类型：phone / email
 * @param target 手机号或邮箱地址
 */
public record SendCodeRequest(
    @NotBlank(message = "验证码类型不能为空")
    @Pattern(regexp = "^(phone|email)$", message = "验证码类型仅支持 phone / email")
    String type,

    @NotBlank(message = "接收地址不能为空")
    String target
) {
}
