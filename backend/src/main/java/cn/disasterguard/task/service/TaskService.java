package cn.disasterguard.task.service;

import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.common.export.TaskExportRow;
import cn.disasterguard.task.dto.TaskDto;
import cn.disasterguard.task.dto.TaskUpsertRequest;
import cn.disasterguard.task.entity.InspectionTask;
import cn.disasterguard.task.repository.InspectionTaskRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.criteria.Predicate;

@Service
public class TaskService {

  private final InspectionTaskRepository repository;

  public TaskService(InspectionTaskRepository repository) {
    this.repository = repository;
  }

  /** 供 Controller 直接获取 JPA Repository（用于实体级操作） */
  public InspectionTaskRepository getRepository() {
    return repository;
  }

  /** 根据 ID 查询实体（供 Controller 使用） */
  public InspectionTask findEntityById(Long id) {
    return repository.findById(id).orElse(null);
  }

  public PageResponse<TaskDto> page(int page, int size, String keyword, String status) {
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 200),
        Sort.by(Sort.Direction.DESC, "id"));
    Specification<InspectionTask> spec = (root, query, cb) -> {
      Predicate predicate = cb.conjunction();
      if (keyword != null && !keyword.isBlank()) {
        String like = "%" + keyword.trim().toLowerCase() + "%";
        predicate = cb.and(predicate, cb.or(
            cb.like(cb.lower(root.get("name")), like),
            cb.like(cb.lower(root.get("taskType")), like),
            cb.like(cb.lower(root.get("executor")), like),
            cb.like(cb.lower(root.get("status")), like)
        ));
      }
      if (status != null && !status.isBlank()) {
        predicate = cb.and(predicate, cb.equal(root.get("status"), status));
      }
      return predicate;
    };

    var result = repository.findAll(spec, pageable);
    List<TaskDto> records = result.getContent().stream().map(this::toDto).toList();
    return new PageResponse<>(records, result.getTotalElements(), page, size, result.getTotalPages());
  }

  public TaskDto getById(Long id) {
    return repository.findById(id).map(this::toDto).orElseThrow(() -> new AppException(404, "未找到任务"));
  }

  /** 导出用：返回巡检任务导出行。 */
  public List<TaskExportRow> exportRows() {
    return repository.findAll().stream()
        .sorted(Comparator.comparing(InspectionTask::getId))
        .map(t -> new TaskExportRow(t.getTaskCode(), t.getName(), t.getTaskType(), t.getExecutor(), t.getRegionCode(),
            t.getStatus(), t.getProgress(), t.getStartTime(), t.getDueTime()))
        .toList();
  }

  @Transactional
  public TaskDto create(TaskUpsertRequest request) {
    InspectionTask task = new InspectionTask();
    task.setTaskCode("TK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    apply(task, request);
    task.setStartTime(OffsetDateTime.now());
    task.setDueTime(OffsetDateTime.now().plusHours(8));
    return toDto(repository.save(task));
  }

  @Transactional
  public TaskDto update(Long id, TaskUpsertRequest request) {
    InspectionTask task = repository.findById(id).orElseThrow(() -> new AppException(404, "未找到任务"));
    apply(task, request);
    return toDto(repository.save(task));
  }

  @Transactional
  public TaskDto updateStatus(Long id, String status) {
    InspectionTask task = repository.findById(id).orElseThrow(() -> new AppException(404, "未找到任务"));
    task.setStatus(status);
    if (Objects.equals(status, "已完成")) {
      task.setProgress(100);
    }
    return toDto(repository.save(task));
  }

  @Transactional
  public void delete(Long id) {
    if (!repository.existsById(id)) {
      throw new AppException(404, "未找到任务");
    }
    repository.deleteById(id);
  }

  private void apply(InspectionTask task, TaskUpsertRequest request) {
    task.setName(request.name());
    task.setTaskType(request.type());
    task.setExecutor(request.owner());
    task.setRegionCode(request.regionCode());
    task.setStatus(request.status());
    task.setProgress(request.progress());
  }

  /** 将实体转换为 DTO（供 Controller 复用） */
  public TaskDto toDto(InspectionTask task) {
    return new TaskDto(task.getId(), task.getTaskCode(), task.getName(), task.getTaskType(), task.getExecutor(), task.getRegionCode(), task.getStatus(), task.getProgress(), task.getStartTime(), task.getDueTime());
  }
}
