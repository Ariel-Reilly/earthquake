package cn.disasterguard.notification.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook 发送器（HTTP POST 到第三方系统）
 */
@Component
public class WebhookSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

  private final HttpClient httpClient;

  @Value("${notification.webhook.enabled:true}")
  private boolean enabled;

  @Value("${notification.webhook.timeout-ms:5000}")
  private int timeoutMs;

  public WebhookSender() {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(5000))
        .build();
  }

  @Override
  public String getChannel() {
    return "WEBHOOK";
  }

  @Override
  public SendResult send(String target, String title, String content, Map<String, Object> variables) {
    if (!enabled) {
      log.warn("Webhook 发送已禁用");
      return SendResult.failure("Webhook 发送已禁用");
    }

    try {
      String messageId = UUID.randomUUID().toString();
      log.info("[WEBHOOK Mock] 发送 Webhook 到 {}: {}", target, title);

      // 构建 Webhook payload
      String payload = buildPayload(messageId, title, content, variables);

      // TODO: 在生产环境中启用真实 HTTP 请求
      // HttpRequest request = HttpRequest.newBuilder()
      //     .uri(URI.create(target))
      //     .header("Content-Type", "application/json")
      //     .header("X-Notification-Id", messageId)
      //     .timeout(Duration.ofMillis(timeoutMs))
      //     .POST(HttpRequest.BodyPublishers.ofString(payload))
      //     .build();
      //
      // HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      // if (response.statusCode() >= 200 && response.statusCode() < 300) {
      //   return SendResult.success(messageId);
      // } else {
      //   return SendResult.failure("HTTP " + response.statusCode() + ": " + response.body());
      // }

      // Mock: 模拟成功
      Thread.sleep(20);
      return SendResult.success(messageId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return SendResult.failure("Webhook 请求被中断");
    } catch (Exception e) {
      log.error("Webhook 发送失败: {}", e.getMessage(), e);
      return SendResult.failure("Webhook 发送失败: " + e.getMessage());
    }
  }

  private String buildPayload(String messageId, String title, String content, Map<String, Object> variables) {
    // 构建标准 Webhook payload
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"id\":\"").append(messageId).append("\",");
    sb.append("\"title\":\"").append(escapeJson(title)).append("\",");
    sb.append("\"content\":\"").append(escapeJson(content)).append("\",");
    sb.append("\"timestamp\":").append(System.currentTimeMillis()).append(",");
    sb.append("\"variables\":{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : variables.entrySet()) {
      if (!first) sb.append(",");
      sb.append("\"").append(entry.getKey()).append("\":\"").append(escapeJson(String.valueOf(entry.getValue()))).append("\"");
      first = false;
    }
    sb.append("}}");
    return sb.toString();
  }

  private String escapeJson(String value) {
    if (value == null) return "";
    return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
  }
}
