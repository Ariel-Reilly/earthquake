package cn.disasterguard.warning.service;

import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.common.export.WarningExportRow;
import cn.disasterguard.emergency.service.EmergencyService;
import cn.disasterguard.event.EventTrigger;
import cn.disasterguard.event.service.EventStateService;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import cn.disasterguard.notification.NotificationService;
import cn.disasterguard.notification.dto.NotificationRequestDto;
import cn.disasterguard.realtime.RealtimeBroadcastService;
import cn.disasterguard.warning.dto.WarningCreateRequest;
import cn.disasterguard.warning.dto.WarningDto;
import cn.disasterguard.warning.dto.WarningUpdateRequest;
import cn.disasterguard.warning.entity.WarningEvent;
import cn.disasterguard.warning.repository.WarningEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.criteria.Predicate;

@Service
public class WarningService {

  private static final Logger log = LoggerFactory.getLogger(WarningService.class);
  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
  private final WarningEventRepository warningRepository;
  private final HazardPointRepository hazardRepository;
  private final EmergencyService emergencyService;
  private final RealtimeBroadcastService broadcastService;
  private final NotificationService notificationService;
  private final ObjectMapper objectMapper;
  private final EventStateService eventStateService;

  public WarningService(WarningEventRepository warningRepository, HazardPointRepository hazardRepository,
                        EmergencyService emergencyService, RealtimeBroadcastService broadcastService,
                        NotificationService notificationService, ObjectMapper objectMapper,
                        EventStateService eventStateService) {
    this.warningRepository = warningRepository;
    this.hazardRepository = hazardRepository;
    this.emergencyService = emergencyService;
    this.broadcastService = broadcastService;
    this.notificationService = notificationService;
    this.objectMapper = objectMapper;
    this.eventStateService = eventStateService;
  }

