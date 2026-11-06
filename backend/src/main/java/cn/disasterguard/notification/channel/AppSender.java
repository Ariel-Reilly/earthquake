package cn.disasterguard.notification.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * APP 推送发送器（Mock 实现，支持扩展极光/个推/FCM）
 */
@Component
public class AppSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(AppSender.class);

  @Value("${notification.app.enabled:true}")
  private boolean enabled;

  @Override
  public String getChannel() {
    return "APP";
  }

  @Override
  public SendResult send(String target, String title, String content, Map<String, Object> variables) {
    if (!enabled) {
      log.warn("APP 推送已禁用");
      return SendResult.failure("APP 推送已禁用");
    }

    try {
      // TODO: 集成真实推送服务（如极光/个推/FCM）
      String messageId = UUID.randomUUID().toString();
      log.info("[APP Mock] 发送推送到设备 {}: {} - {}", target, title, content);

      // 模拟发送延迟
      Thread.sleep(50);

      return SendResult.mockSuccess(messageId, "APP 推送网关未接入");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return SendResult.failure("APP 推送被中断");
    } catch (Exception e) {
      log.error("APP 推送发送失败: {}", e.getMessage(), e);
      return SendResult.failure("APP 推送发送失败: " + e.getMessage());
    }
  }
}
