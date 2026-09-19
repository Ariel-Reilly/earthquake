package cn.disasterguard.notification.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 声光报警器发送器（Mock 实现，支持扩展 HTTP/MQTT 协议）
 */
@Component
public class SoundLightSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(SoundLightSender.class);

  @Value("${notification.soundlight.enabled:true}")
  private boolean enabled;

  @Value("${notification.soundlight.endpoint:http://localhost:8888/api/alarm}")
  private String endpoint;

  @Override
  public String getChannel() {
    return "SOUND_LIGHT";
  }

  @Override
  public SendResult send(String target, String title, String content, Map<String, Object> variables) {
    if (!enabled) {
      log.warn("声光报警已禁用");
      return SendResult.failure("声光报警已禁用");
    }

    try {
      // TODO: 集成真实声光报警器 API（如 HTTP POST / MQTT）
      String messageId = UUID.randomUUID().toString();

      // 解析告警级别
      String level = (String) variables.getOrDefault("level", "normal");
      String soundPattern = getSoundPattern(level);
      String lightColor = getLightColor(level);

      log.info("[SOUND_LIGHT Mock] 触发声光报警: level={}, pattern={}, color={}, area={}",
               level, soundPattern, lightColor, target);

      // 模拟发送延迟
      Thread.sleep(30);

      return SendResult.success(messageId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return SendResult.failure("声光报警被中断");
    } catch (Exception e) {
      log.error("声光报警发送失败: {}", e.getMessage(), e);
      return SendResult.failure("声光报警发送失败: " + e.getMessage());
    }
  }

  private String getSoundPattern(String level) {
    return switch (level) {
      case "red" -> "CONTINUOUS_HIGH";   // 红色：连续高音
      case "orange" -> "INTERVAL_MEDIUM"; // 橙色：间歇中音
      case "yellow" -> "INTERVAL_LOW";    // 黄色：间歇低音
      default -> "NONE";
    };
  }

  private String getLightColor(String level) {
    return switch (level) {
      case "red" -> "RED_FLASH";
      case "orange" -> "ORANGE_FLASH";
      case "yellow" -> "YELLOW_STEADY";
      default -> "OFF";
    };
  }
}
