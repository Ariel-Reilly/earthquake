package cn.disasterguard.inspection;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.task.entity.InspectionTask;
import cn.disasterguard.task.repository.InspectionTaskRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 巡检报告（{@code inspection_task} 表的真实聚合）。
 *
 * <p>v1.8.0 起改为读取真实库：此前本端点循环生成 20 条完全虚构的记录
 * （写死 {@code totalRecords=156 / qualified=142 / unqualified=9}、
 * 北京坐标 39.9/116.4、张工/李工等假数据），且忽略 from/to/taskCode/hazardCode 过滤条件。
 *
 * <p>现在：
 * <ul>
 *   <li>记录来自 {@code inspection_task}（重庆 5 条真实任务）；</li>
 *   <li>summary 由真实数据聚合得出（按 status 统计，不再写死）；</li>
 *   <li>from/to/taskCode/hazardCode 过滤真实生效；</li>
 *   <li>库中无匹配数据时如实返回空 items + 0 计数，不伪造。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/inspection")
public class InspectionReportController {

  private final InspectionTaskRepository taskRepository;

  public InspectionReportController(InspectionTaskRepository taskRepository) {
    this.taskRepository = taskRepository;
  }

  @GetMapping("/report")
  @PreAuthorize("hasAuthority('inspection:view')")
  public ApiResponse<Map<String, Object>> report(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String taskCode,
      @RequestParam(required = false) String hazardCode) {

    String reportId = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    List<InspectionTask> all = taskRepository.findAll().stream()
        .sorted(java.util.Comparator.comparing(InspectionTask::getId))
        .toList();
    List<InspectionTask> filtered = all.stream()
        .filter(t -> taskCode == null || taskCode.isBlank() || taskCode.equals(t.getTaskCode()))
        .filter(t -> hazardCode == null || hazardCode.isBlank() || hazardCode.equals(t.getHazardCode()))
        .filter(t -> inPeriod(t, from, to))
        .toList();

    // ── summary：全部由真实数据聚合，不再写死 ──────────────────────────────
    int total = filtered.size();
    int completed = (int) filtered.stream().filter(t -> "已完成".equals(t.getStatus())).count();
    int inProgress = (int) filtered.stream().filter(t -> "执行中".equals(t.getStatus())).count();
    int pending = (int) filtered.stream().filter(t -> "待处理".equals(t.getStatus())).count();
    double completionRate = total > 0 ? Math.round(completed * 1000.0 / total) / 10.0 : 0.0;
    // 真实数据无 duration 字段：以 start→due 的时间跨度估算平均任务时长（分钟）
    double avgDurationMin = filtered.stream()
        .filter(t -> t.getStartTime() != null && t.getDueTime() != null)
        .mapToLong(t -> java.time.Duration.between(t.getStartTime(), t.getDueTime()).toMinutes())
        .filter(v -> v > 0)
        .average()
        .stream().map(v -> Math.round(v * 10) / 10.0).findFirst().orElse(0.0);

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("totalRecords", total);
    summary.put("completed", completed);
    summary.put("inProgress", inProgress);
    summary.put("pending", pending);
    summary.put("completionRate", completionRate);
    summary.put("avgDurationMin", avgDurationMin);
    summary.put("dataSource", "inspection_task（真实库聚合）");

    // ── items：直接来自真实任务，无虚构字段 ──────────────────────────────
    List<Map<String, Object>> items = new ArrayList<>(filtered.size());
    for (InspectionTask t : filtered) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("recordId", t.getTaskCode());
      row.put("taskCode", t.getTaskCode());
      row.put("taskName", t.getName());
      row.put("taskType", t.getTaskType());
      row.put("hazardCode", t.getHazardCode());
      row.put("regionCode", t.getRegionCode());
      row.put("inspector", t.getExecutor());
      row.put("status", t.getStatus());
      row.put("progress", t.getProgress());
      row.put("scheduledAt", t.getStartTime());
      row.put("dueAt", t.getDueTime());
      items.add(row);
    }

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("reportId", reportId);
    meta.put("generatedAt", LocalDateTime.now());
    meta.put("periodFrom", from != null ? from : LocalDate.now().minusDays(30).toString());
    meta.put("periodTo", to != null ? to : LocalDate.now().toString());
    meta.put("taskCode", taskCode);
    meta.put("hazardCode", hazardCode);
    meta.put("matchedTasks", total);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("reportId", reportId);
    response.put("generatedAt", LocalDateTime.now());
    response.put("summary", summary);
    response.put("items", items);
    response.put("meta", meta);
    return ApiResponse.success(response);
  }

  /** 时间过滤：任务起始时间落在 [from, to] 区间内。from/to 为 ISO 日期字符串。 */
  private boolean inPeriod(InspectionTask t, String from, String to) {
    if ((from == null || from.isBlank()) && (to == null || to.isBlank())) {
      return true;
    }
    OffsetDateTime start = t.getStartTime();
    if (start == null) {
      return false;
    }
    LocalDate d = start.toLocalDate();
    try {
      if (from != null && !from.isBlank() && d.isBefore(LocalDate.parse(from))) {
        return false;
      }
      if (to != null && !to.isBlank() && d.isAfter(LocalDate.parse(to))) {
        return false;
      }
    } catch (Exception ex) {
      // 日期格式非法时不做时间过滤（不因参数错误丢数据）
      return true;
    }
    return true;
  }
}
