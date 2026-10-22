package cn.disasterguard.auth.register.dto;

/**
 * 图形验证码响应。
 *
 * @param captchaId  验证码 ID（校验时需原样回传）
 * @param imageBase64 图片的 Base64 编码（不含 data URI 前缀）
 */
public record CaptchaView(
    String captchaId,
    String imageBase64
) {
}
