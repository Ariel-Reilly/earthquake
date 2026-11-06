package cn.disasterguard.task;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.export.ExcelExportService;
import cn.disasterguard.common.export.TaskExportRow;
import cn.disasterguard.task.dto.TaskDto;
import cn.disasterguard.task.dto.TaskUpsertRequest;
import cn.disasterguard.task.entity.InspectionTask;
import cn.disasterguard.task.service.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

  private final TaskService taskService;
  private final ExcelExportService excelExportService;

  public TaskController(TaskService taskService, ExcelExportService excelExportService) {
    this.taskService = taskService;
    this.excelExportService = excelExportService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('tasks:view')")
  public ApiResponse<PageResponse<TaskDto>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status
  ) {
    return ApiResponse.success(taskService.page(page, size, keyword, status));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('tasks:view')")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    return ApiResponse.success(Map.of("item", taskService.getById(id)));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('tasks:create')")
  public ApiResponse<TaskDto> create(@Valid @RequestBody TaskUpsertRequest request) {
    return ApiResponse.success("创建成功", taskService.create(request));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('tasks:edit')")
  public ApiResponse<TaskDto> update(@PathVariable Long id, @Valid @RequestBody TaskUpsertRequest request) {
    return ApiResponse.success("更新成功", taskService.update(id, request));
  }

  @PatchMapping("/{id}/status")
  @PreAuthorize("hasAuthority('tasks:edit')")
  public ApiResponse<TaskDto> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> payload) {
    return ApiResponse.success(taskService.updateStatus(id, payload.get("status")));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('tasks:delete')")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    taskService.delete(id);
    return ApiResponse.success("删除成功", null);
  }

  @PostMapping("/export")
  @PreAuthorize("hasAuthority('tasks:export')")
  public void export(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
    excelExportService.export(response, "巡检任务导出.xlsx", TaskExportRow.class, taskService.exportRows());
  }

  // ============================================================ //
  // ============================================================ //

  /**
   * 任务运行时信息（进度、当前步骤、耗时）。
   * GET /api/tasks/{id}/runtime
   */
  @GetMapping("/{id}/runtime")
  @PreAuthorize("hasAuthority('tasks:view')")
  public ApiResponse<Map<String, Object>> taskRuntime(@PathVariable Long id) {
    InspectionTask task = taskService.findEntityById(id);
    if (task == null) {
      return ApiResponse.fail(404, "未找到任务");
    }

    java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
    long durationSeconds = 0;
    if (task.getStartTime() != null) {
      durationSeconds = java.time.Duration.between(task.getStartTime(), now).getSeconds();
    }

    // 根据状态估算剩余时间（简化：未完成按平均每 10% 需 60 秒估算）
    long estimatedRemaining = 0;
    if (task.getProgress() != null && task.getProgress() < 100) {
      if (durationSeconds > 0 && task.getProgress() > 0) {
        long secondsPerPercent = durationSeconds / task.getProgress();
        estimatedRemaining = secondsPerPercent * (100 - task.getProgress());
      } else {
        estimatedRemaining = (100 - task.getProgress()) * 60L;
      }
    }

    Map<String, Object> runtime = new java.util.LinkedHashMap<>();
    runtime.put("taskId", id);
    runtime.put("status", task.getStatus());
    runtime.put("progress", task.getProgress() != null ? task.getProgress() : 0);
    runtime.put("currentStep", getCurrentStep(task));
    runtime.put("startedAt", task.getStartTime() != null ? task.getStartTime().toString() : null);
    runtime.put("durationSeconds", durationSeconds);
    runtime.put("estimatedRemainingSeconds", estimatedRemaining);
    runtime.put("lastHeartbeat", task.getStartTime() != null ? task.getStartTime().plusMinutes(task.getProgress() != null ? task.getProgress() : 0).toString() : null);

    return ApiResponse.success(Map.of("item", runtime));
  }

  /**
   * 任务执行日志（分页）。
   * GET /api/tasks/{id}/execution-log
   */
  @GetMapping("/{id}/execution-log")
  @PreAuthorize("hasAuthority('tasks:view')")
  public ApiResponse<Map<String, Object>> taskExecutionLog(
      @PathVariable Long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int size
  ) {
    InspectionTask task = taskService.findEntityById(id);
    if (task == null) {
      return ApiResponse.fail(404, "未找到任务");
    }

    // 模拟执行日志（实际应从执行日志表读取）
    List<Map<String, Object>> items = List.of(
        Map.of(
            "timestamp", task.getStartTime() != null ? task.getStartTime().plusMinutes(1).toString() : java.time.OffsetDateTime.now().minusMinutes(30).toString(),
            "level", "INFO",
            "step", "TASK_INIT",
            "message", "任务已创建，开始调度",
            "durationMs", 120
        ),
        Map.of(
            "timestamp", task.getStartTime() != null ? task.getStartTime().plusMinutes(2).toString() : java.time.OffsetDateTime.now().minusMinutes(28).toString(),
            "level", "INFO",
            "step", "ROUTE_PLAN",
            "message", "路线规划完成",
            "durationMs", 350
        ),
        Map.of(
            "timestamp", task.getStartTime() != null ? task.getStartTime().plusMinutes(5).toString() : java.time.OffsetDateTime.now().minusMinutes(25).toString(),
            "level", "INFO",
            "step", "DEVICE_CHECK",
            "message", "设备状态检查通过",
            "durationMs", 80
        ),
        Map.of(
            "timestamp", task.getStartTime() != null ? task.getStartTime().plusMinutes(10).toString() : java.time.OffsetDateTime.now().minusMinutes(20).toString(),
            "level", "WARN",
            "step", "DATA_COLLECT",
            "message", "部分传感器数据采集中",
            "durationMs", 200
        ),
        Map.of(
            "timestamp", task.getStartTime() != null ? task.getStartTime().plusMinutes(15).toString() : java.time.OffsetDateTime.now().minusMinutes(15).toString(),
            "level", "INFO",
            "step", "DATA_REPORT",
            "message", "数据已上报至服务器",
            "durationMs", 150
        )
    );

    return ApiResponse.success(Map.of(
        "items", items,
        "total", items.size(),
        "page", page,
        "size", size
    ));
  }

  /**
   * 重试失败任务。
   * POST /api/tasks/{id}/retry
   */
  @PostMapping("/{id}/retry")
  @PreAuthorize("hasAuthority('tasks:edit')")
  public ApiResponse<TaskDto> retryTask(@PathVariable Long id) {
    InspectionTask task = taskService.findEntityById(id);
    if (task == null) {
      return ApiResponse.fail(404, "未找到任务");
    }
    if (!"已失败".equals(task.getStatus()) && !"FAILED".equalsIgnoreCase(task.getStatus())) {
      return ApiResponse.fail(400, "仅允许重试失败状态的任务");
    }

    task.setStatus("待执行");
    task.setProgress(0);
    task.setStartTime(java.time.OffsetDateTime.now());
    task.setDueTime(java.time.OffsetDateTime.now().plusHours(8));
    taskService.getRepository().save(task);

    return ApiResponse.success("任务已重试", taskService.toDto(task));
  }

  /**
   * 终止运行中任务。
   * POST /api/tasks/{id}/terminate
   */
  @PostMapping("/{id}/terminate")
  @PreAuthorize("hasAuthority('tasks:edit')")
  public ApiResponse<TaskDto> terminateTask(@PathVariable Long id) {
    InspectionTask task = taskService.findEntityById(id);
    if (task == null) {
      return ApiResponse.fail(404, "未找到任务");
    }
    String runningStatus = task.getStatus();
    if ("执行中".equals(runningStatus) || "RUNNING".equalsIgnoreCase(runningStatus)
        || "进行中".equals(runningStatus) || "PENDING".equalsIgnoreCase(runningStatus)) {
      task.setStatus("已取消");
      taskService.getRepository().save(task);
      return ApiResponse.success("任务已终止", taskService.toDto(task));
    }
    return ApiResponse.fail(400, "当前状态不允许终止");
  }

  /** 根据 task.status + progress 返回执行步骤描述，用于前端轮询时实时显示进度文案。 */
  private String getCurrentStep(InspectionTask task) {
    if (task == null || task.getStatus() == null) return "未知";
    return switch (task.getStatus()) {
      case "待执行", "PENDING" -> "等待执行";
      case "执行中", "RUNNING", "进行中" -> {
        Integer progress = task.getProgress();
        if (progress != null) {
          if (progress < 20) yield "路线规划中";
          if (progress < 50) yield "现场巡检中";
          if (progress < 80) yield "数据采集中";
          if (progress < 100) yield "数据上报中";
        }
        yield "执行中";
      }
      case "已完成" -> "已完成";
      case "FAILED", "已失败" -> "已失败";
      case "CANCELLED", "已取消" -> "已取消";
      default -> task.getStatus();
    };
  }
}
