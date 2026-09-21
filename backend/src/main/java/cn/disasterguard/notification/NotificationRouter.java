package cn.disasterguard.notification;

import cn.disasterguard.notification.channel.*;
import cn.disasterguard.notification.dto.*;
import cn.disasterguard.notification.entity.*;
import cn.disasterguard.notification.repository.*;
import cn.disasterguard.auth.repository.UserPreferenceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 通知路由服务：核心路由逻辑
 *
 * 路由策略：
 * 1. 根据 bizType + disasterType + level 查询渠道配置
 * 2. 合并用户偏好（用户可关闭某些渠道）
 * 3. 按优先级排序渠道
 * 4. 为每个渠道选择对应模板，渲染变量
 * 5. 异步调用各 Sender 发送
 * 6. 记录发送记录与回执
 *
 * <p>异步发送委托给 {@link NotificationDispatcher}，避免在同类内自调用 @Async（自调用
 * 会绕过 Spring 代理，导致 @Async / @Transactional 不生效）。
 */
@Service
public class NotificationRouter {

  private static final Logger log = LoggerFactory.getLogger(NotificationRouter.class);

  private final NotificationChannelConfigRepository configRepository;
  private final NotificationRecordRepository recordRepository;
  private final NotificationReceiptRepository receiptRepository;
  private final UserPreferenceRepository userPreferenceRepository;
  private final Map<String, NotificationSender> senders;
  private final ObjectMapper objectMapper;
  private final NotificationDispatcher dispatcher;

  // 限流：每个渠道每分钟最大发送数
  private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

  // 默认渠道优先级
  private static final List<String> DEFAULT_CHANNEL_PRIORITY = List.of(
      "SMS", "APP", "SCREEN", "SOUND_LIGHT", "EMAIL", "WEBHOOK"
  );

  public NotificationRouter(
      NotificationChannelConfigRepository configRepository,
      NotificationRecordRepository recordRepository,
      NotificationReceiptRepository receiptRepository,
      UserPreferenceRepository userPreferenceRepository,
      List<NotificationSender> senderList,
      ObjectMapper objectMapper,
      NotificationDispatcher dispatcher) {
    this.configRepository = configRepository;
    this.recordRepository = recordRepository;
    this.receiptRepository = receiptRepository;
    this.userPreferenceRepository = userPreferenceRepository;
    this.objectMapper = objectMapper;
    this.dispatcher = dispatcher;
    this.senders = senderList.stream()
        .collect(Collectors.toMap(NotificationSender::getChannel, s -> s));

    rateLimiters.put("SMS", new RateLimiter(100));      // 每分钟 100 条
    rateLimiters.put("APP", new RateLimiter(200));      // 每分钟 200 条
    rateLimiters.put("EMAIL", new RateLimiter(50));     // 每分钟 50 条
  }

  /**
   * 路由发送通知
   */
  public SendResultDto route(NotificationRequestDto request, String operator) {
    // 1. 创建发送记录
    NotificationRecord record = createRecord(request, operator);
    record = recordRepository.save(record);

    // 2. 确定要发送的渠道
    List<String> channels = resolveChannels(request);

    if (channels.isEmpty()) {
      log.warn("没有可用的发送渠道，recordId={}", record.getId());
      record.setStatus("NO_CHANNEL");
      recordRepository.save(record);
      return new SendResultDto(record.getId(), "NO_CHANNEL", List.of(), "无可用渠道");
    }

    // 3. 异步发送（必须经过 dispatcher 的代理，才能真正触发 @Async + 新事务）
    dispatcher.asyncSend(record, request, channels);

    return new SendResultDto(record.getId(), "SENDING", List.of(), "发送中");
  }

