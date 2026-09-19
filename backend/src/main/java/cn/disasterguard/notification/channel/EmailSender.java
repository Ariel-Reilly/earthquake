package cn.disasterguard.notification.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 邮件发送器（Mock 实现，支持扩展 JavaMail/阿里云邮件）
 */
@Component
public class EmailSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

  @Value("${notification.email.enabled:true}")
  private boolean enabled;

  @Value("${notification.email.from:noreply@disasterguard.cn}")
  private String fromEmail;

  @Override
  public String getChannel() {
    return "EMAIL";
  }

  @Override
  public SendResult send(String target, String title, String content, Map<String, Object> variables) {
    if (!enabled) {
      log.warn("邮件发送已禁用");
      return SendResult.failure("邮件发送已禁用");
    }

    try {
      // TODO: 集成真实邮件服务（如 JavaMail/阿里云邮件）
      String messageId = UUID.randomUUID().toString();
      log.info("[EMAIL Mock] 发送邮件到 {}: {} - {}", target, title, content);

      // 模拟发送延迟
      Thread.sleep(80);

      return SendResult.success(messageId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return SendResult.failure("邮件发送被中断");
    } catch (Exception e) {
      log.error("邮件发送失败: {}", e.getMessage(), e);
      return SendResult.failure("邮件发送失败: " + e.getMessage());
    }
  }
}
