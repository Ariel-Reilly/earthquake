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
import org.springframework.data.domain.Sort;
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
  private final TestRecipientGuard recipientGuard;

  public NotificationService(
      NotificationRouter router,
      NotificationRecordRepository recordRepository,
      NotificationReceiptRepository receiptRepository,
      NotificationChannelConfigRepository configRepository,
      List<NotificationSender> senders,
      ObjectMapper objectMapper,
      TestRecipientGuard recipientGuard) {
    this.router = router;
    this.recordRepository = recordRepository;
    this.receiptRepository = receiptRepository;
    this.configRepository = configRepository;
    this.senders = senders;
    this.objectMapper = objectMapper;
    this.recipientGuard = recipientGuard;
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
        bizType, disasterType, level, effectiveRead, null, statusFilter,
        PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt")));

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
   * 测试发送（渠道页 / 通知模板页共用）。
   *
   * <p>同样受测试成员白名单约束：只覆盖 Router 正式派发是不够的，这个入口会直接调用
   * Sender，历史上是绕过派发链路的旁路。「不填 target 的连通性探测」不调用任何 Sender
   * —— {@code ScreenSender} 会向所有在线 WebSocket 客户端广播，空 target 不等于零副作用。
   *
   * <p>探测能且仅能确认「该渠道是否注册了发送器」。它不连数据库、不调外部网关，
   * 因此不得对外宣称「渠道配置已校验 / 网关连通」——那是不成立的陈述。
   */
  public SendResultDto testSend(TestSendRequest request) {
    if (TestRecipientGuard.isProbeWithoutTarget(request.target())) {
      // 与真实发送路径使用完全一致的匹配规则，避免探测说「可用」、实际发送却报 NO_SENDER。
      boolean senderRegistered = senders.stream()
          .anyMatch(s -> s.getChannel().equals(request.channel()));
      String status = senderRegistered ? "PROBE_ONLY" : "NO_SENDER";
      String note = senderRegistered
          ? "[渠道探测·未投递] 已确认渠道 " + request.channel()
              + " 的发送器已注册；未调用任何发送器，未校验渠道配置与外部网关连通性"
          : "[渠道探测·未投递] 渠道 " + request.channel()
              + " 未注册发送器；未调用任何发送器";
      String message = senderRegistered
          ? "渠道发送器已确认注册；未提供收件人，未调用任何发送器，未投递任何消息"
              + "（未校验渠道配置，未校验外部网关连通性）"
          : "渠道未注册发送器；未提供收件人，未投递任何消息";
      return new SendResultDto(null, status,
          List.of(new SendResultDto.ChannelResult(request.channel(), "", status, note)),
          message);
    }
    if (!recipientGuard.isAllowed(request.target())) {
      log.warn("测试发送被白名单拦截: channel={}, target={}, allowlistSize={}",
          request.channel(), TestRecipientGuard.mask(request.target()), recipientGuard.size());
      return new SendResultDto(
          null,
          TestRecipientGuard.BLOCKED_STATUS,
          List.of(new SendResultDto.ChannelResult(
              request.channel(), request.target(),
              TestRecipientGuard.BLOCKED_STATUS, TestRecipientGuard.BLOCKED_REASON)),
          TestRecipientGuard.BLOCKED_REASON);
    }

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

    // 演示 Sender 返回的是「已受理」，不是「已送达」；不能对外报 SUCCESS。
    final String status;
    final String note;
    if (result.mock()) {
      status = "MOCK_SENT";
      note = "[演示发送·未真实投递] "
          + (result.mockReason() == null ? "渠道未接入外部网关" : result.mockReason());
    } else {
      status = result.success() ? "SUCCESS" : "FAILED";
      note = result.success() ? null : result.errorMsg();
    }

    List<SendResultDto.ChannelResult> results = List.of(
        new SendResultDto.ChannelResult(request.channel(), request.target(), status, note)
    );

    String message = switch (status) {
      case "MOCK_SENT" -> "测试发送已受理，但当前为演示发送，未真实投递";
      case "SUCCESS" -> "发送成功";
      default -> "发送失败: " + result.errorMsg();
    };
    if (result.mock()) {
      log.warn("[MOCK] 测试发送未真实投递: channel={}, status={}", request.channel(), status);
    }

    return new SendResultDto(null, status, results, message);
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
    // sent 只数真实送达；演示回执单独统计，不再把 mock 算进「已发送」。
    stats.put("sent", receipts.stream().filter(r -> "SENT".equals(r.status())).count());
    stats.put("mockSent", receipts.stream().filter(r -> "MOCK_SENT".equals(r.status())).count());
    stats.put("blocked", receipts.stream().filter(r -> "BLOCKED".equals(r.status())).count());
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
