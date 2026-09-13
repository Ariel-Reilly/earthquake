package cn.geosentry.notification.channel;

import cn.geosentry.realtime.RealtimeBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 大屏推送发送器
 */
@Component
public class ScreenSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(ScreenSender.class);

  private final RealtimeBroadcastService broadcastService;

  @Value("${notification.screen.enabled:true}")
  private boolean enabled;

  public ScreenSender(RealtimeBroadcastService broadcastService) {
    this.broadcastService = broadcastService;
  }

  @Override
  public String getChannel() {
    return "SCREEN";
  }

  @Override
  public SendResult send(String target, String title, String content, Map<String, Object> variables) {
    if (!enabled) {
      log.warn("大屏推送已禁用");
      return SendResult.failure("大屏推送已禁用");
    }

    try {
      // 通过 WebSocket 推送到大屏
      String messageId = UUID.randomUUID().toString();
      log.info("[SCREEN Mock] 推送到大屏: {} - {}", title, content);

      // 构建大屏消息格式
      Map<String, Object> screenMessage = Map.of(
          "type", "NOTIFICATION",
          "id", messageId,
          "title", title,
          "content", content,
          "level", variables.getOrDefault("level", ""),
          "disasterType", variables.getOrDefault("disasterType", ""),
          "timestamp", System.currentTimeMillis()
      );

      // 通过广播服务推送到大屏
      broadcastService.broadcastScreen(screenMessage);

      return SendResult.success(messageId);
    } catch (Exception e) {
      log.error("大屏推送失败: {}", e.getMessage(), e);
      return SendResult.failure("大屏推送失败: " + e.getMessage());
    }
  }
}
