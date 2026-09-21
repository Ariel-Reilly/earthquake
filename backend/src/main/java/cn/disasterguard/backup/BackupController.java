package cn.disasterguard.backup;

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
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 备份管理（Backup）REST API。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET /api/backup/records} — 备份记录列表</li>
 *   <li>{@code POST /api/backup/records} — 创建备份</li>
 *   <li>{@code POST /api/backup/records/{id}/restore} — 恢复备份</li>
 *   <li>{@code DELETE /api/backup/records/{id}} — 删除备份</li>
 *   <li>{@code GET /api/backup/restores} — 恢复历史</li>
 *   <li>{@code GET /api/backup/strategies} — 备份策略列表</li>
 *   <li>{@code POST /api/backup/strategies} — 创建/更新策略</li>
 *   <li>{@code DELETE /api/backup/strategies/{id}} — 删除策略</li>
 *   <li>{@code POST /api/backup/strategies/{id}/run} — 立即执行策略</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

  private static final AtomicLong BACKUP_ID_GEN = new AtomicLong(1000);
  private static final AtomicLong RESTORE_ID_GEN = new AtomicLong(1000);
  private static final AtomicLong STRATEGY_ID_GEN = new AtomicLong(1000);

  private final Map<Long, Map<String, Object>> backups = new ConcurrentHashMap<>();
  private final Map<Long, Map<String, Object>> restores = new ConcurrentHashMap<>();
  private final Map<Long, Map<String, Object>> strategies = new ConcurrentHashMap<>();

  public BackupController() {
    initSampleData();
  }

  private void initSampleData() {
    String[] types = {"FULL", "INCREMENTAL", "FULL", "INCREMENTAL"};
    for (int i = 0; i < 4; i++) {
      long id = BACKUP_ID_GEN.incrementAndGet();
      Map<String, Object> backup = new LinkedHashMap<>();
      backup.put("id", id);
      backup.put("filename", "backup_" + (i + 1) + "_" + OffsetDateTime.now().minusDays(i + 1).toLocalDate() + ".sql");
      backup.put("size", (i + 1) * 1024 * 1024 * 10L);
      backup.put("createdAt", OffsetDateTime.now().minusDays(i + 1));
      backup.put("createdBy", "system");
      backup.put("status", "COMPLETED");
      backup.put("type", types[i]);
      backups.put(id, backup);
    }

    for (int i = 0; i < 2; i++) {
      long id = RESTORE_ID_GEN.incrementAndGet();
      Map<String, Object> restore = new LinkedHashMap<>();
      restore.put("id", id);
      restore.put("backupId", (long) (i + 1001));
      restore.put("status", i == 0 ? "COMPLETED" : "IN_PROGRESS");
      restore.put("startedAt", OffsetDateTime.now().minusHours(i + 1));
      restore.put("finishedAt", i == 0 ? OffsetDateTime.now().minusHours(i) : null);
      restore.put("restoredRecords", i == 0 ? 1000L : null);
      restores.put(id, restore);
    }

    String[] strategyTypes = {"每日全量", "每日增量", "每周全量"};
    for (int i = 0; i < 3; i++) {
      long id = STRATEGY_ID_GEN.incrementAndGet();
      Map<String, Object> strategy = new LinkedHashMap<>();
      strategy.put("id", id);
      strategy.put("name", "备份策略 " + (i + 1));
      strategy.put("type", strategyTypes[i]);
      strategy.put("schedule", i == 0 ? "0 2 * * *" : (i == 1 ? "0 3 * * *" : "0 4 * * 0"));
      strategy.put("retention", i == 0 ? 7 : (i == 1 ? 30 : 90));
      strategy.put("enabled", i < 2);
      strategy.put("lastRunAt", OffsetDateTime.now().minusDays(i + 1));
      strategies.put(id, strategy);
    }
  }

  @GetMapping("/records")
  @PreAuthorize("hasAuthority('backup:view')")
  public ApiResponse<Map<String, Object>> listRecords(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status) {
    var filtered = backups.values().stream()
        .filter(b -> status == null || status.isBlank()
            || status.equals(b.get("status")))
        .sorted((a, b) -> {
          var ta = (OffsetDateTime) b.get("createdAt");
          var tb = (OffsetDateTime) a.get("createdAt");
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

  @PostMapping("/records")
  @PreAuthorize("hasAuthority('backup:manage')")
  public ApiResponse<Map<String, Object>> createBackup(@RequestBody Map<String, Object> body) {
    long id = BACKUP_ID_GEN.incrementAndGet();
    String type = String.valueOf(body.getOrDefault("type", "FULL"));
    Map<String, Object> backup = new LinkedHashMap<>();
    backup.put("id", id);
    backup.put("filename", "backup_" + id + "_" + java.time.LocalDate.now() + ".sql");
    backup.put("size", 0L);
    backup.put("createdAt", OffsetDateTime.now());
    backup.put("createdBy", "current_user");
    backup.put("status", "IN_PROGRESS");
    backup.put("type", type);
    backup.put("description", body.getOrDefault("description", ""));
    backups.put(id, backup);
    return ApiResponse.success(Map.of(
        "id", id,
        "filename", backup.get("filename"),
        "status", "IN_PROGRESS",
        "createdAt", backup.get("createdAt")
    ));
  }

  @PostMapping("/records/{id}/restore")
  @PreAuthorize("hasAuthority('backup:manage')")
  public ApiResponse<Map<String, Object>> restoreBackup(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
    Map<String, Object> backup = backups.get(id);
    if (backup == null) {
      return ApiResponse.fail(404, "备份记录不存在: " + id);
    }
    if (!"COMPLETED".equals(backup.get("status"))) {
      return ApiResponse.fail(400, "只能恢复已完成的备份");
    }
    long restoreId = RESTORE_ID_GEN.incrementAndGet();
    Map<String, Object> restore = new LinkedHashMap<>();
    restore.put("restoreId", restoreId);
    restore.put("status", "IN_PROGRESS");
    restore.put("startedAt", OffsetDateTime.now());
    restore.put("targetDb", body.getOrDefault("targetDb", "default"));
    restores.put(restoreId, restore);
    return ApiResponse.success(Map.of(
        "restoreId", restoreId,
        "status", "IN_PROGRESS",
        "startedAt", restore.get("startedAt")
    ));
  }

  /**
   * 校验备份完整性。
   *
   * <p>前端 BackupRecoveryPage 的「校验」按钮调用此接口，此前后端完全没有映射，
   * 点击直接 404。这里补上映射，但<b>不返回"校验通过"</b>：备份记录目前保存在
   * ConcurrentHashMap 中（即 CODE_REVIEW 中的 M6 内存 mock 问题），没有真实的
   * 备份文件可供校验。在灾难恢复场景下返回一个假的"通过"比直接报错更危险，
   * 因此明确返回 501；待备份落库后再实现真实校验（文件存在性 + size + checksum）。
   */
  @PostMapping("/records/{id}/verify")
  @PreAuthorize("hasAuthority('backup:view')")
  public ApiResponse<Map<String, Object>> verifyBackup(@PathVariable Long id) {
    if (!backups.containsKey(id)) {
      return ApiResponse.fail(404, "备份记录不存在: " + id);
    }
    return ApiResponse.fail(501,
        "备份校验尚未实现：当前备份记录为内存模拟数据，未落库，无法执行真实完整性校验");
  }

  @DeleteMapping("/records/{id}")
  @PreAuthorize("hasAuthority('backup:manage')")
  public ApiResponse<Map<String, Object>> deleteBackup(@PathVariable Long id) {
    if (!backups.containsKey(id)) {
      return ApiResponse.fail(404, "备份记录不存在: " + id);
    }
    backups.remove(id);
    return ApiResponse.success(Map.of("deleted", id));
  }

  @GetMapping("/restores")
  @PreAuthorize("hasAuthority('backup:view')")
  public ApiResponse<Map<String, Object>> listRestores() {
    var all = restores.values().stream()
        .sorted((a, b) -> {
          var ta = (OffsetDateTime) b.get("startedAt");
          var tb = (OffsetDateTime) a.get("startedAt");
          return ta.compareTo(tb);
        })
        .toList();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", all);
    result.put("total", all.size());
    return ApiResponse.success(result);
  }

  @GetMapping("/strategies")
  @PreAuthorize("hasAuthority('backup:view')")
  public ApiResponse<List<Map<String, Object>>> listStrategies() {
    return ApiResponse.success(new ArrayList<>(strategies.values()));
  }

  @PostMapping("/strategies")
  @PreAuthorize("hasAuthority('backup:manage')")
  public ApiResponse<Map<String, Object>> saveStrategy(@RequestBody Map<String, Object> body) {
    Long id = body.containsKey("id") ? ((Number) body.get("id")).longValue() : null;
    Map<String, Object> strategy;
    if (id != null && strategies.containsKey(id)) {
      strategy = strategies.get(id);
    } else {
      id = STRATEGY_ID_GEN.incrementAndGet();
      strategy = new LinkedHashMap<>();
      strategy.put("id", id);
      strategies.put(id, strategy);
    }
    if (body.containsKey("name")) strategy.put("name", body.get("name"));
    if (body.containsKey("type")) strategy.put("type", body.get("type"));
    if (body.containsKey("schedule")) strategy.put("schedule", body.get("schedule"));
    if (body.containsKey("retention")) strategy.put("retention", body.get("retention"));
    if (body.containsKey("enabled")) strategy.put("enabled", body.get("enabled"));
    return ApiResponse.success(strategy);
  }

  @DeleteMapping("/strategies/{id}")
  @PreAuthorize("hasAuthority('backup:manage')")
  public ApiResponse<Map<String, Object>> deleteStrategy(@PathVariable Long id) {
    if (!strategies.containsKey(id)) {
      return ApiResponse.fail(404, "策略不存在: " + id);
    }
    strategies.remove(id);
    return ApiResponse.success(Map.of("deleted", id));
  }

  @PostMapping("/strategies/{id}/run")
  @PreAuthorize("hasAuthority('backup:manage')")
  public ApiResponse<Map<String, Object>> runStrategy(@PathVariable Long id) {
    Map<String, Object> strategy = strategies.get(id);
    if (strategy == null) {
      return ApiResponse.fail(404, "策略不存在: " + id);
    }
    strategy.put("lastRunAt", OffsetDateTime.now());
    return ApiResponse.success(Map.of(
        "strategyId", id,
        "status", "RUNNING",
        "startedAt", OffsetDateTime.now()
    ));
  }
}
