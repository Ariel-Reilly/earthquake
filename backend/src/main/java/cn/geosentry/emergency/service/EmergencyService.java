package cn.geosentry.emergency.service;

import cn.geosentry.common.api.PageResponse;
import cn.geosentry.common.exception.AppException;
import cn.geosentry.common.export.EmergencyExportRow;
import cn.geosentry.emergency.dto.EmergencyDto;
import cn.geosentry.emergency.entity.EmergencyEvent;
import cn.geosentry.emergency.repository.EmergencyEventRepository;
import cn.geosentry.realtime.RealtimeBroadcastService;
import jakarta.persistence.criteria.Predicate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应急事件：与预警联动（派发橙/红预警时自动生成），支持状态机：
 * {@code 待处置 → 处置中 → 已闭环}。
 */
@Service
public class EmergencyService {

  private static final Logger log = LoggerFactory.getLogger(EmergencyService.class);
  public static final String STATUS_RESOLVED = "已闭环";

  private final EmergencyEventRepository repository;
  private final RealtimeBroadcastService broadcastService;

  public EmergencyService(EmergencyEventRepository repository, RealtimeBroadcastService broadcastService) {
    this.repository = repository;
    this.broadcastService = broadcastService;
  }

  public PageResponse<EmergencyDto> page(int page, int size, String keyword, String status) {
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 200));
    Specification<EmergencyEvent> spec = (root, query, cb) -> {
      Predicate predicate = cb.conjunction();
      if (keyword != null && !keyword.isBlank()) {
        String like = "%" + keyword.trim().toLowerCase() + "%";
        predicate = cb.and(predicate, cb.or(
            cb.like(cb.lower(root.get("title")), like),
            cb.like(cb.lower(root.get("eventCode")), like),
            cb.like(cb.lower(root.get("commander")), like)
        ));
      }
      if (status != null && !status.isBlank()) {
        predicate = cb.and(predicate, cb.equal(root.get("status"), status));
      }
      return predicate;
    };
    var result = repository.findAll(spec, pageable);
    return new PageResponse<>(
        result.getContent().stream().map(this::toDto).toList(),
        result.getTotalElements(), page, size, result.getTotalPages());
  }

  public EmergencyDto getById(Long id) {
    return repository.findById(id).map(this::toDto).orElseThrow(() -> new AppException(404, "未找到应急事件"));
  }

  /**
   * 预警派发联动：由 WarningService.dispatch 调用。
   *
   * <p>幂等机制（三层防御）：
   * <ol>
   *   <li>快速路径：existsByWarningCodeAndStatusNot 检查是否已有未闭环事件</li>
   *   <li>数据库唯一约束：schema.sql 对 warning_code + status != '已闭环' 建部分唯一索引
   *       （PostgreSQL CREATE UNIQUE INDEX ... WHERE status != '已闭环'）</li>
   *   <li>异常兜底：捕获 DataIntegrityViolationException（并发同时通过快速路径时），
   *       回查已存在事件并返回，保证调用方拿到一致结果</li>
   * </ol>
   *
   * @return 新建的应急事件；若已存在未闭环事件则返回 null（调用方应忽略）
   */
  @Transactional
  public EmergencyDto createFromWarning(String warningCode, String hazardCode, String title, String commander) {
    // 快速路径：99.9% 情况在这里短路
    if (repository.existsByWarningCodeAndStatusNot(warningCode, STATUS_RESOLVED)) {
      return null;
    }
    try {
      EmergencyEvent event = new EmergencyEvent();
      event.setEventCode("EM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
      event.setWarningCode(warningCode);
      event.setHazardCode(hazardCode);
      event.setTitle(title);
      event.setStatus("处置中");
      event.setCommander(commander);
      event.setStepName("会商");
      event.setStartedAt(OffsetDateTime.now());
      event.setCreatedAt(OffsetDateTime.now());
      event.setUpdatedAt(OffsetDateTime.now());
      EmergencyDto dto = toDto(repository.save(event));
      broadcastEmergency(dto);
      return dto;
    } catch (DataIntegrityViolationException ex) {
      // 并发竞态：另一个线程先插入成功。回查已存在事件并返回 null（表示"不需要新建"）。
      log.info("createFromWarning 并发命中，已存在未闭环应急事件 warningCode={}", warningCode);
      return null;
    }
  }

  /** 事件闭环。 */
  @Transactional
  public EmergencyDto resolve(Long id, String summary) {
    EmergencyEvent event = repository.findById(id).orElseThrow(() -> new AppException(404, "未找到应急事件"));
    event.setStatus(STATUS_RESOLVED);
    event.setClosedAt(OffsetDateTime.now());
    if (summary != null && !summary.isBlank()) {
      event.setResultSummary(summary);
    }
    event.setUpdatedAt(OffsetDateTime.now());
    EmergencyDto dto = toDto(repository.save(event));
    broadcastEmergency(dto);
    return dto;
  }

  public boolean exists(Long id) {
    return repository.existsById(id);
  }

  /** 导出用：返回应急事件导出行。 */
  public List<EmergencyExportRow> exportRows() {
    return repository.findAll().stream()
        .sorted(java.util.Comparator.comparing(EmergencyEvent::getId, java.util.Comparator.nullsLast(Long::compareTo)))
        .map(e -> new EmergencyExportRow(
            e.getEventCode(), e.getWarningCode(), e.getHazardCode(), e.getTitle(), e.getStatus(),
            e.getCommander(), e.getStepName(), e.getResultSummary(), e.getStartedAt(), e.getClosedAt()))
        .toList();
  }

  @Transactional
  public void delete(Long id) {
    repository.deleteById(id);
  }

  private EmergencyDto toDto(EmergencyEvent e) {
    return new EmergencyDto(
        e.getId(), e.getEventCode(), e.getWarningCode(), e.getHazardCode(), e.getTitle(),
        e.getStatus(), e.getCommander(), e.getStepName(), e.getResultSummary(), e.getStartedAt(), e.getClosedAt());
  }

  /** 广播应急事件状态变更到 WebSocket。 */
  private void broadcastEmergency(EmergencyDto dto) {
    try {
      broadcastService.broadcastEmergency(dto);
    } catch (Exception e) {
      log.debug("WebSocket 广播应急事件失败: {}", e.getMessage());
    }
  }
}
