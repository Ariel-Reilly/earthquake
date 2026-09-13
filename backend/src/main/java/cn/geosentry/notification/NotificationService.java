package cn.geosentry.notification;

import cn.geosentry.common.api.ApiResponse;
import cn.geosentry.common.api.PageResponse;
import cn.geosentry.notification.channel.NotificationSender;
import cn.geosentry.notification.dto.*;
import cn.geosentry.notification.entity.NotificationChannelConfig;
import cn.geosentry.notification.entity.NotificationRecord;
import cn.geosentry.notification.entity.NotificationReceipt;
import cn.geosentry.notification.repository.NotificationChannelConfigRepository;
import cn.geosentry.notification.repository.NotificationRecordRepository;
import cn.geosentry.notification.repository.NotificationReceiptRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  private final NotificationRouter router;
  private final NotificationRecordRepository recordRepository;
  private final NotificationReceiptRepository receiptRepository;
  private final NotificationChannelConfigRepository configRepository;
  private final List<NotificationSender> senders;
  private final ObjectMapper objectMapper;

  public NotificationService(
      NotificationRouter router,
      NotificationRecordRepository recordRepository,
      NotificationReceiptRepository receiptRepository,
      NotificationChannelConfigRepository configRepository,
      List<NotificationSender> senders,
      ObjectMapper objectMapper) {
    this.router = router;
    this.recordRepository = recordRepository;
    this.receiptRepository = receiptRepository;
    this.configRepository = configRepository;
    this.senders = senders;
    this.objectMapper = objectMapper;
  }

  /**
   * 发送通知
   */
  public SendResultDto send(NotificationRequestDto request, String operator) {
    return router.route(request, operator);
  }

  /**
   * 查询发送记录
   */
  public PageResponse<NotificationRecordDto> records(
      String bizType, String disasterType, String level, String status,
      int page, int size) {

    Page<NotificationRecord> result = recordRepository.findByFilters(
        bizType, disasterType, level, status,
        PageRequest.of(page - 1, size));

    List<NotificationRecordDto> records = result.getContent().stream()
        .map(this::toDto)
        .toList();

    return new PageResponse<>(
        records,
        result.getTotalElements(),
        page,
        size,
        result.getTotalPages()
    );
  }

  /**
   * 查询回执详情
   */
  public List<NotificationReceiptDto> receipts(Long recordId) {
    return receiptRepository.findByRecordId(recordId).stream()
        .map(this::toReceiptDto)
        .toList();
  }

  /**
   * 重试失败通知
   */
  public SendResultDto retry(Long recordId) {
    return router.retry(recordId);
  }

  /**
   * 测试发送
   */
  public SendResultDto testSend(TestSendRequest request) {
    NotificationSender sender = senders.stream()
        .filter(s -> s.getChannel().equals(request.channel()))
        .findFirst()
        .orElse(null);

    if (sender == null) {
      return new SendResultDto(null, "NO_SENDER", List.of(),
          "未找到渠道: " + request.channel());
    }

    NotificationSender.SendResult result = sender.send(
        request.target(),
        request.title(),
        request.content(),
        request.variables() != null ? request.variables() : Map.of()
    );

    List<SendResultDto.ChannelResult> results = List.of(
        new SendResultDto.ChannelResult(
            request.channel(),
            request.target(),
            result.success() ? "SUCCESS" : "FAILED",
            result.errorMsg()
        )
    );

    return new SendResultDto(null, result.success() ? "SUCCESS" : "FAILED",
        results, result.success() ? "测试发送成功" : "测试发送失败: " + result.errorMsg());
  }

  /**
   * 获取渠道配置
   */
  public List<ChannelConfigDto> getChannelConfigs(String disasterType, String level) {
    List<NotificationChannelConfig> configs;

    if (disasterType != null && level != null) {
      configs = List.of(configRepository
          .findByDisasterTypeAndLevel(disasterType, level)
          .orElse(createDefaultConfig(disasterType, level)));
    } else if (disasterType != null) {
      configs = configRepository.findByDisasterType(disasterType);
    } else {
      configs = configRepository.findByEnabled(true);
    }

    return configs.stream().map(this::toConfigDto).toList();
  }

  /**
   * 更新渠道配置
   */
  @Transactional
  public ChannelConfigDto updateChannelConfig(ChannelConfigDto config) {
    NotificationChannelConfig entity = configRepository
        .findById(config.id())
        .orElseGet(() -> {
          NotificationChannelConfig c = new NotificationChannelConfig();
          c.setDisasterType(config.disasterType());
          c.setLevel(config.level());
          return c;
        });

    try {
      entity.setChannels(objectMapper.writeValueAsString(
          config.channels().stream().map(ChannelConfigDto.ChannelSetting::channel).toList()));
      entity.setTemplateMapping(objectMapper.writeValueAsString(config.templateMapping()));
      entity.setEnabled(config.enabled());
      entity.setPriorityChannels(objectMapper.writeValueAsString(config.channels()));
    } catch (Exception e) {
      log.error("序列化配置失败: {}", e.getMessage());
    }

    entity.setUpdatedAt(OffsetDateTime.now());
    entity = configRepository.save(entity);

    return toConfigDto(entity);
  }

  /**
   * 转换记录为 DTO
   */
  private NotificationRecordDto toDto(NotificationRecord record) {
    List<NotificationReceiptDto> receipts = receiptRepository
        .findByRecordId(record.getId())
        .stream()
        .map(this::toReceiptDto)
        .toList();

    Map<String, Object> stats = new HashMap<>();
    stats.put("total", receipts.size());
    stats.put("sent", receipts.stream().filter(r -> "SENT".equals(r.status())).count());
    stats.put("failed", receipts.stream().filter(r -> "FAILED".equals(r.status())).count());
    stats.put("pending", receipts.stream().filter(r -> "PENDING".equals(r.status())).count());

    List<String> channels = List.of();
    if (record.getChannels() != null) {
      try {
        channels = objectMapper.readValue(record.getChannels(),
            new TypeReference<List<String>>() {});
      } catch (Exception ignored) {}
    }

    return new NotificationRecordDto(
        record.getId(),
        record.getBizType(),
        record.getBizId(),
        record.getDisasterType(),
        record.getLevel(),
        record.getTitle(),
        record.getContent(),
        channels,
        record.getStatus(),
        record.getCreatedBy(),
        record.getCreatedAt(),
        record.getUpdatedAt(),
        receipts,
        stats
    );
  }

  private NotificationReceiptDto toReceiptDto(NotificationReceipt receipt) {
    return new NotificationReceiptDto(
        receipt.getId(),
        receipt.getRecordId(),
        receipt.getChannel(),
        receipt.getTarget(),
        receipt.getStatus(),
        receipt.getErrorMsg(),
        receipt.getRetryCount(),
        receipt.getSentAt(),
        receipt.getDeliveredAt(),
        receipt.getCreatedAt()
    );
  }

  private ChannelConfigDto toConfigDto(NotificationChannelConfig config) {
    List<String> channelList = List.of();
    Map<String, String> templateMapping = Map.of();
    List<ChannelConfigDto.ChannelSetting> settings = List.of();

    if (config.getChannels() != null) {
      try {
        channelList = objectMapper.readValue(config.getChannels(),
            new TypeReference<List<String>>() {});
      } catch (Exception ignored) {}
    }

    if (config.getTemplateMapping() != null) {
      try {
        templateMapping = objectMapper.readValue(config.getTemplateMapping(),
            new TypeReference<Map<String, String>>() {});
      } catch (Exception ignored) {}
    }

    settings = channelList.stream()
        .map(ch -> new ChannelConfigDto.ChannelSetting(
            ch, true, getChannelPriority(ch), templateMapping.get(ch)))
        .toList();

    return new ChannelConfigDto(
        config.getId(),
        config.getDisasterType(),
        config.getLevel(),
        settings,
        templateMapping,
        config.getEnabled(),
        config.getUpdatedAt()
    );
  }

  private int getChannelPriority(String channel) {
    return switch (channel) {
      case "SMS" -> 1;
      case "APP" -> 2;
      case "SCREEN" -> 3;
      case "SOUND_LIGHT" -> 4;
      case "EMAIL" -> 5;
      case "WEBHOOK" -> 6;
      default -> 99;
    };
  }

  private NotificationChannelConfig createDefaultConfig(String disasterType, String level) {
    NotificationChannelConfig config = new NotificationChannelConfig();
    config.setDisasterType(disasterType);
    config.setLevel(level);
    config.setEnabled(true);
    try {
      config.setChannels(objectMapper.writeValueAsString(List.of("SMS", "APP", "SCREEN")));
      config.setTemplateMapping(objectMapper.writeValueAsString(Map.of()));
    } catch (Exception ignored) {}
    return config;
  }
}
