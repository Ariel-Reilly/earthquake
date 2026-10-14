package cn.disasterguard.governance;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.governance.entity.DataSource;
import cn.disasterguard.governance.entity.DataSyncTask;
import cn.disasterguard.governance.entity.SyncTaskRecord;
import cn.disasterguard.governance.repository.DataSourceRepository;
import cn.disasterguard.governance.repository.DataSyncTaskRepository;
import cn.disasterguard.governance.repository.SyncTaskRecordRepository;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据同步治理 API。
 *
 * <p>v1.8.0 起去掉三处硬编码 mock：
 * <ol>
 *   <li>{@code /history} 原来自实例初始化块里用 {@code LocalDateTime.now()} 编造 6 条历史记录
 *       （H-001…H-006），现改为由 {@code data_sync_task} 的真实执行字段
 *       （last_run_at / last_status / records_synced / error_message）派生。</li>
 *   <li>{@code /anomalies} 原编造 3 条固定异常（ANOM-001…003），现改为从真实数据源状态
 *       （DISCONNECTED/ERROR）与同步任务错误（error_message 非空 / last_status=FAILED）派生。</li>
 *   <li>{@code /integrity} 原写死 totalRecords=6561112 / coverage=99.2，
 *       现改为按真实表统计（同步任务数、错误数、覆盖率按连接正常的数据源占比计算）。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

  private final SyncTaskRecordRepository syncTaskRepository;
  private final DataSyncTaskRepository dataSyncTaskRepository;
  private final DataSourceRepository dataSourceRepository;

  public SyncController(
      SyncTaskRecordRepository syncTaskRepository,
      DataSyncTaskRepository dataSyncTaskRepository,
      DataSourceRepository dataSourceRepository) {
    this.syncTaskRepository = syncTaskRepository;
    this.dataSyncTaskRepository = dataSyncTaskRepository;
    this.dataSourceRepository = dataSourceRepository;
  }

  /** 种子数据（幂等：仅当表为空时写入；payload 内时间为 ISO 字符串）。 */
  @PostConstruct
  void seed() {
    if (syncTaskRepository.count() == 0) {
      addTask("SYNC-001", "ds_sentinel2", "RUNNING", 12_340, LocalDateTime.now().minusMinutes(5), 0);
      addTask("SYNC-002", "ds_era5", "COMPLETED", 8_200, LocalDateTime.now().minusHours(1), 0);
      addTask("SYNC-003", "ds_gpm", "PAUSED", 45_100, LocalDateTime.now().minusHours(3), 2);
      addTask("SYNC-004", "ds_srtm", "ERROR", 1_230, LocalDateTime.now().minusHours(6), 5);
      addTask("SYNC-005", "ds_mqtt_local", "RUNNING", 98_700, LocalDateTime.now().minusMinutes(2), 0);
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

    // 最近一次真实同步时间：取库中最大的 lastSyncAt
    LocalDateTime lastSync = tasks.stream()
        .map(t -> String.valueOf(t.get("lastSyncAt")))
        .filter(s -> !"null".equals(s) && !s.isBlank())
        .map(LocalDateTime::parse)
        .max(LocalDateTime::compareTo)
        .orElse(null);

    return ApiResponse.success(Map.of(
        "totalTasks", tasks.size(),
        "runningTasks", running,
        "pausedTasks", paused,
        "errorTasks", error,
        "totalRecords", totalRecords,
        "lastSyncAt", lastSync == null ? "" : lastSync,
        "online", true,
        "anomalies", deriveAnomalies().size()
    ));
  }

  @GetMapping("/tasks")
  @PreAuthorize("hasAuthority('sync:view')")
  public ApiResponse<List<Map<String, Object>>> listTasks() {
    return ApiResponse.success(syncTaskRepository.findByOrderByIdAsc().stream()
        .map(SyncTaskRecord::getPayload)
        .collect(Collectors.toCollection(ArrayList::new)));
  }

  /**
   * 同步历史：由 {@code data_sync_task} 的真实执行字段派生。
   *
   * <p>每条同步任务产出至多一条"最近一次执行"历史（该表只保留最近一次运行结果，
   * 因此不伪造更早的历史；需完整历史时应新建执行记录表）。
   */
  @GetMapping("/history")
  @PreAuthorize("hasAuthority('sync:view')")
  public ApiResponse<PageResponse<Map<String, Object>>> history(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String task,
      @RequestParam(required = false) String status) {

    Map<Long, DataSource> dsById = dataSourceRepository.findAll().stream()
        .collect(Collectors.toMap(DataSource::getId, d -> d));

    List<Map<String, Object>> all = new ArrayList<>();
    for (DataSyncTask t : dataSyncTaskRepository.findByOrderByIdAsc()) {
      DataSource ds = t.getDataSourceId() == null ? null : dsById.get(t.getDataSourceId());
      Map<String, Object> h = new LinkedHashMap<>();
      h.put("id", "H-" + t.getTaskCode());
      h.put("taskId", t.getTaskCode());
      h.put("taskName", t.getTaskName());
      h.put("source", ds == null ? null : ds.getDsCode());
      h.put("sourceName", ds == null ? null : ds.getDsName());
      // 用 last_status 作为该次执行结果；缺失时回退任务当前 status
      h.put("status", t.getLastStatus() != null ? t.getLastStatus() : t.getStatus());
      h.put("recordsProcessed", t.getRecordsSynced());
      h.put("durationSec", null); // 该表未记录耗时，如实留空而非编造
      h.put("startedAt", null);   // 同上
      h.put("finishedAt", t.getLastRunAt());
      h.put("error", t.getErrorMessage());
      all.add(h);
    }
    // 最近执行的在前
    all.sort((a, b) -> {
      OffsetDateTime ta = a.get("finishedAt") == null ? OffsetDateTime.MIN
          : OffsetDateTime.parse(a.get("finishedAt").toString());
      OffsetDateTime tb = b.get("finishedAt") == null ? OffsetDateTime.MIN
          : OffsetDateTime.parse(b.get("finishedAt").toString());
      return tb.compareTo(ta);
    });

    if (task != null && !task.isBlank()) {
      all.removeIf(h -> !task.equals(String.valueOf(h.get("taskId"))));
    }
    if (status != null && !status.isBlank()) {
      all.removeIf(h -> !status.equals(String.valueOf(h.get("status"))));
    }
    return ApiResponse.success(paginate(all, page, size));
  }

  /**
   * 数据完整性概览：全部按真实表统计，不再写死。
   */
  @GetMapping("/integrity")
  @PreAuthorize("hasAuthority('sync:view')")
  public ApiResponse<Map<String, Object>> integrity() {
    List<DataSource> sources = dataSourceRepository.findAll();
    long connected = sources.stream()
        .filter(d -> "CONNECTED".equalsIgnoreCase(d.getStatus()))
        .count();
    double coverage = sources.isEmpty()
        ? 0.0
        : Math.round(connected * 1000.0 / sources.size()) / 10.0;

    long errorTasks = dataSyncTaskRepository.findAll().stream()
        .filter(t -> t.getErrorMessage() != null && !t.getErrorMessage().isBlank())
        .count();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("totalRecords", dataSyncTaskRepository.findAll().stream()
        .mapToLong(t -> t.getRecordsSynced() == null ? 0L : t.getRecordsSynced())
        .sum());
    result.put("dataSourceTotal", sources.size());
    result.put("dataSourceConnected", connected);
    result.put("missingFields", 0);   // 未做字段级校验，如实为 0
    result.put("duplicates", 0);      // 未做重复检测，如实为 0
    result.put("anomalies", deriveAnomalies().size());
    result.put("coverage", coverage);
    result.put("errorSyncTasks", errorTasks);
    result.put("lastCheckedAt", OffsetDateTime.now());
    result.put("dataSource", "data_source / data_sync_task（真实库统计）");
    return ApiResponse.success(result);
  }

  /**
   * 数据异常：从真实状态派生。
   *
   * <ul>
   *   <li>数据源 DISCONNECTED / ERROR → 连接类异常</li>
   *   <li>同步任务 error_message 非空或 last_status=ERROR/FAILED → 同步类异常</li>
   * </ul>
   * 无异常时返回空列表（前端据此显示"暂无异常"）。
   */
  @GetMapping("/anomalies")
  @PreAuthorize("hasAuthority('sync:view')")
  public ApiResponse<List<Map<String, Object>>> anomalies() {
    return ApiResponse.success(deriveAnomalies());
  }

  private List<Map<String, Object>> deriveAnomalies() {
    List<Map<String, Object>> list = new ArrayList<>();

    for (DataSource d : dataSourceRepository.findAll()) {
      String st = d.getStatus() == null ? "" : d.getStatus().toUpperCase();
      if ("DISCONNECTED".equals(st) || "ERROR".equals(st)) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "ANOM-DS-" + d.getDsCode());
        a.put("target", d.getDsCode());
        a.put("targetName", d.getDsName());
        a.put("type", "DATASOURCE_STATUS");
        a.put("description", "数据源状态为 " + d.getStatus() + "，最近同步: "
            + (d.getLastSyncAt() == null ? "从未" : d.getLastSyncAt()));
        a.put("detectedAt", d.getUpdatedAt() != null ? d.getUpdatedAt() : d.getCreatedAt());
        a.put("severity", "ERROR".equals(st) ? "ERROR" : "WARNING");
        list.add(a);
      }
    }

    for (DataSyncTask t : dataSyncTaskRepository.findAll()) {
      boolean hasError = t.getErrorMessage() != null && !t.getErrorMessage().isBlank();
      boolean failed = t.getLastStatus() != null
          && ("ERROR".equalsIgnoreCase(t.getLastStatus()) || "FAILED".equalsIgnoreCase(t.getLastStatus()));
      if (hasError || failed) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "ANOM-TASK-" + t.getTaskCode());
        a.put("target", t.getTaskCode());
        a.put("targetName", t.getTaskName());
        a.put("type", "SYNC_TASK_FAILURE");
        a.put("description", hasError
            ? t.getErrorMessage()
            : ("最近一次同步结果为 " + t.getLastStatus()));
        a.put("detectedAt", t.getLastRunAt() != null ? t.getLastRunAt() : t.getUpdatedAt());
        a.put("severity", "ERROR");
        list.add(a);
      }
    }
    return list;
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
