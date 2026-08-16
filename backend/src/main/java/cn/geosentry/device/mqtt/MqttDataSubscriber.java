package cn.geosentry.device.mqtt;

import cn.geosentry.device.service.DeviceDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * EMQX MQTT 数据桥接：订阅 {@code geohazard/{deviceCode}/data}，解析 JSON 后写入 {@code device_metric}。
 *
 * <p>EMQX 不可达时仅记录 WARN 并定时重连，不阻塞应用启动；HTTP 上报仍可作备选通道。
 */
@Component
public class MqttDataSubscriber implements ApplicationRunner, MqttCallback {

  private static final Logger log = LoggerFactory.getLogger(MqttDataSubscriber.class);
  private static final String TOPIC_PREFIX = "geohazard/";
  private static final String TOPIC_SUFFIX = "/data";

  private final DeviceDataService deviceDataService;
  private final ObjectMapper objectMapper;

  private final boolean enabled;
  private final String brokerUrl;
  private final String clientId;
  private final String topic;
  private final int qos;
  private final String username;
  private final String password;

  public MqttDataSubscriber(DeviceDataService deviceDataService,
                            ObjectMapper objectMapper,
                            @Value("${emqx.enabled:true}") boolean enabled,
                            @Value("${emqx.mqtt-url:tcp://localhost:1883}") String brokerUrl,
                            @Value("${emqx.client-id:geosentry-backend}") String clientId,
                            @Value("${emqx.topic:geohazard/+/data}") String topic,
                            @Value("${emqx.qos:1}") int qos,
                            @Value("${emqx.username:}") String username,
                            @Value("${emqx.password:}") String password) {
    this.deviceDataService = deviceDataService;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.brokerUrl = brokerUrl;
    this.clientId = clientId;
    this.topic = topic;
    this.qos = qos;
    this.username = username;
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled || brokerUrl == null || brokerUrl.isBlank()) {
      log.info("EMQX 桥接已禁用（emqx.enabled=false），跳过 MQTT 订阅");
      return;
    }
    Thread thread = new Thread(this::connectLoop, "emqx-subscriber");
    thread.setDaemon(true);
    thread.start();
  }

  private void connectLoop() {
    int delay = 5;
    int attempts = 0;
    final int maxAttempts = 20;  // 最多重试 20 次；指数退避上限 60s -> 最坏约 20 分钟
    while (!Thread.currentThread().isInterrupted()) {
      try {
        connectAndSubscribe();
        attempts = 0;  // 连接成功后重置计数
        // 连接后线程随 MqttClient 阻塞在 delivery 回调上；若退出则说明断线，进入重连。
        return;
      } catch (Exception e) {
        attempts++;
        if (attempts >= maxAttempts) {
          log.error("EMQX 连续 {} 次连接失败，停止重连。MQTT 链路已断开，仅 HTTP 通道可用。"
                  + " 检查 broker 状态或设置 emqx.enabled=false 禁用。", maxAttempts);
          return;
        }
        log.warn("EMQX 连接失败({}), {}/{} 次, {}s 后重试: {}", brokerUrl, attempts, maxAttempts, delay, e.getMessage());
        try {
          Thread.sleep(delay * 1000L);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
        delay = Math.min(delay * 2, 60);
      }
    }
  }

  private void connectAndSubscribe() throws org.eclipse.paho.client.mqttv3.MqttException {
    MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
    client.setCallback(this);
    MqttConnectOptions options = new MqttConnectOptions();
    options.setCleanSession(true);
    options.setAutomaticReconnect(true);
    options.setConnectionTimeout(10);
    if (username != null && !username.isBlank()) {
      options.setUserName(username);
      options.setPassword(password == null ? "".toCharArray() : password.toCharArray());
    }
    client.connect(options);
    client.subscribe(topic, qos);
    log.info("EMQX 已订阅主题: {} @ {}", topic, brokerUrl);
  }

  @Override
  public void connectionLost(Throwable cause) {
    log.warn("EMQX 连接断开: {}", cause == null ? "未知原因" : cause.getMessage());
  }

  @Override
  public void messageArrived(String topic, MqttMessage message) {
    String deviceCode = extractDeviceCode(topic);
    if (deviceCode == null) {
      log.warn("忽略无法解析设备号的 MQTT 主题: {}", topic);
      return;
    }
    try {
      String body = new String(message.getPayload(), StandardCharsets.UTF_8);
      Map<String, Object> payload = objectMapper.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
      });
      int ingested = deviceDataService.ingest(deviceCode, payload);
      if (ingested == 0) {
        log.warn("MQTT 上报设备 {} 无有效指标", deviceCode);
      }
    } catch (Exception e) {
      log.warn("MQTT 上报解析/入库失败 device={}: {}", deviceCode, e.getMessage());
    }
  }

  @Override
  public void deliveryComplete(IMqttDeliveryToken token) {
    // 无需处理
  }

  private String extractDeviceCode(String topic) {
    if (topic == null || !topic.startsWith(TOPIC_PREFIX) || !topic.endsWith(TOPIC_SUFFIX)) {
      return null;
    }
    String middle = topic.substring(TOPIC_PREFIX.length(), topic.length() - TOPIC_SUFFIX.length());
    return middle.isBlank() ? null : middle;
  }
}