  /**
   * 发送单个渠道
   */
  SendResultDto.ChannelResult sendToChannel(
      Long recordId, String channel, String target,
      String title, String content, Map<String, Object> variables) {

    if (isRateLimited(channel)) {
      log.warn("渠道 {} 触发限流，跳过", channel);
      createReceipt(recordId, channel, target, "RATE_LIMITED", "触发限流");
      return new SendResultDto.ChannelResult(channel, target, "RATE_LIMITED", "触发限流");
    }

    NotificationSender sender = senders.get(channel);
    if (sender == null) {
      log.warn("未找到渠道发送器: {}", channel);
      return new SendResultDto.ChannelResult(channel, target, "NO_SENDER", "未找到发送器");
    }

    long start = System.currentTimeMillis();
    NotificationSender.SendResult result = sender.send(target, title, content, variables);
    long duration = System.currentTimeMillis() - start;

    log.info("发送结果: channel={}, target={}, status={}, duration={}ms",
        channel, target, result.success() ? "SUCCESS" : "FAILED", duration);

    // 记录回执
    String status = result.success() ? "SENT" : "FAILED";
    createReceipt(recordId, channel, target, status, result.errorMsg());

    return new SendResultDto.ChannelResult(
        channel, target,
        result.success() ? "SUCCESS" : "FAILED",
        result.errorMsg()
    );
  }

  /**
   * 解析可用渠道
   */
  private List<String> resolveChannels(NotificationRequestDto request) {
    // 优先使用请求中指定的渠道
    if (request.channels() != null && !request.channels().isEmpty()) {
      return request.channels();
    }

    List<String> configuredChannels = queryConfiguredChannels(
        request.disasterType(), request.level());

    // 如果没有配置，使用默认渠道
    if (configuredChannels.isEmpty()) {
      configuredChannels = DEFAULT_CHANNEL_PRIORITY;
    }

    return configuredChannels;
  }

  /**
   * 查询渠道配置
   */
  private List<String> queryConfiguredChannels(String disasterType, String level) {
    Optional<NotificationChannelConfig> config = configRepository
        .findByDisasterTypeAndLevelAndEnabled(disasterType, level, true);

    if (config.isPresent() && config.get().getChannels() != null) {
      try {
        return objectMapper.readValue(config.get().getChannels(),
            new TypeReference<List<String>>() {});
      } catch (Exception e) {
        log.error("解析渠道配置失败: {}", e.getMessage());
      }
    }

    return List.of();
  }

  /**
   * 创建发送记录
   */
  private NotificationRecord createRecord(NotificationRequestDto request, String operator) {
    NotificationRecord record = new NotificationRecord();
    record.setBizType(request.bizType());
    record.setBizId(request.bizId());
    record.setDisasterType(request.disasterType());
    record.setLevel(request.level());
    record.setTitle(request.title());
    record.setContent(request.content());
    record.setCreatedBy(operator);
    record.setStatus("PENDING");

    try {
      List<String> channels = request.channels() != null ? request.channels() : List.of();
      record.setChannels(objectMapper.writeValueAsString(channels));
    } catch (Exception e) {
      log.error("序列化渠道失败: {}", e.getMessage());
    }

    return record;
  }

  /**
   * 创建回执记录
   */
  void createReceipt(Long recordId, String channel, String target, String status, String errorMsg) {
    NotificationReceipt receipt = new NotificationReceipt();
    receipt.setRecordId(recordId);
    receipt.setChannel(channel);
    receipt.setTarget(target);
    receipt.setStatus(status);
    receipt.setErrorMsg(errorMsg);

    if ("SENT".equals(status)) {
      receipt.setSentAt(OffsetDateTime.now());
    }

    receiptRepository.save(receipt);
  }

  /**
   * 限流检查
   */
  private boolean isRateLimited(String channel) {
    RateLimiter limiter = rateLimiters.get(channel);
    if (limiter == null) return false;
    return limiter.isLimited();
  }

  /**
   * 限流器
   */
  private static class RateLimiter {
    private final int maxPerMinute;
    private final Queue<Long> timestamps = new LinkedList<>();

    public RateLimiter(int maxPerMinute) {
      this.maxPerMinute = maxPerMinute;
    }

    public synchronized boolean isLimited() {
      long now = System.currentTimeMillis();
      long cutoff = now - 60_000; // 1分钟窗口

      // 清理过期记录
      while (!timestamps.isEmpty() && timestamps.peek() < cutoff) {
        timestamps.poll();
      }

      if (timestamps.size() >= maxPerMinute) {
        return true;
      }

      timestamps.offer(now);
      return false;
    }
  }

  /**
   * 重试失败的通知（同步路径，由 dispatcher 的 @Transactional + @Async 上下文保证写入）。
   */
  public SendResultDto retry(Long recordId) {
    return dispatcher.retry(recordId);
  }
}
