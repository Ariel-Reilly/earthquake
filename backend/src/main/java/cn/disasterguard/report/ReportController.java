package cn.disasterguard.report;

import cn.disasterguard.common.api.ApiResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报告管理（Report）REST API。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET /api/reports/templates} — 模板列表</li>
 *   <li>{@code POST /api/reports/templates} — 创建模板</li>
 *   <li>{@code DELETE /api/reports/templates/{templateId}} — 删除模板</li>
 *   <li>{@code POST /api/reports/templates/{templateId}/run} — 立即运行</li>
 *   <li>{@code GET /api/reports/history} — 报告历史</li>
 *   <li>{@code POST /api/reports/history/{id}/rerun} — 重新运行</li>
 *   <li>{@code POST /api/reports/history/{id}/cancel} — 取消运行</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

  private static final AtomicLong TEMPLATE_ID_GEN = new AtomicLong(1000);
  private static final AtomicLong REPORT_ID_GEN = new AtomicLong(1000);

  private final Map<Long, Map<String, Object>> templates = new ConcurrentHashMap<>();
  private final Map<Long, Map<String, Object>> history = new ConcurrentHashMap<>();

  public ReportController() {
    initSampleData();
  }

  private void initSampleData() {
    String[] categories = {"日报", "周报", "月报", "专题报告"};
    String[] names = {"灾害监测日报", "预警统计周报", "应急响应月报", "震情专题报告"};
    for (int i = 0; i < 4; i++) {
      long id = TEMPLATE_ID_GEN.incrementAndGet();
      Map<String, Object> template = new LinkedHashMap<>();
      template.put("templateId", id);
      template.put("name", names[i]);
      template.put("category", categories[i]);
      template.put("description", names[i] + "模板，用于生成对应周期的统计报告");
      template.put("fields", List.of("时间范围", "灾害类型", "区域", "预警等级", "处置情况"));
      template.put("schedule", i < 3 ? "0 0 " + (i == 0 ? "8" : (i == 1 ? "9" : "10")) + " * * ?" : null);
      template.put("lastRunAt", OffsetDateTime.now().minusDays(i + 1));
      templates.put(id, template);
    }

    // 添加一些历史报告
    for (int i = 1; i <= 5; i++) {
      long id = REPORT_ID_GEN.incrementAndGet();
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("id", id);
      report.put("templateId", (long) (i % 4 + 1001));
      report.put("templateName", names[i % 4]);
      report.put("status", i <= 2 ? "SUCCESS" : (i == 3 ? "RUNNING" : "FAILED"));
      report.put("triggeredBy", "system");
      report.put("triggeredAt", OffsetDateTime.now().minusHours(i * 2));
      report.put("finishedAt", i <= 2 ? OffsetDateTime.now().minusHours(i * 2 - 1) : null);
      report.put("recordCount", 100 + i * 10);
      history.put(id, report);
    }
  }

  @GetMapping("/templates")
  public ApiResponse<Map<String, Object>> listTemplates(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String keyword) {
    var filtered = templates.values().stream()
        .filter(t -> category == null || category.isBlank()
            || category.equals(t.get("category")))
        .filter(t -> {
          if (keyword == null || keyword.isBlank()) return true;
          String k = keyword.toLowerCase();
          String name = String.valueOf(t.get("name")).toLowerCase();
          return name.contains(k);
        })
        .toList();

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);
    int total = filtered.size();
    int fromIndex = Math.min((safePage - 1) * safeSize, total);
    int toIndex = Math.min(fromIndex + safeSize, total);
    List<Map<String, Object>> items = filtered.subList(fromIndex, toIndex);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("total", total);
    return ApiResponse.success(result);
  }

  @PostMapping("/templates")
  public ApiResponse<Map<String, Object>> createTemplate(@RequestBody Map<String, Object> body) {
    long id = TEMPLATE_ID_GEN.incrementAndGet();
    Map<String, Object> template = new LinkedHashMap<>();
    template.put("templateId", id);
    template.put("name", body.getOrDefault("name", "新报告模板"));
    template.put("category", body.getOrDefault("category", "专题报告"));
    template.put("description", body.getOrDefault("description", ""));
    template.put("fields", body.getOrDefault("fields", new ArrayList<>()));
    template.put("schedule", body.get("schedule"));
    template.put("lastRunAt", null);
    templates.put(id, template);
    return ApiResponse.success(template);
  }

  @DeleteMapping("/templates/{templateId}")
  public ApiResponse<Map<String, Object>> deleteTemplate(@PathVariable Long templateId) {
    if (!templates.containsKey(templateId)) {
      return ApiResponse.fail(404, "模板不存在: " + templateId);
    }
    templates.remove(templateId);
    return ApiResponse.success(Map.of("deleted", templateId));
  }

  @PostMapping("/templates/{templateId}/run")
  public ApiResponse<Map<String, Object>> runTemplate(@PathVariable Long templateId) {
    Map<String, Object> template = templates.get(templateId);
    if (template == null) {
      return ApiResponse.fail(404, "模板不存在: " + templateId);
    }
    long reportId = REPORT_ID_GEN.incrementAndGet();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("reportId", reportId);
    report.put("status", "RUNNING");
    report.put("startedAt", OffsetDateTime.now());
    // 更新模板最后运行时间
    template.put("lastRunAt", OffsetDateTime.now());
    return ApiResponse.success(report);
  }

  @GetMapping("/history")
  public ApiResponse<Map<String, Object>> listHistory(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) Long templateId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String trigger) {
    var filtered = history.values().stream()
        .filter(h -> templateId == null || templateId.equals(h.get("templateId")))
        .filter(h -> status == null || status.isBlank()
            || status.equals(h.get("status")))
        .filter(h -> trigger == null || trigger.isBlank()
            || trigger.equals(h.get("triggeredBy")))
        .sorted((a, b) -> {
          var ta = (OffsetDateTime) b.get("triggeredAt");
          var tb = (OffsetDateTime) a.get("triggeredAt");
          return ta.compareTo(tb);
        })
        .toList();

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);
    int total = filtered.size();
    int fromIndex = Math.min((safePage - 1) * safeSize, total);
    int toIndex = Math.min(fromIndex + safeSize, total);
    List<Map<String, Object>> items = filtered.subList(fromIndex, toIndex);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("total", total);
    return ApiResponse.success(result);
  }

  @PostMapping("/history/{id}/rerun")
  public ApiResponse<Map<String, Object>> rerun(@PathVariable Long id) {
    Map<String, Object> existing = history.get(id);
    if (existing == null) {
      return ApiResponse.fail(404, "报告记录不存在: " + id);
    }
    long newId = REPORT_ID_GEN.incrementAndGet();
    Map<String, Object> rerun = new LinkedHashMap<>();
    rerun.put("id", newId);
    rerun.put("templateId", existing.get("templateId"));
    rerun.put("templateName", existing.get("templateName"));
    rerun.put("status", "RUNNING");
    rerun.put("triggeredBy", "rerun");
    rerun.put("triggeredAt", OffsetDateTime.now());
    rerun.put("finishedAt", null);
    rerun.put("recordCount", 0);
    history.put(newId, rerun);
    return ApiResponse.success(Map.of("id", newId, "status", "RUNNING"));
  }

  @PostMapping("/history/{id}/cancel")
  public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id) {
    Map<String, Object> report = history.get(id);
    if (report == null) {
      return ApiResponse.fail(404, "报告记录不存在: " + id);
    }
    if (!"RUNNING".equals(report.get("status"))) {
      return ApiResponse.fail(400, "只能取消运行中的报告");
    }
    report.put("status", "CANCELLED");
    report.put("finishedAt", OffsetDateTime.now());
    return ApiResponse.success(Map.of("id", id, "status", "CANCELLED"));
  }
}
