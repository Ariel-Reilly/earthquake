package cn.disasterguard.governance;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.governance.entity.SyncTaskRecord;
import cn.disasterguard.governance.repository.SyncTaskRecordRepository;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

  private final SyncTaskRecordRepository syncTaskRepository;

  public SyncController(SyncTaskRecordRepository syncTaskRepository) {
    this.syncTaskRepository = syncTaskRepository;
  }

  /** 种子数据（幂等：仅当表为空时写入；payload 内时间为 ISO 字符串）。 */
  @PostConstruct
  void seed() {
    if (syncTaskRepository.count() == 0) {
      addTask("SYNC-001", "USGS_EARTHQUAKE", "RUNNING", 12_340, LocalDateTime.now().minusMinutes(5), 0);
      addTask("SYNC-002", "CENC_SEISMIC", "COMPLETED", 8_200, LocalDateTime.now().minusHours(1), 0);
      addTask("SYNC-003", "WEATHER_GovCN", "PAUSED", 45_100, LocalDateTime.now().minusHours(3), 2);
      addTask("SYNC-004", "GPS_CORS", "ERROR", 1_230, LocalDateTime.now().minusHours(6), 5);
      addTask("SYNC-005", "RAINFALL_HYET", "RUNNING", 98_700, LocalDateTime.now().minusMinutes(2), 0);
    }
  }

  private void addTask(String id, String source, String status, long records, LocalDateTime lastSync, int errors) {
    Map<String, Object> t = new LinkedHashMap<>();
    t.put("taskId", id);
    t.put("source", source);
    t.put("status", status);
    t.put("recordsSynced", records);
    t.put("lastSyncAt", lastSync.toString());
    t.put("errorCount", errors);
    SyncTaskRecord record = new SyncTaskRecord();
    record.setId(id);
    record.setPayload(t);
    syncTaskRepository.save(record);
  }

  // ── Mock sync history ───────────────────────────────────────────────────────
  private static final List<Map<String, Object>> HISTORY = new ArrayList<>();

  {
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

  {
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
    List<Map<String, Object>> tasks = syncTaskRepository.findAll().stream()
        .map(SyncTaskRecord::getPayload)
        .collect(Collectors.toList());
    long running = tasks.stream().filter(t -> "RUNNING".equals(t.get("status"))).count();
    long paused = tasks.stream().filter(t -> "PAUSED".equals(t.get("status"))).count();
    long error = tasks.stream().filter(t -> "ERROR".equals(t.get("status"))).count();
    long totalRecords = tasks.stream()
        .mapToLong(t -> ((Number) t.get("recordsSynced")).longValue())
        .sum();

    return ApiResponse.success(Map.of(
        "totalTasks", tasks.size(),
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
    return ApiResponse.success(syncTaskRepository.findByOrderByIdAsc().stream()
        .map(SyncTaskRecord::getPayload)
        .collect(Collectors.toCollection(ArrayList::new)));
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
    SyncTaskRecord record = syncTaskRepository.findById(id).orElse(null);
    if (record == null) {
      return ApiResponse.fail(404, "Sync task not found: " + id);
    }
    Map<String, Object> t = record.getPayload();
    t.put("status", "RUNNING");
    t.put("lastSyncAt", LocalDateTime.now().toString());
    t.put("errorCount", 0);
    syncTaskRepository.save(record);
    addHistory("H-" + System.currentTimeMillis(), id, (String) t.get("source"),
        "RUNNING", 0, 0, LocalDateTime.now(), null);
    return ApiResponse.success("同步任务已触发", t);
  }

  @PostMapping("/tasks/{id}/pause")
  @PreAuthorize("hasAuthority('sync:manage')")
  public ApiResponse<Map<String, Object>> pause(@PathVariable String id) {
    SyncTaskRecord record = syncTaskRepository.findById(id).orElse(null);
    if (record == null) {
      return ApiResponse.fail(404, "Sync task not found: " + id);
    }
    record.getPayload().put("status", "PAUSED");
    syncTaskRepository.save(record);
    return ApiResponse.success("同步任务已暂停", record.getPayload());
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
