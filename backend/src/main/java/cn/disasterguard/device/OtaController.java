package cn.disasterguard.device;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.device.entity.Firmware;
import cn.disasterguard.device.entity.OtaTask;
import cn.disasterguard.device.repository.FirmwareRepository;
import cn.disasterguard.device.repository.OtaTaskRepository;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/ota")
public class OtaController {

  private final FirmwareRepository firmwareRepository;
  private final OtaTaskRepository otaTaskRepository;

  public OtaController(FirmwareRepository firmwareRepository, OtaTaskRepository otaTaskRepository) {
    this.firmwareRepository = firmwareRepository;
    this.otaTaskRepository = otaTaskRepository;
  }

  /** 种子数据（幂等：仅当表为空时写入；payload 内时间为 ISO 字符串）。 */
  @PostConstruct
  void seed() {
    if (firmwareRepository.count() == 0) {
      firmwareRepository.save(buildFirmware("FW-001", "1.2.3", "SEISMOMETER", 4194304L,
          LocalDateTime.now().minusDays(5).toString(), "admin", "ACTIVE"));
      firmwareRepository.save(buildFirmware("FW-002", "2.0.0", "WEATHER_STATION", 2097152L,
          LocalDateTime.now().minusDays(2).toString(), "admin", "ACTIVE"));
      firmwareRepository.save(buildFirmware("FW-003", "1.0.5", "GPS_RECEIVER", 3145728L,
          LocalDateTime.now().minusDays(10).toString(), "operator", "DEPRECATED"));
    }
    if (otaTaskRepository.count() == 0) {
      otaTaskRepository.save(buildOtaTask("TASK-001", "FW-001", "DEV-001", "COMPLETED", 100,
          LocalDateTime.now().minusDays(3).toString(),
          LocalDateTime.now().minusDays(3).plusMinutes(5).toString()));
      otaTaskRepository.save(buildOtaTask("TASK-002", "FW-002", "DEV-002", "IN_PROGRESS", 65,
          LocalDateTime.now().minusHours(2).toString(), null));
    }
  }

  private Firmware buildFirmware(String id, String version, String deviceType, long size,
                                 String uploadedAt, String uploadedBy, String status) {
    Map<String, Object> fw = new HashMap<>();
    fw.put("firmwareId", id);
    fw.put("version", version);
    fw.put("deviceType", deviceType);
    fw.put("size", size);
    fw.put("uploadedAt", uploadedAt);
    fw.put("uploadedBy", uploadedBy);
    fw.put("status", status);
    Firmware entity = new Firmware();
    entity.setId(id);
    entity.setPayload(fw);
    return entity;
  }

  private OtaTask buildOtaTask(String id, String firmwareId, String deviceId, String status,
                               int progress, String startedAt, String finishedAt) {
    Map<String, Object> task = new HashMap<>();
    task.put("taskId", id);
    task.put("firmwareId", firmwareId);
    task.put("deviceId", deviceId);
    task.put("status", status);
    task.put("progress", progress);
    task.put("startedAt", startedAt);
    task.put("finishedAt", finishedAt);
    OtaTask entity = new OtaTask();
    entity.setId(id);
    entity.setPayload(task);
    return entity;
  }

  // ── Firmware endpoints ─────────────────────────────────────────────────────

  @GetMapping("/firmwares")
  @PreAuthorize("hasAuthority('devices:view')")
  public ApiResponse<PageResponse<Map<String, Object>>> listFirmwares(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String deviceType,
      @RequestParam(required = false) String keyword
  ) {
    List<Map<String, Object>> all = firmwareRepository.findAll().stream()
        .sorted(Comparator.comparing(Firmware::getId))
        .map(Firmware::getPayload)
        .collect(Collectors.toCollection(ArrayList::new));
    if (deviceType != null && !deviceType.isBlank()) {
      all.removeIf(fw -> !fw.get("deviceType").equals(deviceType));
    }
    if (keyword != null && !keyword.isBlank()) {
      String kw = keyword.toLowerCase();
      all.removeIf(fw -> {
        String ver = String.valueOf(fw.get("version")).toLowerCase();
        String id = String.valueOf(fw.get("firmwareId")).toLowerCase();
        String type = String.valueOf(fw.get("deviceType")).toLowerCase();
        return !ver.contains(kw) && !id.contains(kw) && !type.contains(kw);
      });
    }
    return ApiResponse.success(paginate(all, page, size));
  }

  @PostMapping("/firmwares")
  @PreAuthorize("hasAuthority('devices:edit')")
  public ApiResponse<Map<String, Object>> uploadFirmware(
      @RequestParam("file") MultipartFile file,
      @RequestParam("version") String version,
      @RequestParam("deviceType") String deviceType
  ) {
    String id = nextFirmwareId();
    Map<String, Object> fw = new HashMap<>();
    fw.put("firmwareId", id);
    fw.put("version", version);
    fw.put("deviceType", deviceType);
    fw.put("size", file.getSize());
    fw.put("uploadedAt", LocalDateTime.now().toString());
    fw.put("uploadedBy", "admin");
    fw.put("status", "ACTIVE");
    Firmware entity = new Firmware();
    entity.setId(id);
    entity.setPayload(fw);
    firmwareRepository.save(entity);
    return ApiResponse.success("固件上传成功", fw);
  }

  @DeleteMapping("/firmwares/{firmwareId}")
  @PreAuthorize("hasAuthority('devices:delete')")
  public ApiResponse<Void> deleteFirmware(@PathVariable String firmwareId) {
    firmwareRepository.deleteById(firmwareId);
    return ApiResponse.success("删除成功", null);
  }

