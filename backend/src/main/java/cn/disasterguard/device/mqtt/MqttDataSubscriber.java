package cn.disasterguard.device.mqtt;

import cn.disasterguard.device.service.DeviceDataService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * EMQX MQTT 数据桥接：订阅 {@code disasterguard/{deviceCode}/data}，解析 JSON 后写入 {@code device_metric}。
 *
 * <p>解析和入库在独立的消费线程池（有界队列）中执行，避免 MQTT broker 回调线程被 DB IO 阻塞。
 * 队列满时丢弃最旧消息并告警，保证 EMQX 接收循环不被反向压垮。
 *
 * <p>EMQX 不可达时仅记录 WARN 并指数退避重连，不阻塞应用启动；HTTP 上报仍可作备选通道。
 * 应用关闭时优雅关闭线程池 + 断开 MQTT 连接，避免资源泄漏。
 */
@Component
public class MqttDataSubscriber implements ApplicationRunner, MqttCallback {

  private static final Logger log = LoggerFactory.getLogger(MqttDataSubscriber.class);
  private static final String TOPIC_PREFIX = "disasterguard/";
  private static final String TOPIC_SUFFIX = "/data";
  /** 队列容量：高并发时丢弃旧消息告警，避免 broker 线程被阻塞。 */
  private static final int QUEUE_CAPACITY = 1000;
  /** 消费线程数：IO 密集型，2-4 个线程足够。 */
  private static final int CONSUMER_THREADS = 2;

  private final DeviceDataService deviceDataService;
  private final ObjectMapper objectMapper;

  private final boolean enabled;
  private final String brokerUrl;
  private final String clientId;
  private final String topic;
  private final int qos;
  private final String username;
  private final String password;

  private volatile MqttClient client;
  private final AtomicBoolean running = new AtomicBoolean(false);

  /** 异步消费线程池：有界队列，队列满时丢弃最旧消息并告警。 */
  private final ThreadPoolExecutor ingestExecutor;

