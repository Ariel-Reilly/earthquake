package cn.disasterguard.auth.register.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云号码认证服务配置属性（用于短信验证码发送）。
 *
 * <p>使用阿里云号码认证服务（Dypnsapi）的短信认证功能，无需企业资质。
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code disasterguard.sms.enabled}: 是否启用阿里云短信（false 时降级到日志输出）</li>
 *   <li>{@code disasterguard.sms.access-key-id}: RAM 用户 AccessKey ID</li>
 *   <li>{@code disasterguard.sms.access-key-secret}: RAM 用户 AccessKey Secret</li>
 *   <li>{@code disasterguard.sms.sign-name}: 短信签名（阿里云赠送的测试签名）</li>
 *   <li>{@code disasterguard.sms.template-code}: 短信模板 CODE（阿里云赠送的测试模板）</li>
 *   <li>{@code disasterguard.sms.endpoint}: 阿里云号码认证服务端点（默认 dypnsapi.aliyuncs.com）</li>
 * </ul>
 *
 * <p>生产环境安全建议：
 * <ul>
 *   <li>通过环境变量 {@code ALIYUN_SMS_ACCESS_KEY_ID / ALIYUN_SMS_ACCESS_KEY_SECRET} 注入凭证；</li>
 *   <li>RAM 用户权限最小化原则：仅授权 {@code AliyunDypnsFullAccess} 或自定义策略限制到 SendSmsVerifyCode 操作；</li>
 *   <li>定期轮换 AccessKey。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "disasterguard.sms")
public class SmsProperties {

  /**
   * 是否启用阿里云短信发送。
   *
   * <p>false 时降级策略：
   * <ul>
   *   <li>prod 环境：抛出 {@code AppException(50301, "验证码服务未配置")}；</li>
   *   <li>非 prod 环境：日志输出验证码（便于本地联调）。</li>
   * </ul>
   */
  private boolean enabled = false;

  /**
   * 阿里云 RAM 用户 AccessKey ID。
   */
  private String accessKeyId;

  /**
   * 阿里云 RAM 用户 AccessKey Secret。
   */
  private String accessKeySecret;

  /**
   * 短信签名（阿里云号码认证服务赠送的测试签名）。
   */
  private String signName = "恒创联众";

  /**
   * 短信模板 CODE（阿里云号码认证服务赠送的测试模板）。
   *
   * <p>模板变量：
   * <ul>
   *   <li>{@code ${code}}: 我们业务端生成的 6 位验证码</li>
   *   <li>{@code ${min}}: 验证码有效时间（分钟数，固定传 5）</li>
   * </ul>
   */
  private String templateCode = "100001";

  /**
   * 阿里云号码认证服务端点。
   *
   * <p>默认为 {@code dypnsapi.aliyuncs.com}，特殊场景（如金融云）可能需要修改。
   */
  private String endpoint = "dypnsapi.aliyuncs.com";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getAccessKeyId() {
    return accessKeyId;
  }

  public void setAccessKeyId(String accessKeyId) {
    this.accessKeyId = accessKeyId;
  }

  public String getAccessKeySecret() {
    return accessKeySecret;
  }

  public void setAccessKeySecret(String accessKeySecret) {
    this.accessKeySecret = accessKeySecret;
  }

  public String getSignName() {
    return signName;
  }

  public void setSignName(String signName) {
    this.signName = signName;
  }

  public String getTemplateCode() {
    return templateCode;
  }

  public void setTemplateCode(String templateCode) {
    this.templateCode = templateCode;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }
}
