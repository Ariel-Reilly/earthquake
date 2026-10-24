package cn.disasterguard.auth.register.config;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.teaopenapi.models.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云号码认证服务（Dypnsapi）Client 配置。
 *
 * <p>使用号码认证服务的短信认证功能替代普通短信（dysmsapi）。
 * 优点：无需企业资质，个人开发者可直接使用系统赠送的测试签名和模板。
 *
 * <p>仅在 {@code disasterguard.sms.enabled=true} 时初始化 Client Bean。
 */
@Configuration
public class SmsConfig {

  private static final Logger log = LoggerFactory.getLogger(SmsConfig.class);

  /**
   * 创建阿里云号码认证服务客户端 Bean。
   *
   * <p>条件：{@code disasterguard.sms.enabled=true} 时才初始化。
   * 未启用时不创建 Bean，避免无意义的连接开销。
   *
   * @param properties 短信配置属性
   * @return 阿里云号码认证服务 Client 实例
   * @throws RuntimeException 配置项缺失或初始化失败时抛出
   */
  @Bean
  @ConditionalOnProperty(prefix = "disasterguard.sms", name = "enabled", havingValue = "true")
  public Client aliyunDypnsClient(SmsProperties properties) {
    try {
      // 校验必填配置项
      if (properties.getAccessKeyId() == null || properties.getAccessKeyId().isBlank()) {
        throw new IllegalArgumentException("阿里云短信配置缺失：disasterguard.sms.access-key-id 不能为空");
      }
      if (properties.getAccessKeySecret() == null || properties.getAccessKeySecret().isBlank()) {
        throw new IllegalArgumentException("阿里云短信配置缺失：disasterguard.sms.access-key-secret 不能为空");
      }
      if (properties.getSignName() == null || properties.getSignName().isBlank()) {
        throw new IllegalArgumentException("阿里云短信配置缺失：disasterguard.sms.sign-name 不能为空");
      }
      if (properties.getTemplateCode() == null || properties.getTemplateCode().isBlank()) {
        throw new IllegalArgumentException("阿里云短信配置缺失：disasterguard.sms.template-code 不能为空");
      }

      // 初始化阿里云 SDK Config
      Config config = new Config()
          .setAccessKeyId(properties.getAccessKeyId())
          .setAccessKeySecret(properties.getAccessKeySecret())
          .setEndpoint(properties.getEndpoint());

      Client client = new Client(config);
      log.info("阿里云号码认证服务 Client 初始化成功 [endpoint={}]", properties.getEndpoint());
      return client;
    } catch (Exception e) {
      log.error("阿里云号码认证服务 Client 初始化失败: {}", e.getMessage(), e);
      throw new RuntimeException("阿里云号码认证服务初始化失败: " + e.getMessage(), e);
    }
  }
}
