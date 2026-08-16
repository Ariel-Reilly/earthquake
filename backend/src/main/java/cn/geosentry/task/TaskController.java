package cn.geosentry.task;

import cn.geosentry.common.api.ApiResponse;
import cn.geosentry.common.api.PageResponse;
import cn.geosentry.common.export.ExcelExportService;
import cn.geosentry.common.export.TaskExportRow;
import cn.geosentry.task.dto.TaskDto;
import cn.geosentry.task.dto.TaskUpsertRequest;
import cn.geosentry.task.service.TaskService;
import jakarta.validation.Valid;
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
  public ApiResponse<PageResponse<TaskDto>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status
  ) {
    return ApiResponse.success(taskService.page(page, size, keyword, status));
  }

  @GetMapping("/{id}")
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
}
