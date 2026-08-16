package cn.geosentry.warning.service;

import cn.geosentry.common.api.PageResponse;
import cn.geosentry.common.exception.AppException;
import cn.geosentry.common.export.WarningExportRow;
import cn.geosentry.emergency.service.EmergencyService;
import cn.geosentry.hazard.entity.HazardPoint;
import cn.geosentry.hazard.repository.HazardPointRepository;
import cn.geosentry.realtime.RealtimeBroadcastService;
import cn.geosentry.warning.dto.WarningDto;
import cn.geosentry.warning.dto.WarningUpdateRequest;
import cn.geosentry.warning.entity.WarningEvent;
import cn.geosentry.warning.repository.WarningEventRepository;
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

  public WarningService(WarningEventRepository warningRepository, HazardPointRepository hazardRepository,
                        EmergencyService emergencyService, RealtimeBroadcastService broadcastService) {
    this.warningRepository = warningRepository;
    this.hazardRepository = hazardRepository;
    this.emergencyService = emergencyService;
    this.broadcastService = broadcastService;
  }

  public PageResponse<WarningDto> page(int page, int size, String keyword, String status) {
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 200));
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
    return warningRepository.findAll().stream()
        .filter(w -> !List.of("已关闭", "已忽略", "已解除").contains(w.getStatus()))
        .sorted(Comparator.comparing(WarningEvent::getTriggeredAt, Comparator.nullsLast(Comparator.reverseOrder())))
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
    return dto;
  }

  @Transactional
  public WarningDto dispatch(Long id) {
    WarningEvent warning = warningRepository.findById(id).orElseThrow(() -> new AppException(404, "未找到预警"));
    warning.setStatus("处理中");
    warning.setDispatchedAt(OffsetDateTime.now());
    WarningDto dto = toDto(warningRepository.save(warning));
    broadcastWarning(dto);
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
  public WarningDto resolve(Long id, String operator) {
    WarningEvent warning = warningRepository.findById(id).orElseThrow(() -> new AppException(404, "未找到预警"));
    warning.setStatus("已解除");
    warning.setClosedAt(OffsetDateTime.now());
    warning.setCloseReason("自动解除");
    warning.setClosedBy(operator);
    WarningDto dto = toDto(warningRepository.save(warning));
    broadcastWarning(dto);
    return dto;
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
        warning.getHazardCode(),
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

  private boolean matches(WarningEvent warning, Map<String, HazardPoint> hazardIndex, String bbox, String keyword, String status) {
    if (keyword != null && !keyword.isBlank()) {
      String like = keyword.trim().toLowerCase();
      boolean matched = contains(warning.getTitle(), like) || contains(warning.getWarningLevel(), like) || contains(warning.getStatus(), like) || contains(warning.getHazardCode(), like);
      if (!matched) return false;
    }
    if (status != null && !status.isBlank() && !status.equals(warning.getStatus())) return false;
    if (bbox != null && !bbox.isBlank()) {
      HazardPoint hazard = hazardIndex.get(warning.getHazardCode());
      if (hazard == null || !withinBbox(hazard.getLongitude(), hazard.getLatitude(), bbox)) return false;
    }
    return true;
  }

  private boolean withinBbox(Double lon, Double lat, String bbox) {
    if (bbox == null || bbox.isBlank()) return true;
    if (lon == null || lat == null) return false;
    String[] parts = bbox.split(",");
    if (parts.length != 4) return true;
    try {
      double minLon = Double.parseDouble(parts[0].trim());
      double minLat = Double.parseDouble(parts[1].trim());
      double maxLon = Double.parseDouble(parts[2].trim());
      double maxLat = Double.parseDouble(parts[3].trim());
      return lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
    } catch (NumberFormatException ex) {
      return true;
    }
  }

  private boolean contains(String value, String keyword) {
    return value != null && value.toLowerCase().contains(keyword);
  }

  private String cssClass(String level) {
    if (level == null) return "blue";
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
}
