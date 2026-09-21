package cn.disasterguard.governance;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

  // ── Mock sync task store ─────────────────────────────────────────────────────
  private static final Map<String, Map<String, Object>> TASKS = new ConcurrentHashMap<>();

  static {
    addTask("SYNC-001", "USGS_EARTHQUAKE", "RUNNING", 12_340, LocalDateTime.now().minusMinutes(5), 0);
    addTask("SYNC-002", "CENC_SEISMIC", "COMPLETED", 8_200, LocalDateTime.now().minusHours(1), 0);
    addTask("SYNC-003", "WEATHER_GovCN", "PAUSED", 45_100, LocalDateTime.now().minusHours(3), 2);
    addTask("SYNC-004", "GPS_CORS", "ERROR", 1_230, LocalDateTime.now().minusHours(6), 5);
    addTask("SYNC-005", "RAINFALL_HYET", "RUNNING", 98_700, LocalDateTime.now().minusMinutes(2), 0);
  }

  private static void addTask(String id, String source, String status, long records, LocalDateTime lastSync, int errors) {
    Map<String, Object> t = new LinkedHashMap<>();
    t.put("taskId", id);
    t.put("source", source);
    t.put("status", status);
    t.put("recordsSynced", records);
    t.put("lastSyncAt", lastSync);
    t.put("errorCount", errors);
    TASKS.put(id, t);
  }

  // ── Mock sync history ───────────────────────────────────────────────────────
  private static final List<Map<String, Object>> HISTORY = new ArrayList<>();

  static {
    addHistory("H-001", "SYNC-001", "USGS_EARTHQUAKE", "COMPLETED", 1_540, 180, LocalDateTime.now().minusHours(1), null);
    addHistory("H-002", "SYNC-002", "CENC_SEISMIC", "COMPLETED", 820, 95, LocalDateTime.now().minusHours(2), null);
    addHistory("H-003", "SYNC-003", "WEATHER_GovCN", "ERROR", 312, 120, LocalDateTime.now().minusHours(3), "连接超时");
    addHistory("H-004", "SYNC-004", "GPS_CORS", "COMPLETED", 45, 30, LocalDateTime.now().minusHours(5), null);
    addHistory("H-005", "SYNC-001", "USGS_EARTHQUAKE", "COMPLETED", 1_600, 200, LocalDateTime.now().minusHours(6), null);
    addHistory("H-006", "SYNC-005", "RAINFALL_HYET", "COMPLETED", 12_300, 60, LocalDateTime.now().minusMinutes(30), null);
  }

  private static void addHistory(String id, String taskId, String source, String status,
                                  long records, int durationSec,
                                  LocalDateTime finishedAt, String error) {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("id", id);
    h.put("taskId", taskId);
    h.put("source", source);
    h.put("status", status);
    h.put("recordsProcessed", records);
    h.put("durationSec", durationSec);
    h.put("startedAt", finishedAt.minusSeconds(durationSec));
    h.put("finishedAt", finishedAt);
    h.put("error", error);
    HISTORY.add(0, h); // newest first
  }

  // ── Mock anomalies ───────────────────────────────────────────────────────────
  private static final List<Map<String, Object>> ANOMALIES = new ArrayList<>();

  static {
    ANOMALIES.add(Map.of(
        "id", "ANOM-001", "source", "GPS_CORS",
        "type", "MISSING_FIELD", "description", "gps_x 字段在最近3条记录中为空",
        "detectedAt", LocalDateTime.now().minusHours(1), "severity", "WARNING"));
    ANOMALIES.add(Map.of(
        "id", "ANOM-002", "source", "WEATHER_GovCN",
        "type", "DUPLICATE", "description", "检测到重复记录 ID: WTH-20260915-001 ~ WTH-20260915-008",
        "detectedAt", LocalDateTime.now().minusHours(2), "severity", "ERROR"));
    ANOMALIES.add(Map.of(
        "id", "ANOM-003", "source", "CENC_SEISMIC",
        "type", "OUTLIER", "description", "震级 -9.2 超出合理范围（ML 0~9）",
        "detectedAt", LocalDateTime.now().minusHours(5), "severity", "WARNING"));
  }

  @GetMapping("/stats")
  @PreAuthorize("hasAuthority('sync:view')")
  public ApiResponse<Map<String, Object>> stats() {
    long running = TASKS.values().stream().filter(t -> "RUNNING".equals(t.get("status"))).count();
    long paused = TASKS.values().stream().filter(t -> "PAUSED".equals(t.get("status"))).count();
    long error = TASKS.values().stream().filter(t -> "ERROR".equals(t.get("status"))).count();
    long totalRecords = TASKS.values().stream().mapToLong(t -> (Long) t.get("recordsSynced")).sum();

    return ApiResponse.success(Map.of(
        "totalTasks", TASKS.size(),
        "runningTasks", running,
        "pausedTasks", paused,
        "errorTasks", error,
        "totalRecords", totalRecords,
        "lastSyncAt", LocalDateTime.now().minusMinutes(2),
        "online", true,
        "anomalies", ANOMALIES.size()
    ));
  }

  @GetMapping("/tasks")
  @PreAuthorize("hasAuthority('sync:view')")
  public ApiResponse<List<Map<String, Object>>> listTasks() {
    return ApiResponse.success(new ArrayList<>(TASKS.values()));
  }

  @GetMapping("/history")
  @PreAuthorize("hasAuthority('sync:view')")
  public ApiResponse<PageResponse<Map<String, Object>>> history(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String task,
      @RequestParam(required = false) String status
  ) {
    List<Map<String, Object>> all = new ArrayList<>(HISTORY);
    if (task != null && !task.isBlank()) {
      all.removeIf(h -> !h.get("taskId").equals(task));
    }
    if (status != null && !status.isBlank()) {
      all.removeIf(h -> !h.get("status").equals(status));
    }
    return ApiResponse.success(paginate(all, page, size));
  }

  @GetMapping("/integrity")
  @PreAuthorize("hasAuthority('sync:view')")
  public ApiResponse<Map<String, Object>> integrity() {
    return ApiResponse.success(Map.of(
        "totalRecords", 6_561_112,
        "missingFields", 87,
        "duplicates", 12,
        "anomalies", ANOMALIES.size(),
        "coverage", 99.2,
        "lastCheckedAt", LocalDateTime.now().minusMinutes(15)
    ));
  }

  @GetMapping("/anomalies")
  @PreAuthorize("hasAuthority('sync:view')")
  public ApiResponse<List<Map<String, Object>>> anomalies() {
    return ApiResponse.success(new ArrayList<>(ANOMALIES));
  }

  @PostMapping("/tasks/{id}/trigger")
  @PreAuthorize("hasAuthority('sync:manage')")
  public ApiResponse<Map<String, Object>> trigger(@PathVariable String id) {
    Map<String, Object> t = TASKS.get(id);
    if (t == null) {
      return ApiResponse.fail(404, "Sync task not found: " + id);
    }
    t.put("status", "RUNNING");
    t.put("lastSyncAt", LocalDateTime.now());
    t.put("errorCount", 0);
    addHistory("H-" + System.currentTimeMillis(), id, (String) t.get("source"),
        "RUNNING", 0, 0, LocalDateTime.now(), null);
    return ApiResponse.success("同步任务已触发", t);
  }

  @PostMapping("/tasks/{id}/pause")
  @PreAuthorize("hasAuthority('sync:manage')")
  public ApiResponse<Map<String, Object>> pause(@PathVariable String id) {
    Map<String, Object> t = TASKS.get(id);
    if (t == null) {
      return ApiResponse.fail(404, "Sync task not found: " + id);
    }
    t.put("status", "PAUSED");
    return ApiResponse.success("同步任务已暂停", t);
  }

  private static <T> PageResponse<T> paginate(List<T> list, int page, int size) {
    int total = list.size();
    int pages = (int) Math.ceil((double) total / size);
    int from = (page - 1) * size;
    int to = Math.min(from + size, total);
    List<T> records = from < total ? list.subList(from, to) : List.of();
    return new PageResponse<>(records, total, page, size, pages);
  }
}
