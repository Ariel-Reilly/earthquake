package cn.disasterguard.todo;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.emergency.repository.EmergencyEventRepository;
import cn.disasterguard.event.EventState;
import cn.disasterguard.event.repository.UnifiedEventRepository;
import cn.disasterguard.task.repository.InspectionTaskRepository;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 待办中心汇总（v1.7.0+ P2-1 顶栏红点）。
 *
 * <p>前端 dashboardApi.getTodoSummary 期望返回四个计数：
 * <ul>
 *   <li>{@code confirmation} — 待我确认（DETECTED + CONFIRMING）</li>
 *   <li>{@code approval} — 待我审批（warning_event.status = '处理中'，即已派发待审批）</li>
 *   <li>{@code pendingWarnings} — 待发布预警（warning_event.status = '待确认'）</li>
 *   <li>{@code openEmergency} — 进行中应急事件（非终态的 emergency_event）</li>
 * </ul>
 *
 * <p>四个数字之和作为侧栏"待办"菜单红点。
 */
@RestController
@RequestMapping("/api/todo")
public class TodoCenterController {

  private final UnifiedEventRepository unifiedEventRepository;
  private final WarningEventRepository warningRepository;
  private final EmergencyEventRepository emergencyRepository;
  private final InspectionTaskRepository taskRepository;

  public TodoCenterController(UnifiedEventRepository unifiedEventRepository,
                              WarningEventRepository warningRepository,
                              EmergencyEventRepository emergencyRepository,
                              InspectionTaskRepository taskRepository) {
    this.unifiedEventRepository = unifiedEventRepository;
    this.warningRepository = warningRepository;
    this.emergencyRepository = emergencyRepository;
    this.taskRepository = taskRepository;
  }

  @GetMapping("/summary")
  @PreAuthorize("hasAuthority('todo:view')")
  public ApiResponse<Map<String, Object>> summary() {
    long confirmation = unifiedEventRepository.findByState(EventState.DETECTED).size()
        + unifiedEventRepository.findByState(EventState.CONFIRMING).size();
    long pendingWarnings = warningRepository.findAll().stream()
        .filter(w -> "待确认".equals(w.getStatus()))
        .count();
    long approval = warningRepository.findAll().stream()
        .filter(w -> "处理中".equals(w.getStatus()))
        .count();
    // emergency_event 没有专用状态列，借用 count + 在终态列表外的简化估算；
    // 终态参考 EmergencyService：已关闭 / 已归档。
    long openEmergency = emergencyRepository.findAll().stream()
        .filter(e -> {
          String s = e.getStatus();
          return s == null || !(s.contains("关闭") || s.contains("归档"));
        })
        .count();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("confirmation", confirmation);
    result.put("approval", approval);
    result.put("pendingWarnings", pendingWarnings);
    result.put("openEmergency", openEmergency);
    return ApiResponse.success(result);
  }

  /** 待我确认列表（顶栏红点点击进入）。 */
  @GetMapping("/confirmations")
  @PreAuthorize("hasAuthority('todo:view')")
  public ApiResponse<List<Map<String, Object>>> listConfirmations() {
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    unifiedEventRepository.findByState(EventState.DETECTED).forEach(e -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("eventCode", e.getEventCode());
      row.put("hazardCode", e.getHazardCode());
      row.put("title", e.getTitle() == null ? "待确认灾害点" : e.getTitle());
      row.put("state", e.getState().name());
      row.put("updatedAt", e.getUpdatedAt());
      items.add(row);
    });
    return ApiResponse.success(items);
  }

  /** 待我审批列表（基于待发布 / 处理中 预警）。 */
  @GetMapping("/approvals")
  @PreAuthorize("hasAuthority('todo:view')")
  public ApiResponse<List<Map<String, Object>>> listApprovals() {
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    warningRepository.findAll().stream()
        .filter(w -> "待确认".equals(w.getStatus()) || "处理中".equals(w.getStatus()))
        .forEach(w -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", w.getId());
          row.put("warningCode", w.getWarningCode());
          row.put("title", w.getTitle());
          row.put("level", w.getWarningLevel());
          row.put("status", w.getStatus());
          row.put("triggeredAt", w.getTriggeredAt());
          items.add(row);
        });
    return ApiResponse.success(items);
  }

  /**
   * 待处理预警列表（status = '待确认'）。
   * GET /api/todo/warnings
   */
  @GetMapping("/warnings")
  @PreAuthorize("hasAuthority('todo:view')")
  public ApiResponse<List<Map<String, Object>>> listPendingWarnings() {
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    warningRepository.findAll().stream()
        .filter(w -> "待确认".equals(w.getStatus()))
        .sorted((a, b) -> {
          var ta = a.getTriggeredAt() == null ? java.time.OffsetDateTime.MIN : a.getTriggeredAt();
          var tb = b.getTriggeredAt() == null ? java.time.OffsetDateTime.MIN : b.getTriggeredAt();
          return tb.compareTo(ta);
        })
        .forEach(w -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("warningCode", w.getWarningCode());
          row.put("title", w.getTitle());
          row.put("level", w.getWarningLevel());
          row.put("triggeredAt", w.getTriggeredAt());
          items.add(row);
        });
    return ApiResponse.success(items);
  }

  /**
   * 待办任务列表（未关闭/未取消的巡检任务）。
   * GET /api/todo/tasks
   */
  @GetMapping("/tasks")
  @PreAuthorize("hasAuthority('todo:view')")
  public ApiResponse<List<Map<String, Object>>> listOpenTasks() {
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    taskRepository.findAll().stream()
        .filter(t -> {
          String s = t.getStatus();
          return s != null && !("CLOSED".equals(s) || "CANCELLED".equals(s) || "已完成".equals(s) || "已取消".equals(s));
        })
        .sorted((a, b) -> {
          var ta = a.getDueTime() == null ? java.time.OffsetDateTime.MAX : a.getDueTime();
          var tb = b.getDueTime() == null ? java.time.OffsetDateTime.MAX : b.getDueTime();
          return ta.compareTo(tb);
        })
        .forEach(t -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("taskCode", t.getTaskCode());
          row.put("title", t.getName());
          row.put("status", t.getStatus());
          row.put("assignee", t.getExecutor());
          row.put("dueDate", t.getDueTime());
          items.add(row);
        });
    return ApiResponse.success(items);
  }
}
