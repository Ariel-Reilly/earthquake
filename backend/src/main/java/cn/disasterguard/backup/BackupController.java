package cn.disasterguard.backup;

import cn.disasterguard.backup.entity.BackupRecord;
import cn.disasterguard.backup.entity.BackupStrategy;
import cn.disasterguard.backup.entity.RestoreRecord;
import cn.disasterguard.backup.repository.BackupRecordRepository;
import cn.disasterguard.backup.repository.BackupStrategyRepository;
import cn.disasterguard.backup.repository.RestoreRecordRepository;
import cn.disasterguard.common.api.ApiResponse;
import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 *
 * <p>M6 技术债改造：原实现用 3 个 ConcurrentHashMap 保存业务数据，重启即丢；
 * 现统一落库为 {@code {id, payload JSONB, created_at}} 实体表
 * （backup_record / restore_record / backup_strategy），端点路径、请求/响应
 * 结构与权限控制保持不变。
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

  private final BackupRecordRepository backupRecordRepository;
  private final RestoreRecordRepository restoreRecordRepository;
  private final BackupStrategyRepository backupStrategyRepository;

  private AtomicLong backupIdGen;
  private AtomicLong restoreIdGen;
  private AtomicLong strategyIdGen;

  public BackupController(BackupRecordRepository backupRecordRepository,
                          RestoreRecordRepository restoreRecordRepository,
                          BackupStrategyRepository backupStrategyRepository) {
    this.backupRecordRepository = backupRecordRepository;
    this.restoreRecordRepository = restoreRecordRepository;
    this.backupStrategyRepository = backupStrategyRepository;
  }

  @PostConstruct
  public void init() {
    // 计数器从库内历史最大 id 之后起步（首次启动 max=0，首个 id 为 1），
    // 保证应用重启后新分配的 id 不与已有数据冲突。
    backupIdGen = new AtomicLong(backupRecordRepository.findMaxId());
    restoreIdGen = new AtomicLong(restoreRecordRepository.findMaxId());
    strategyIdGen = new AtomicLong(backupStrategyRepository.findMaxId());
    initSampleData();
  }

  private void initSampleData() {
    if (backupRecordRepository.count() == 0) {
      String[] types = {"FULL", "INCREMENTAL", "FULL", "INCREMENTAL"};
      for (int i = 0; i < 4; i++) {
        long id = backupIdGen.incrementAndGet();
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("id", id);
        backup.put("filename", "backup_" + (i + 1) + "_" + OffsetDateTime.now().minusDays(i + 1).toLocalDate() + ".sql");
        backup.put("size", (i + 1) * 1024 * 1024 * 10L);
        backup.put("createdAt", OffsetDateTime.now().minusDays(i + 1).toString());
        backup.put("createdBy", "system");
        backup.put("status", "COMPLETED");
        backup.put("type", types[i]);
        BackupRecord entity = new BackupRecord();
        entity.setId(id);
        entity.setPayload(backup);
        backupRecordRepository.save(entity);
      }
    }

    if (restoreRecordRepository.count() == 0) {
      for (int i = 0; i < 2; i++) {
        long id = restoreIdGen.incrementAndGet();
        Map<String, Object> restore = new LinkedHashMap<>();
        restore.put("id", id);
        restore.put("backupId", (long) (i + 1001));
        restore.put("status", i == 0 ? "COMPLETED" : "IN_PROGRESS");
        restore.put("startedAt", OffsetDateTime.now().minusHours(i + 1).toString());
        restore.put("finishedAt", i == 0 ? OffsetDateTime.now().minusHours(i).toString() : null);
        restore.put("restoredRecords", i == 0 ? 1000L : null);
        RestoreRecord entity = new RestoreRecord();
        entity.setId(id);
        entity.setPayload(restore);
        restoreRecordRepository.save(entity);
      }
    }

    if (backupStrategyRepository.count() == 0) {
      String[] strategyTypes = {"每日全量", "每日增量", "每周全量"};
      for (int i = 0; i < 3; i++) {
        long id = strategyIdGen.incrementAndGet();
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("id", id);
        strategy.put("name", "备份策略 " + (i + 1));
        strategy.put("type", strategyTypes[i]);
        strategy.put("schedule", i == 0 ? "0 2 * * *" : (i == 1 ? "0 3 * * *" : "0 4 * * 0"));
        strategy.put("retention", i == 0 ? 7 : (i == 1 ? 30 : 90));
        strategy.put("enabled", i < 2);
        strategy.put("lastRunAt", OffsetDateTime.now().minusDays(i + 1).toString());
        BackupStrategy entity = new BackupStrategy();
        entity.setId(id);
        entity.setPayload(strategy);
        backupStrategyRepository.save(entity);
      }
    }
  }

  @GetMapping("/records")
  @PreAuthorize("hasAuthority('backup:view')")
  public ApiResponse<Map<String, Object>> listRecords(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status) {
    var filtered = backupRecordRepository.findAll().stream()
        .sorted(Comparator.comparing(BackupRecord::getId))
        .map(BackupRecord::getPayload)
        .filter(b -> status == null || status.isBlank()
            || status.equals(b.get("status")))
        .sorted((a, b) -> String.valueOf(b.get("createdAt"))
            .compareTo(String.valueOf(a.get("createdAt"))))
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
    long id = backupIdGen.incrementAndGet();
    String type = String.valueOf(body.getOrDefault("type", "FULL"));
    Map<String, Object> backup = new LinkedHashMap<>();
    backup.put("id", id);
    backup.put("filename", "backup_" + id + "_" + java.time.LocalDate.now() + ".sql");
    backup.put("size", 0L);
    backup.put("createdAt", OffsetDateTime.now().toString());
    backup.put("createdBy", "current_user");
    backup.put("status", "IN_PROGRESS");
    backup.put("type", type);
    backup.put("description", body.getOrDefault("description", ""));
    BackupRecord entity = new BackupRecord();
    entity.setId(id);
    entity.setPayload(backup);
    backupRecordRepository.save(entity);
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
    BackupRecord record = backupRecordRepository.findById(id).orElse(null);
    if (record == null) {
      return ApiResponse.fail(404, "备份记录不存在: " + id);
    }
    Map<String, Object> backup = record.getPayload();
    if (!"COMPLETED".equals(backup.get("status"))) {
      return ApiResponse.fail(400, "只能恢复已完成的备份");
    }
    long restoreId = restoreIdGen.incrementAndGet();
    Map<String, Object> restore = new LinkedHashMap<>();
    restore.put("restoreId", restoreId);
    restore.put("status", "IN_PROGRESS");
    restore.put("startedAt", OffsetDateTime.now().toString());
    restore.put("targetDb", body.getOrDefault("targetDb", "default"));
    RestoreRecord entity = new RestoreRecord();
    entity.setId(restoreId);
    entity.setPayload(restore);
    restoreRecordRepository.save(entity);
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
   * 点击直接 404。这里补上映射，但<b>不返回"校验通过"</b>：备份记录虽已落库，
   * 但系统中没有真实的备份文件可供校验（记录本身仍是模拟数据）。在灾难恢复
   * 场景下返回一个假的"通过"比直接报错更危险，因此明确返回 501；
   * 待接入真实备份产物后再实现校验（文件存在性 + size + checksum）。
   */
  @PostMapping("/records/{id}/verify")
  @PreAuthorize("hasAuthority('backup:view')")
  public ApiResponse<Map<String, Object>> verifyBackup(@PathVariable Long id) {
    if (backupRecordRepository.findById(id).isEmpty()) {
      return ApiResponse.fail(404, "备份记录不存在: " + id);
    }
    return ApiResponse.fail(501,
        "备份校验尚未实现：当前备份记录为内存模拟数据，未落库，无法执行真实完整性校验");
  }

  @DeleteMapping("/records/{id}")
  @PreAuthorize("hasAuthority('backup:manage')")
  public ApiResponse<Map<String, Object>> deleteBackup(@PathVariable Long id) {
    if (backupRecordRepository.findById(id).isEmpty()) {
      return ApiResponse.fail(404, "备份记录不存在: " + id);
    }
    backupRecordRepository.deleteById(id);
    return ApiResponse.success(Map.of("deleted", id));
  }

  @GetMapping("/restores")
  @PreAuthorize("hasAuthority('backup:view')")
  public ApiResponse<Map<String, Object>> listRestores() {
    var all = restoreRecordRepository.findAll().stream()
        .sorted(Comparator.comparing(RestoreRecord::getId))
        .map(RestoreRecord::getPayload)
        .sorted((a, b) -> String.valueOf(b.get("startedAt"))
            .compareTo(String.valueOf(a.get("startedAt"))))
        .toList();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", all);
    result.put("total", all.size());
    return ApiResponse.success(result);
  }

  @GetMapping("/strategies")
  @PreAuthorize("hasAuthority('backup:view')")
  public ApiResponse<List<Map<String, Object>>> listStrategies() {
    List<Map<String, Object>> items = new ArrayList<>();
    backupStrategyRepository.findAll().stream()
        .sorted(Comparator.comparing(BackupStrategy::getId))
        .map(BackupStrategy::getPayload)
        .forEach(items::add);
    return ApiResponse.success(items);
  }

  @PostMapping("/strategies")
  @PreAuthorize("hasAuthority('backup:manage')")
  public ApiResponse<Map<String, Object>> saveStrategy(@RequestBody Map<String, Object> body) {
    Long id = body.containsKey("id") ? ((Number) body.get("id")).longValue() : null;
    BackupStrategy entity;
    Map<String, Object> strategy;
    if (id != null && backupStrategyRepository.existsById(id)) {
      entity = backupStrategyRepository.findById(id).orElseThrow();
      strategy = entity.getPayload();
    } else {
      id = strategyIdGen.incrementAndGet();
      strategy = new LinkedHashMap<>();
      strategy.put("id", id);
      entity = new BackupStrategy();
      entity.setId(id);
      entity.setPayload(strategy);
    }
    if (body.containsKey("name")) strategy.put("name", body.get("name"));
    if (body.containsKey("type")) strategy.put("type", body.get("type"));
    if (body.containsKey("schedule")) strategy.put("schedule", body.get("schedule"));
    if (body.containsKey("retention")) strategy.put("retention", body.get("retention"));
    if (body.containsKey("enabled")) strategy.put("enabled", body.get("enabled"));
    backupStrategyRepository.save(entity);
    return ApiResponse.success(strategy);
  }

  @DeleteMapping("/strategies/{id}")
  @PreAuthorize("hasAuthority('backup:manage')")
  public ApiResponse<Map<String, Object>> deleteStrategy(@PathVariable Long id) {
    if (backupStrategyRepository.findById(id).isEmpty()) {
      return ApiResponse.fail(404, "策略不存在: " + id);
    }
    backupStrategyRepository.deleteById(id);
    return ApiResponse.success(Map.of("deleted", id));
  }

  @PostMapping("/strategies/{id}/run")
  @PreAuthorize("hasAuthority('backup:manage')")
  public ApiResponse<Map<String, Object>> runStrategy(@PathVariable Long id) {
    BackupStrategy entity = backupStrategyRepository.findById(id).orElse(null);
    if (entity == null) {
      return ApiResponse.fail(404, "策略不存在: " + id);
    }
    entity.getPayload().put("lastRunAt", OffsetDateTime.now().toString());
    backupStrategyRepository.save(entity);
    return ApiResponse.success(Map.of(
        "strategyId", id,
        "status", "RUNNING",
        "startedAt", OffsetDateTime.now().toString()
    ));
  }
}
