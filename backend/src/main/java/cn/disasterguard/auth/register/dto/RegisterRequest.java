package cn.disasterguard.auth.register.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册申请请求体。
 *
 * <p>该 DTO 只用于入参，字段中的密码/身份证属于敏感信息：
 * {@code OperationLogAspect} 会对 password 字段做脱敏，身份证在落库前会加密。
 *
 * @param username        登录用户名
 * @param password        登录密码
 * @param confirmPassword 确认密码
 * @param realName        真实姓名
 * @param phone           手机号
 * @param email           邮箱
 * @param idCard          身份证号
 * @param department      所属部门
 * @param position        岗位（可空）
 * @param roleCode        申请角色
 * @param regionCode      所属区域编码
 * @param applyReason     申请理由
 * @param inviteCode      邀请码（申请 admin 角色时必填）
 * @param captchaId       图形验证码 ID
 * @param captchaCode     图形验证码
 * @param phoneCode       短信验证码
 * @param emailCode       邮箱验证码
 * @param agreed          是否已同意用户协议
 */
public record RegisterRequest(
    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 32, message = "用户名长度需在 4-32 之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    String username,

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在 8-64 之间")
    String password,

    @NotBlank(message = "确认密码不能为空")
    String confirmPassword,

    @NotBlank(message = "姓名不能为空")
    @Size(min = 2, max = 32, message = "姓名长度需在 2-32 之间")
    String realName,

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    String phone,

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    String email,

    @NotBlank(message = "身份证号不能为空")
    @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "身份证号格式不正确")
    String idCard,

    @NotBlank(message = "部门不能为空")
    @Size(max = 128, message = "部门长度不能超过 128")
    String department,

    @Size(max = 64, message = "岗位长度不能超过 64")
    String position,

    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "^(attendant|maintenance|admin)$", message = "角色不合法")
    String roleCode,

    @NotBlank(message = "区域编码不能为空")
    String regionCode,

    @NotBlank(message = "申请理由不能为空")
    @Size(min = 10, max = 500, message = "申请理由长度需在 10-500 之间")
    String applyReason,

    String inviteCode,

    @NotBlank(message = "图形验证码 ID 不能为空")
    String captchaId,

    @NotBlank(message = "图形验证码不能为空")
    String captchaCode,

    @NotBlank(message = "短信验证码不能为空")
    String phoneCode,

    @NotBlank(message = "邮箱验证码不能为空")
    String emailCode,

    @AssertTrue(message = "必须同意用户协议")
    boolean agreed
) {
}
