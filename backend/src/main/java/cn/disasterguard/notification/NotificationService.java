package cn.disasterguard.notification;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.notification.channel.NotificationSender;
import cn.disasterguard.notification.dto.*;
import cn.disasterguard.notification.entity.NotificationChannelConfig;
import cn.disasterguard.notification.entity.NotificationRecord;
import cn.disasterguard.notification.entity.NotificationReceipt;
import cn.disasterguard.notification.repository.NotificationChannelConfigRepository;
import cn.disasterguard.notification.repository.NotificationRecordRepository;
import cn.disasterguard.notification.repository.NotificationReceiptRepository;
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
   * 查询发送记录。
   *
   * <p>{@code status} 支持原始状态字符串（SENT / FAILED / READ 等精确匹配），
   * 当为空时退化为 {@code readFilter} 的 read/unread 分路。
   */
  public PageResponse<NotificationRecordDto> records(
      String bizType, String disasterType, String level,
      String status, Boolean readFilter,
      int page, int size) {

    String statusFilter = (status == null || status.isBlank()) ? null : status.trim();
    Boolean effectiveRead = statusFilter == null ? readFilter : null;

    Page<NotificationRecord> result = recordRepository.findByFilters(
        bizType, disasterType, level, effectiveRead, statusFilter,
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
      } catch (Exception ignored) {
        log.debug("无法解析 channels JSON '{}'（容忍脏数据）", record.getChannels());
      }
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
    final List<String> channelList;
    final Map<String, String> templateMapping;

    if (config.getChannels() != null) {
      List<String> parsed = new ArrayList<>();
      try {
        parsed.addAll(objectMapper.readValue(config.getChannels(),
            new TypeReference<List<String>>() {}));
      } catch (Exception ignored) {
        log.debug("无法解析 config.channels JSON '{}'（容忍脏数据）", config.getChannels());
      }
      channelList = List.copyOf(parsed);
    } else {
      channelList = List.of();
    }

    if (config.getTemplateMapping() != null) {
      Map<String, String> parsed = new HashMap<>();
      try {
        parsed.putAll(objectMapper.readValue(config.getTemplateMapping(),
            new TypeReference<Map<String, String>>() {}));
      } catch (Exception ignored) {
        log.debug("无法解析 config.templateMapping JSON '{}'（容忍脏数据）", config.getTemplateMapping());
      }
      templateMapping = Map.copyOf(parsed);
    } else {
      templateMapping = Map.of();
    }

    final List<ChannelConfigDto.ChannelSetting> settings = channelList.stream()
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
    // 硬编码字面量序列化不会失败；捕获仅防止未来维护时引入不兼容对象
    try {
      config.setChannels(objectMapper.writeValueAsString(List.of("SMS", "APP", "SCREEN")));
      config.setTemplateMapping(objectMapper.writeValueAsString(Map.of()));
    } catch (Exception e) {
      log.error("createDefaultConfig 序列化失败（不应发生）：{}", e.getMessage());
      config.setChannels("[\"SMS\",\"APP\",\"SCREEN\"]");
      config.setTemplateMapping("{}");
    }
    return config;
  }
}
