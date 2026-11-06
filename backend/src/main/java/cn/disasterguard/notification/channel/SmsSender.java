package cn.disasterguard.notification.channel;

import cn.disasterguard.common.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 短信发送器（Mock 实现，支持扩展阿里云/腾讯云短信网关）
 */
@Component
public class SmsSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(SmsSender.class);

  @Value("${notification.sms.enabled:true}")
  private boolean enabled;

  @Value("${notification.sms.signature:地脉智巡}")
  private String signature;

  @Override
  public String getChannel() {
    return "SMS";
  }

  @Override
  public SendResult send(String target, String title, String content, Map<String, Object> variables) {
    if (!enabled) {
      log.warn("短信发送已禁用");
      return SendResult.failure("短信发送已禁用");
    }

    try {
      // TODO: 集成真实短信网关（如阿里云/腾讯云）
      // 这里实现 Mock 逻辑，便于开发测试
      String messageId = UUID.randomUUID().toString();
      log.info("[SMS Mock] 发送短信到 {}: {} - {}", target, title, content);

      // 模拟发送延迟
      Thread.sleep(100);

      return SendResult.mockSuccess(messageId, "短信网关未接入");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return SendResult.failure("短信发送被中断");
    } catch (Exception e) {
      log.error("短信发送失败: {}", e.getMessage(), e);
      return SendResult.failure("短信发送失败: " + e.getMessage());
    }
  }
}