  public PageResponse<WarningDto> page(int page, int size, String keyword, String status) {
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 200),
        Sort.by(Sort.Direction.DESC, "id"));
    Specification<WarningEvent> spec = (root, query, cb) -> {
      Predicate predicate = cb.conjunction();
      if (keyword != null && !keyword.isBlank()) {
        String like = "%" + keyword.trim().toLowerCase() + "%";
        predicate = cb.and(predicate, cb.or(
                cb.like(cb.lower(root.get("title")), like),
                cb.like(cb.lower(root.get("warningLevel")), like),
                cb.like(cb.lower(root.get("status")), like),
                cb.like(cb.lower(root.get("hazardCode")), like)
        ));
      }
      if (status != null && !status.isBlank()) {
        predicate = cb.and(predicate, cb.equal(root.get("status"), status));
      }
      return predicate;
    };

    var result = warningRepository.findAll(spec, pageable);
    List<WarningDto> records = result.getContent().stream().map(this::toDto).toList();
    return new PageResponse<>(records, result.getTotalElements(), page, size, result.getTotalPages());
  }

  public List<WarningDto> listAll() {
    return warningRepository.findAll().stream().sorted(Comparator.comparing(WarningEvent::getId)).map(this::toDto).toList();
  }

  /** 活跃预警预览：排除已关闭/已忽略/已解除等终态。 */
  public List<WarningDto> preview() {
    return warningRepository.findOpenWarningsOrderByTriggeredAtDesc(
                    List.of("已关闭", "已忽略", "已解除"))
            .stream()
            .map(this::toDto)
            .toList();
  }

  @Transactional
  public WarningDto update(Long id, WarningUpdateRequest request) {
    WarningEvent warning = warningRepository.findById(id).orElseThrow(() -> new AppException(404, "未找到预警"));
    if (request.title() != null && !request.title().isBlank()) {
      warning.setTitle(request.title());
    }
    if (request.warningLevel() != null && !request.warningLevel().isBlank()) {
      warning.setWarningLevel(request.warningLevel());
    }
    if (request.status() != null && !request.status().isBlank()) {
      warning.setStatus(request.status());
    }
    return toDto(warningRepository.save(warning));
  }

  /** 导出用：返回预警事件导出行。 */
  public List<WarningExportRow> exportRows() {
    return warningRepository.findAll().stream()
            .sorted(Comparator.comparing(WarningEvent::getId))
            .map(w -> new WarningExportRow(w.getWarningCode(), w.getTitle(), w.getHazardCode(), w.getRuleCode(),
                    w.getWarningLevel(), w.getTriggerValue(), w.getStatus(), w.getTriggeredAt()))
            .toList();
  }

  public List<WarningDto> listByViewport(String bbox, String keyword, String status) {
    Map<String, HazardPoint> hazardIndex = hazardRepository.findAll().stream()
            .filter(item -> item.getHazardCode() != null && !item.getHazardCode().isBlank())
            .collect(HashMap::new, (map, hazard) -> map.put(hazard.getHazardCode(), hazard), Map::putAll);

    return warningRepository.findAll().stream()
            .filter(item -> matches(item, hazardIndex, bbox, keyword, status))
            .sorted(Comparator.comparing(WarningEvent::getId, Comparator.nullsLast(Long::compareTo)))
            .map(this::toDto)
            .toList();
  }

  /**
   * 隐患详情页用：按隐患编码取该隐患触发的全部预警，按触发时间倒序。
   * 不分页（隐患单点预警量小），返回 DTO 列表。
   */
  public List<WarningDto> listByHazardCode(String hazardCode) {
    if (hazardCode == null || hazardCode.isBlank()) {
      return List.of();
    }
    return warningRepository.findByHazardCodeOrderByTriggeredAtDesc(hazardCode).stream()
            .map(this::toDto)
            .toList();
  }

  public WarningDto getById(Long id) {
    return warningRepository.findById(id).map(this::toDto).orElseThrow(() -> new AppException(404, "未找到预警"));
  }

  @Transactional
  public WarningDto confirm(Long id) {
    WarningEvent warning = warningRepository.findById(id).orElseThrow(() -> new AppException(404, "未找到预警"));
    warning.setStatus("已确认");
    warning.setConfirmedAt(OffsetDateTime.now());
    WarningDto dto = toDto(warningRepository.save(warning));
    broadcastWarning(dto);
    
    // 【A3】预警确认后推进统一事件状态
    transitionUnifiedEvent(warning, "CONFIRM");
    
    return dto;
  }

  @Transactional
  public WarningDto dispatch(Long id) {
    WarningEvent warning = warningRepository.findById(id).orElseThrow(() -> new AppException(404, "未找到预警"));
    warning.setStatus("处理中");
    warning.setDispatchedAt(OffsetDateTime.now());
    WarningDto dto = toDto(warningRepository.save(warning));
    broadcastWarning(dto);
    
    // 【A3】预警派发后推进统一事件状态
    transitionUnifiedEvent(warning, "DISPATCH");
    
    // ✅ 【新增】派发时触发通知（与通知中心打通闭环）
    try {
      List<String> channels = extractChannels(warning);
      String hazardName = resolveHazardName(warning.getHazardCode());
      String notifyContent = buildDispatchNotificationContent(warning, hazardName);
      
      NotificationRequestDto notifyReq = new NotificationRequestDto(
          "DISPATCH",                          // bizType: 预警派发
          warning.getWarningCode(),            // bizId: 预警编码
          warning.getHazardCode(),             // disasterType: 灾害点编码
          warning.getWarningLevel(),           // level: 预警等级
          "【预警派发】" + warning.getTitle(),  // title: 通知标题（含预警标题）
          notifyContent,                       // content: 通知内容（含等级、位置、处置要求）
          List.of("admin"),                    // targetUsers: 发送给值班员
          hazardName,                          // targetArea: 目标区域
          channels,                            // channels: 发送渠道
          null,                                // templateId
          "HIGH",                              // priority: 高优先级
          Map.of(
              "warningCode", warning.getWarningCode(),
              "warningLevel", warning.getWarningLevel(),
              "hazardName", hazardName,
              "dispatchedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)
          )
      );
      
      notificationService.send(notifyReq, "system");
      log.info("派发通知已发送 warning={}, channels={}", warning.getWarningCode(), channels);
    } catch (Exception e) {
      // 通知失败不阻塞派发主流程，保证预警派发一定能成功
      log.warn("派发通知发送失败 warning={}, 但派发流程已成功: {}", warning.getWarningCode(), e.getMessage());
    }
    
    // 橙/红预警派发时联动生成应急事件（同预警未闭环则复用，不重复建）
    if (dto != null && isHighLevel(warning.getWarningLevel())) {
      String commander = "自然资源局值班组";
      // 不因应急生成失败而阻塞派发主流程
      try {
        emergencyService.createFromWarning(warning.getWarningCode(), warning.getHazardCode(),
                warning.getTitle(), commander);
      } catch (Exception e) {
        log.warn("派发联动生成应急事件失败: {}", e.getMessage());
      }
    }
    return dto;
  }

  private boolean isHighLevel(String level) {
    return level != null && (level.contains("橙") || level.contains("红"));
  }

  @Transactional
  public WarningDto close(Long id, String reason, String operator) {
    WarningEvent warning = warningRepository.findById(id).orElseThrow(() -> new AppException(404, "未找到预警"));
    warning.setStatus(resolveTerminalStatus(reason));
    warning.setClosedAt(OffsetDateTime.now());
    warning.setCloseReason(reason);
    warning.setClosedBy(operator);
    WarningDto dto = toDto(warningRepository.save(warning));
    broadcastWarning(dto);
    
    // 【A3】预警关闭后推进统一事件状态
    transitionUnifiedEvent(warning, "CLOSE");
    
    return dto;
  }

  @Transactional
  public WarningDto dismiss(Long id, String operator) {
    WarningEvent warning = warningRepository.findById(id).orElseThrow(() -> new AppException(404, "未找到预警"));
    warning.setStatus("已忽略");
    warning.setClosedAt(OffsetDateTime.now());
    warning.setCloseReason("误报");
    warning.setClosedBy(operator);
    WarningDto dto = toDto(warningRepository.save(warning));
    broadcastWarning(dto);
    return dto;
  }

  @Transactional
  public WarningDto resolve(Long id, String reason, String scope, String scopeArea, String operator) {
    WarningEvent warning = warningRepository.findById(id).orElseThrow(() -> new AppException(404, "未找到预警"));
    if (!"已发布".equals(warning.getStatus())) {
      throw new AppException(409, "当前状态不允许解除：" + warning.getStatus());
    }
    warning.setStatus("已解除");
    warning.setClosedAt(OffsetDateTime.now());
    warning.setCloseReason(reason == null ? "现场核查，风险已消除" : reason);
    warning.setClosedBy(operator);
    warning.setResolveScope(scope == null ? "ALL" : scope);
    warning.setResolveArea(scopeArea);
    WarningDto dto = toDto(warningRepository.save(warning));
    broadcastWarning(dto);
    
    // 【A3】预警解除后推进统一事件状态
    transitionUnifiedEvent(warning, "CLOSE");
    
    // P0-2 解除即归档：待接入 emergencyService 的历史归档链路（以 hazardCode 为关联键）。
    // 当前仅记录操作日志，不阻塞主流程。
    log.info("Warning {} resolved by {} with reason {}, scope {}, area {}",
            id, operator, reason, scope, scopeArea);
    return dto;
  }

  /**
   * 兼容既有两参版本（保持向后兼容）。
   */
  public WarningDto resolve(Long id, String operator) {
    return resolve(id, "自动解除", "ALL", null, operator);
  }

  /** P0-2：解除通知预览文案。 */
  public Map<String, Object> previewResolve(Long id) {
    WarningEvent warning = warningRepository.findById(id).orElseThrow(() -> new AppException(404, "未找到预警"));
    String ts = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    String text = "【解除预警】" + warning.getTitle() + " 已解除\n"
            + "解除时间：" + ts + "\n"
            + "解除原因：现场核查，风险已消除。\n"
            + "解除范围：全部\n"
            + "请相关人员知悉。";
    return Map.of(
            "title", "【解除预警】" + warning.getTitle() + " 已解除",
            "content", text,
            "templates", List.of(
                    "经现场核查，监测数据已恢复正常，特解除本预警。",
                    "应急响应已结束，本次预警风险已消除，现予以解除。",
                    "现场复核确认威胁消除，预警予以解除，请继续关注后续监测。"),
            "channels", List.of("SMS", "APP", "SCREEN"));
  }

  /**
   * P0-2：解除范围下钻。
   *
   * <p>TODO: 当前为硬编码三镇兜底。待注入 RegionRepository 后改为
   * {@code regionRepository.findByLevel("TOWN")} 之类的真实字典查询。
   */
  public Map<String, Object> resolveScope(Long id) {
    return Map.of("scopes", List.of(
            Map.of("value", "ALL", "label", "全部"),
            Map.of("value", "龙安镇", "label", "龙安镇"),
            Map.of("value", "石桥镇", "label", "石桥镇"),
            Map.of("value", "清河镇", "label", "清河镇")));
  }

  /** 根据 reason 推断终态：包含"误报"→已忽略；包含"解除"→已解除；否则已关闭。 */
  private String resolveTerminalStatus(String reason) {
    if (reason != null) {
      if (reason.contains("误报")) return "已忽略";
      if (reason.contains("解除")) return "已解除";
    }
    return "已关闭";
  }

  @Transactional
  public WarningDto create(String hazardCode, String title, String level, String triggerValue, String ruleCode) {
    WarningEvent warning = new WarningEvent();
    warning.setWarningCode("WG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    warning.setHazardCode(hazardCode);
    warning.setTitle(title);
    warning.setWarningLevel(level);
    warning.setTriggerValue(triggerValue);
    warning.setRuleCode(ruleCode);
    warning.setStatus("待确认");
    warning.setTriggeredAt(OffsetDateTime.now());
    WarningDto dto = toDto(warningRepository.save(warning));
    broadcastWarning(dto);
    return dto;
  }

  /**
   * 灾害类型中文标签 → 英文码。
   *
   * <p>取值与 {@code sys_dict.disaster_type}（data.sql）及前端
   * {@code utils/hazardTypes.js} 保持同一套。R3 前此处仅 5 条，
   * 缺 地裂缝/洪水/火灾/台风/干旱/内涝，导致这些灾种提交后无法归一化。
   */
  private static final Map<String, String> HAZARD_TYPE_CODES = Map.ofEntries(
          Map.entry("滑坡", "landslide"),
          Map.entry("崩塌", "collapse"),
          Map.entry("泥石流", "debris_flow"),
          Map.entry("地面沉降", "subsidence"),
          Map.entry("地裂缝", "ground_fissure"),
          Map.entry("地震", "earthquake"),
          Map.entry("洪水", "flood"),
          Map.entry("火灾", "fire"),
          Map.entry("台风", "typhoon"),
          Map.entry("干旱", "drought"),
          Map.entry("内涝", "waterlogging"),
          // 历史混用写法
          Map.entry("地面塌陷", "subsidence"),
          Map.entry("城市内涝", "waterlogging")
  );

  /** 预警等级英文码 → 库内存值（warning_level 存中文，见 AiWarningScheduler#translateLevel）。 */
  private static final Map<String, String> LEVEL_LABELS = Map.of(
          "red", "红色预警",
          "orange", "橙色预警",
          "yellow", "黄色预警",
          "blue", "蓝色预警"
  );

  /**
   * 人工上报预警（POST /api/warnings）。
   *
   * <p>前端向导页提交英文等级码 + 中文灾害类型，这里统一归一化后再落库，
   * 保证与规则引擎 / AI 自动产生的预警在同一套编码体系内。
   *
   * <p>注意：hazard_code 是 NOT NULL 且外键引用 hazard_point，
   * 因此必须先校验隐患点存在，否则会退化成 500（外键冲突）。
   */
  @Transactional
  public WarningDto createManual(WarningCreateRequest request) {
    if (request == null || request.title() == null || request.title().isBlank()) {
      throw new AppException(400, "预警标题不能为空");
    }
    String hazardCode = request.hazardCode() == null ? "" : request.hazardCode().trim();
    if (hazardCode.isEmpty()) {
      throw new AppException(400, "创建预警必须关联隐患点（hazardCode 不能为空）");
    }
    if (!hazardRepository.existsByHazardCode(hazardCode)) {
      throw new AppException(400, "隐患点不存在: " + hazardCode);
    }
    WarningEvent warning = new WarningEvent();
    warning.setWarningCode("WG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    warning.setHazardCode(hazardCode);
    warning.setTitle(request.title().trim());
    warning.setWarningLevel(normalizeLevel(request.level()));
    warning.setHazardType(normalizeHazardType(request.hazardType()));
    warning.setTriggerValue(request.content() == null ? "" : request.content());
    warning.setStatus("待确认");
    warning.setSource("MANUAL");
    warning.setTriggeredAt(OffsetDateTime.now());
    warning.setManualPayload(toManualPayload(request));
    WarningDto dto = toDto(warningRepository.save(warning));
    broadcastWarning(dto);
    return dto;
  }

  /** 英文等级码 → 中文存值；已为中文存值则保留；无法识别按黄色兜底（与 AI 预警默认值一致）。 */
  private String normalizeLevel(String level) {
    if (level == null || level.isBlank()) {
      return "黄色预警";
    }
    String trimmed = level.trim();
    String mapped = LEVEL_LABELS.get(trimmed.toLowerCase());
    if (mapped != null) {
      return mapped;
    }
    if (trimmed.endsWith("预警")) {
      return trimmed;
    }
    log.warn("未知预警等级码: {}，回落为黄色预警", trimmed);
    return "黄色预警";
  }

  /** 中文灾害类型 → 英文码；不在映射表内且已是英文码则保留，其余原样入库并告警。 */
  private String normalizeHazardType(String hazardType) {
    if (hazardType == null || hazardType.isBlank()) {
      return null;
    }
    String trimmed = hazardType.trim();
    String mapped = HAZARD_TYPE_CODES.get(trimmed);
    if (mapped != null) {
      return mapped;
    }
    if (trimmed.matches("[a-z_]+")) {
      return trimmed;
    }
    log.warn("未知灾害类型标签: {}，原样入库", trimmed);
    return trimmed;
  }

  /** 向导页扩展字段序列化为 JSON，避免为低频字段撑宽主表。 */
  private String toManualPayload(WarningCreateRequest request) {
    try {
      Map<String, Object> payload = new java.util.LinkedHashMap<>();
      payload.put("region", request.region());
      payload.put("lon", request.lon());
      payload.put("lat", request.lat());
      payload.put("bufferKm", request.bufferKm());
      payload.put("channels", request.channels());
      payload.put("content", request.content());
      payload.put("validUntil", request.validUntil());
      payload.put("operator", request.operator());
      payload.put("hazardName", request.hazardName());
      return objectMapper.writeValueAsString(payload);
    } catch (Exception e) {
      log.warn("人工预警扩展字段序列化失败，已忽略: {}", e.getMessage());
      return null;
    }
  }

  @Transactional
  public void delete(Long id) {
    if (!warningRepository.existsById(id)) {
      throw new AppException(404, "未找到预警");
    }
    warningRepository.deleteById(id);
  }

  private WarningDto toDto(WarningEvent warning) {
    return new WarningDto(
            warning.getId(),
            warning.getWarningCode(),
            warning.getWarningLevel(),
            cssClass(warning.getWarningLevel()),
            warning.getTitle(),
            warning.getTriggerValue() == null ? "" : warning.getTriggerValue(),
            warning.getTriggeredAt() == null ? "" : warning.getTriggeredAt().format(TIME_FORMAT),
            resolvePoint(warning),   // P0-3 修复：原为 warning.getHazardCode()，把编码错塞进 point
            warning.getStatus(),
            warning.getHazardCode(),
            warning.getRuleCode(),
            warning.getTriggerValue(),
            warning.getTriggeredAt(),
            warning.getCloseReason(),
            warning.getClosedBy(),
            warning.getConfirmedAt(),
            warning.getDispatchedAt(),
            warning.getClosedAt()
    );
  }

  /**
   * P0-3：解析预警位置展示文本，替代原先把 hazardCode 错塞给 point 的做法。
   *
   * <p>优先从人工上报的 {@code manualPayload} 里取 {@code hazardName} 与 {@code region}
   * 拼成"名称 · 区域"；两者缺一时取其一；均缺时返回空串（前端自行决定是否展示）。
   * AI/规则引擎产生的预警没有 manualPayload，返回空串。
   */
  private String resolvePoint(WarningEvent warning) {
    String payload = warning.getManualPayload();
    if (payload == null || payload.isBlank()) {
      return "";
    }
    try {
      JsonNode node = objectMapper.readTree(payload);
      if (node == null || node.isNull()) {
        return "";
      }
      String name = node.hasNonNull("hazardName") ? node.get("hazardName").asText("") : "";
      String region = node.hasNonNull("region") ? node.get("region").asText("") : "";
      if (!name.isBlank() && !region.isBlank()) {
        return name + " · " + region;
      }
      if (!name.isBlank()) {
        return name;
      }
      if (!region.isBlank()) {
        return region;
      }
    } catch (Exception e) {
      log.debug("解析 manualPayload 位置信息失败: {}", e.getMessage());
    }
    return "";
  }

  private boolean matches(WarningEvent warning, Map<String, HazardPoint> hazardIndex, String bbox, String keyword, String status) {
    if (keyword != null && !keyword.isBlank()) {
      String like = keyword.trim().toLowerCase();
      boolean matched = contains(warning.getTitle(), like) || contains(warning.getWarningLevel(), like) || contains(warning.getStatus(), like) || contains(warning.getHazardCode(), like);
      if (!matched) return false;
    }
    if (status != null && !status.isBlank() && !status.equals(warning.getStatus())) return false;
    if (bbox != null && !bbox.isBlank()) {
      HazardPoint hazard = hazardIndex.get(warning.getHazardCode());
      var bounds = cn.disasterguard.common.geo.ViewportBounds.parse(bbox);
      if (bounds == null) return true;  // bbox 格式异常时不过滤（保守）
      if (hazard == null || !bounds.contains(hazard.getLongitude(), hazard.getLatitude())) return false;
    }
    return true;
  }

  private boolean contains(String value, String keyword) {
    return value != null && value.toLowerCase().contains(keyword);
  }

  private String cssClass(String level) {
    if (level == null) return "blue";
    if (level.contains("红")) return "red";
    if (level.contains("橙")) return "orange";
    if (level.contains("黄")) return "yellow";
    return "blue";
  }

  /** 广播预警状态变更到 WebSocket（忽略连接数为 0 时的空广播）。 */
  private void broadcastWarning(WarningDto dto) {
    try {
      broadcastService.broadcastWarning(dto);
    } catch (Exception e) {
      // 广播失败不阻塞业务主流程
      log.debug("WebSocket 广播预警失败: {}", e.getMessage());
    }
  }

  /**
   * 从预警的 manualPayload 中提取通知渠道列表。
   * 如果是人工预警且明确指定了渠道，则返回指定的渠道；
   * 否则使用缺省渠道 ["SMS","APP","SCREEN"]。
   */
  private List<String> extractChannels(WarningEvent warning) {
    if (warning.getManualPayload() == null || warning.getManualPayload().isBlank()) {
      return List.of("SMS", "APP", "SCREEN");
    }
    try {
      Map<String, Object> payload = objectMapper.readValue(
          warning.getManualPayload(),
          new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      @SuppressWarnings("unchecked")
      List<String> channels = (List<String>) payload.get("channels");
      if (channels != null && !channels.isEmpty()) {
        return channels;
      }
    } catch (Exception e) {
      log.debug("解析 manualPayload.channels 失败，使用缺省渠道: {}", e.getMessage());
    }
    return List.of("SMS", "APP", "SCREEN");
  }

  /**
   * 解析灾害点名称。
   * 优先从 hazard_point 表查询名称；无数据则返回灾害点编码。
   */
  private String resolveHazardName(String hazardCode) {
    if (hazardCode == null || hazardCode.isBlank()) {
      return "未指定";
    }
    try {
      HazardPoint hazard = hazardRepository.findByHazardCode(hazardCode);
      if (hazard != null && hazard.getName() != null) {
        return hazard.getName();
      }
    } catch (Exception e) {
      log.debug("查询灾害点名称失败 hazardCode={}: {}", hazardCode, e.getMessage());
    }
    return hazardCode;
  }

  /**
   * 构建派发通知的内容文本。
   * 包含预警标题、等级、位置等关键信息，便于用户快速定位预警。
   */
  private String buildDispatchNotificationContent(WarningEvent warning, String hazardName) {
    return String.format(
        "【预警派发通知】\n" +
        "标题：%s\n" +
        "等级：%s\n" +
        "位置：%s\n" +
        "派发时间：%s\n" +
        "请及时处理相关事宜。",
        warning.getTitle(),
        warning.getWarningLevel(),
        hazardName,
        OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    );
  }

  /**
   * 【A3 修复】推进统一事件状态机。
   * 如果预警关联了统一事件，则按触发器类型推进事件状态。
   * 如果状态转换不合法或事件不存在，只记录 WARN 日志，不中断业务流程。
   *
   * @param warning 预警实体
   * @param trigger 触发器类型：CONFIRM（确认）/ DISPATCH（派发）/ CLOSE（关闭/解除）
   */
  private void transitionUnifiedEvent(WarningEvent warning, String trigger) {
    if (warning.getUnifiedEventCode() == null || warning.getUnifiedEventCode().isBlank()) {
      return;  // 预警未关联统一事件，跳过状态转换
    }
    
    try {
      EventTrigger eventTrigger;
      switch (trigger) {
        case "CONFIRM" -> eventTrigger = EventTrigger.CONFIRM;
        case "DISPATCH" -> eventTrigger = EventTrigger.ISSUE_WARNING;  // 派发对应发布预警
        case "CLOSE" -> eventTrigger = EventTrigger.CLOSE;
        default -> {
          log.warn("未知的触发器类型: {}", trigger);
          return;
        }
      }
      
      eventStateService.transition(warning.getUnifiedEventCode(), eventTrigger, "system", trigger);
      log.info("预警操作成功，已同步推进统一事件状态 eventCode={}, trigger={}, warningCode={}",
          warning.getUnifiedEventCode(), trigger, warning.getWarningCode());
    } catch (Exception e) {
      log.warn("状态机转换失败（但预警操作已成功）eventCode={}, trigger={}, warningCode={}: {}",
          warning.getUnifiedEventCode(), trigger, warning.getWarningCode(), e.getMessage());
    }
  }
}