  // ── OTA task endpoints ─────────────────────────────────────────────────────

  @GetMapping("/tasks")
  @PreAuthorize("hasAuthority('devices:view')")
  public ApiResponse<PageResponse<Map<String, Object>>> listTasks(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String deviceId
  ) {
    List<Map<String, Object>> all = otaTaskRepository.findAll().stream()
        .sorted(Comparator.comparing(OtaTask::getId))
        .map(OtaTask::getPayload)
        .collect(Collectors.toCollection(ArrayList::new));
    if (status != null && !status.isBlank()) {
      all.removeIf(t -> !t.get("status").equals(status));
    }
    if (deviceId != null && !deviceId.isBlank()) {
      all.removeIf(t -> !t.get("deviceId").equals(deviceId));
    }
    return ApiResponse.success(paginate(all, page, size));
  }

  @PostMapping("/tasks")
  @PreAuthorize("hasAuthority('devices:edit')")
  public ApiResponse<Map<String, Object>> createTask(@RequestBody Map<String, Object> body) {
    String firmwareId = (String) body.get("firmwareId");
    @SuppressWarnings("unchecked")
    List<String> deviceIds = (List<String>) body.get("deviceIds");
    String taskId = nextTaskId();
    Map<String, Object> task = new HashMap<>();
    task.put("taskId", taskId);
    task.put("firmwareId", firmwareId);
    task.put("deviceIds", deviceIds != null ? deviceIds : List.of());
    task.put("status", "PENDING");
    task.put("progress", 0);
    task.put("startedAt", null);
    task.put("finishedAt", null);
    task.put("createdAt", LocalDateTime.now().toString());
    OtaTask entity = new OtaTask();
    entity.setId(taskId);
    entity.setPayload(task);
    otaTaskRepository.save(entity);
    return ApiResponse.success(Map.of("taskId", taskId, "status", "PENDING", "createdAt", LocalDateTime.now()));
  }

  @GetMapping("/tasks/{taskId}")
  @PreAuthorize("hasAuthority('devices:view')")
  public ApiResponse<Map<String, Object>> taskDetail(@PathVariable String taskId) {
    OtaTask entity = otaTaskRepository.findById(taskId).orElse(null);
    if (entity == null) {
      return ApiResponse.fail(404, "Task not found");
    }
    Map<String, Object> task = entity.getPayload();
    // Build timeline mock
    List<Map<String, Object>> timeline = new ArrayList<>();
    timeline.add(Map.of("event", "CREATED", "timestamp", task.get("createdAt")));
    if (task.get("startedAt") != null) {
      timeline.add(Map.of("event", "STARTED", "timestamp", task.get("startedAt")));
    }
    if ("COMPLETED".equals(task.get("status"))) {
      timeline.add(Map.of("event", "COMPLETED", "timestamp", task.get("finishedAt")));
    } else if ("IN_PROGRESS".equals(task.get("status"))) {
      timeline.add(Map.of("event", "DOWNLOADING", "timestamp", LocalDateTime.now().minusMinutes(10)));
      timeline.add(Map.of("event", "INSTALLING", "timestamp", LocalDateTime.now().minusMinutes(5)));
    }
    Map<String, Object> detail = new HashMap<>(task);
    detail.put("timeline", timeline);
    return ApiResponse.success(detail);
  }

  @PostMapping("/tasks/{taskId}/cancel")
  @PreAuthorize("hasAuthority('devices:edit')")
  public ApiResponse<Map<String, Object>> cancelTask(@PathVariable String taskId) {
    OtaTask entity = otaTaskRepository.findById(taskId).orElse(null);
    if (entity == null) {
      return ApiResponse.fail(404, "Task not found");
    }
    entity.getPayload().put("status", "CANCELLED");
    entity.getPayload().put("finishedAt", LocalDateTime.now().toString());
    otaTaskRepository.save(entity);
    return ApiResponse.success("任务已取消", Map.of("taskId", taskId, "status", "CANCELLED"));
  }

  @PostMapping("/tasks/{taskId}/retry")
  @PreAuthorize("hasAuthority('devices:edit')")
  public ApiResponse<Map<String, Object>> retryTask(@PathVariable String taskId) {
    OtaTask entity = otaTaskRepository.findById(taskId).orElse(null);
    if (entity == null) {
      return ApiResponse.fail(404, "Task not found");
    }
    entity.getPayload().put("status", "PENDING");
    entity.getPayload().put("progress", 0);
    entity.getPayload().put("startedAt", null);
    entity.getPayload().put("finishedAt", null);
    otaTaskRepository.save(entity);
    return ApiResponse.success("任务已重置", Map.of("taskId", taskId, "status", "PENDING"));
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  /** 基于 DB 中现有 id 的数字后缀生成下一个固件 id（替代原内存自增序列）。 */
  private String nextFirmwareId() {
    int max = firmwareRepository.findAll().stream()
        .map(Firmware::getId)
        .filter(id -> id != null && id.startsWith("FW-"))
        .mapToInt(id -> parseSuffix(id, "FW-"))
        .max()
        .orElse(0);
    return "FW-" + String.format("%03d", max + 1);
  }

  private String nextTaskId() {
    int max = otaTaskRepository.findAll().stream()
        .map(OtaTask::getId)
        .filter(id -> id != null && id.startsWith("TASK-"))
        .mapToInt(id -> parseSuffix(id, "TASK-"))
        .max()
        .orElse(0);
    return "TASK-" + String.format("%03d", max + 1);
  }

  private static int parseSuffix(String id, String prefix) {
    try {
      return Integer.parseInt(id.substring(prefix.length()));
    } catch (NumberFormatException e) {
      return 0;
    }
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