  public MqttDataSubscriber(DeviceDataService deviceDataService,
                            ObjectMapper objectMapper,
                            @Value("${emqx.enabled:true}") boolean enabled,
                            @Value("${emqx.mqtt-url:tcp://localhost:1883}") String brokerUrl,
                            @Value("${emqx.client-id:disasterguard-backend}") String clientIdBase,
                            @Value("${emqx.topic:disasterguard/+/data}") String topic,
                            @Value("${emqx.qos:1}") int qos,
                            @Value("${emqx.username:}") String username,
                            @Value("${emqx.password:}") String password) {
    this.deviceDataService = deviceDataService;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.brokerUrl = brokerUrl;
    // clientId 加入随机后缀，支持多实例部署时互不踢掉
    String suffix = "-" + UUID.randomUUID().toString().substring(0, 8);
    this.clientId = clientIdBase + suffix;
    this.topic = topic;
    this.qos = qos;
    this.username = username;
    this.password = password;

    ThreadFactory factory = r -> {
      Thread t = new Thread(r, "mqtt-ingest-worker");
      t.setDaemon(true);
      return t;
    };
    this.ingestExecutor = new ThreadPoolExecutor(
        CONSUMER_THREADS, CONSUMER_THREADS,
        60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(QUEUE_CAPACITY),
        factory,
        // 队列满时：丢弃最旧消息并打告警日志（CallerRunsPolicy 会阻塞 broker 线程，不能用）
        (r, ex) -> log.warn("MQTT 消费队列已满（{}），丢弃最旧消息。emqx 上报速率可能超过入库能力。", QUEUE_CAPACITY)
    );
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled || brokerUrl == null || brokerUrl.isBlank()) {
      log.info("EMQX 桥接已禁用（emqx.enabled=false），跳过 MQTT 订阅");
      return;
    }
    running.set(true);
    Thread thread = new Thread(this::connectLoop, "emqx-subscriber");
    thread.setDaemon(true);
    thread.start();
  }

  private void connectLoop() {
    int delay = 5;
    int attempts = 0;
    final int MAX_ATTEMPTS_BEFORE_WARN = 5;
    final int MAX_DELAY_SECONDS = 600;  // 上限 10 分钟，避免无限等待
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        connectAndSubscribe();
        attempts = 0;
        delay = 5;
        // 阻塞等待，直到断线或应用关闭
        synchronized (this) {
          try {
            wait();
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      } catch (MqttException e) {
        attempts++;
        if (attempts >= MAX_ATTEMPTS_BEFORE_WARN) {
          log.error("EMQX 连续 {} 次连接失败，将持续重试（delay={}s）。检查 broker 状态或设置 emqx.enabled=false 禁用。",
              attempts, delay, e);
        } else {
          log.warn("EMQX 连接失败({}), {}/{} 次, {}s 后重试: {}", brokerUrl, attempts, MAX_ATTEMPTS_BEFORE_WARN, delay, e.getMessage());
        }
        try {
          Thread.sleep(delay * 1000L);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
        // 指数退避 + 上限，永不断开（除非应用关闭）
        delay = Math.min(delay * 2, MAX_DELAY_SECONDS);
      }
    }
  }

  private void connectAndSubscribe() throws MqttException {
    // 确保旧连接已关闭
    closeClient();

    client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
    client.setCallback(this);
    MqttConnectOptions options = new MqttConnectOptions();
    options.setCleanSession(false); // 保留会话，断线重连后继续接收离线消息
    options.setAutomaticReconnect(true);
    options.setConnectionTimeout(10);
    options.setKeepAliveInterval(30);
    if (username != null && !username.isBlank()) {
      options.setUserName(username);
      options.setPassword(password == null ? "".toCharArray() : password.toCharArray());
    }
    client.connect(options);
    client.subscribe(topic, qos);
    log.info("EMQX 已订阅主题: {} @ {}", topic, brokerUrl);
  }

  private void closeClient() {
    MqttClient oldClient = client;
    client = null;
    if (oldClient != null && oldClient.isConnected()) {
      try {
        oldClient.disconnect(1000); // 最多等待 1s 完成离线消息发送
        oldClient.close();
      } catch (Exception e) {
        log.debug("关闭旧 MQTT 连接异常（忽略）: {}", e.getMessage());
      }
    }
  }

  @Override
  public void connectionLost(Throwable cause) {
    log.warn("EMQX 连接断开: {}，将尝试重连", cause == null ? "未知原因" : cause.getMessage());
    // 通知 connectLoop 重连（setAutomaticReconnect 会自动重连，这里只是唤醒等待线程）
    synchronized (this) {
      notifyAll();
    }
  }

  @Override
  public void messageArrived(String topic, MqttMessage message) {
    String deviceCode = extractDeviceCode(topic);
    if (deviceCode == null) {
      log.warn("忽略无法解析设备号的 MQTT 主题: {}", topic);
      return;
    }
    // 将解析 + 入库提交到独立消费线程池，避免阻塞 EMQX broker 回调线程
    final String payloadBody = new String(message.getPayload(), StandardCharsets.UTF_8);
    ingestExecutor.execute(() -> ingestSafely(deviceCode, payloadBody));
  }

  /** 安全入库：独立消费线程内自行 try-catch，防止异常杀死 worker 线程。 */
  private void ingestSafely(String deviceCode, String body) {
    try {
      Map<String, Object> payload = objectMapper.readValue(body, new TypeReference<>() {});
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
    // 无需处理（本服务只订阅不发布）
  }

  private String extractDeviceCode(String topic) {
    if (topic == null || !topic.startsWith(TOPIC_PREFIX) || !topic.endsWith(TOPIC_SUFFIX)) {
      return null;
    }
    String middle = topic.substring(TOPIC_PREFIX.length(), topic.length() - TOPIC_SUFFIX.length());
    return middle.isBlank() ? null : middle;
  }

  /** 应用关闭时优雅断开 MQTT 连接 + 关闭消费线程池，避免资源泄漏。 */
  @PreDestroy
  public void shutdown() {
    running.set(false);
    synchronized (this) {
      notifyAll();
    }
    closeClient();
    // 优雅关闭消费线程池：等待队列中已接收的任务完成（最多 5s）
    ingestExecutor.shutdown();
    try {
      if (!ingestExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        ingestExecutor.shutdownNow();
      }
    } catch (InterruptedException ignored) {
      ingestExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    log.info("EMQX 订阅器已关闭");
  }
}
