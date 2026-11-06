package cn.disasterguard.notification.channel;

import cn.disasterguard.realtime.RealtimeBroadcastService;
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

      // 这里确实调用了 broadcastScreen：不能再说「仅记录日志」。它是站内广播，
      // 可见范围是所有在线客户端，不限于白名单里的那个 target。
      return SendResult.mockSuccess(messageId,
          "已向站内在线大屏 WebSocket 广播演示消息（广播类渠道，收件人不限于指定 target）；未接入外部投递网关");
    } catch (Exception e) {
      log.error("大屏推送失败: {}", e.getMessage(), e);
      return SendResult.failure("大屏推送失败: " + e.getMessage());
    }
  }
}
