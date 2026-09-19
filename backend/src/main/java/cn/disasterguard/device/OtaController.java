package cn.disasterguard.device;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ota")
public class OtaController {

  // ── Mock firmware store ─────────────────────────────────────────────────────
  private static final Map<String, Map<String, Object>> FIRMWARES = new ConcurrentHashMap<>();
  private static long fwSeq = 1;

  static {
    Map<String, Object> fw1 = new HashMap<>();
    fw1.put("firmwareId", "FW-001");
    fw1.put("version", "1.2.3");
    fw1.put("deviceType", "SEISMOMETER");
    fw1.put("size", 4194304);
    fw1.put("uploadedAt", LocalDateTime.now().minusDays(5));
    fw1.put("uploadedBy", "admin");
    fw1.put("status", "ACTIVE");
    FIRMWARES.put("FW-001", fw1);

    Map<String, Object> fw2 = new HashMap<>();
    fw2.put("firmwareId", "FW-002");
    fw2.put("version", "2.0.0");
    fw2.put("deviceType", "WEATHER_STATION");
    fw2.put("size", 2097152);
    fw2.put("uploadedAt", LocalDateTime.now().minusDays(2));
    fw2.put("uploadedBy", "admin");
    fw2.put("status", "ACTIVE");
    FIRMWARES.put("FW-002", fw2);

    Map<String, Object> fw3 = new HashMap<>();
    fw3.put("firmwareId", "FW-003");
    fw3.put("version", "1.0.5");
    fw3.put("deviceType", "GPS_RECEIVER");
    fw3.put("size", 3145728);
    fw3.put("uploadedAt", LocalDateTime.now().minusDays(10));
    fw3.put("uploadedBy", "operator");
    fw3.put("status", "DEPRECATED");
    FIRMWARES.put("FW-003", fw3);
  }

  // ── Mock OTA task store ─────────────────────────────────────────────────────
  private static final Map<String, Map<String, Object>> TASKS = new ConcurrentHashMap<>();
  private static long taskSeq = 1;

  static {
    Map<String, Object> t1 = new HashMap<>();
    t1.put("taskId", "TASK-001");
    t1.put("firmwareId", "FW-001");
    t1.put("deviceId", "DEV-001");
    t1.put("status", "COMPLETED");
    t1.put("progress", 100);
    t1.put("startedAt", LocalDateTime.now().minusDays(3));
    t1.put("finishedAt", LocalDateTime.now().minusDays(3).plusMinutes(5));
    TASKS.put("TASK-001", t1);

    Map<String, Object> t2 = new HashMap<>();
    t2.put("taskId", "TASK-002");
    t2.put("firmwareId", "FW-002");
    t2.put("deviceId", "DEV-002");
    t2.put("status", "IN_PROGRESS");
    t2.put("progress", 65);
    t2.put("startedAt", LocalDateTime.now().minusHours(2));
    t2.put("finishedAt", null);
    TASKS.put("TASK-002", t2);
  }

  // ── Firmware endpoints ─────────────────────────────────────────────────────

  @GetMapping("/firmwares")
  public ApiResponse<PageResponse<Map<String, Object>>> listFirmwares(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String deviceType,
      @RequestParam(required = false) String keyword
  ) {
    List<Map<String, Object>> all = new ArrayList<>(FIRMWARES.values());
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
  public ApiResponse<Map<String, Object>> uploadFirmware(
      @RequestParam("file") MultipartFile file,
      @RequestParam("version") String version,
      @RequestParam("deviceType") String deviceType
  ) {
    String id = "FW-" + String.format("%03d", fwSeq++);
    Map<String, Object> fw = new HashMap<>();
    fw.put("firmwareId", id);
    fw.put("version", version);
    fw.put("deviceType", deviceType);
    fw.put("size", file.getSize());
    fw.put("uploadedAt", LocalDateTime.now());
    fw.put("uploadedBy", "admin");
    fw.put("status", "ACTIVE");
    FIRMWARES.put(id, fw);
    return ApiResponse.success("固件上传成功", fw);
  }

  @DeleteMapping("/firmwares/{firmwareId}")
  public ApiResponse<Void> deleteFirmware(@PathVariable String firmwareId) {
    FIRMWARES.remove(firmwareId);
    return ApiResponse.success("删除成功", null);
  }

  // ── OTA task endpoints ─────────────────────────────────────────────────────

  @GetMapping("/tasks")
  public ApiResponse<PageResponse<Map<String, Object>>> listTasks(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String deviceId
  ) {
    List<Map<String, Object>> all = new ArrayList<>(TASKS.values());
    if (status != null && !status.isBlank()) {
      all.removeIf(t -> !t.get("status").equals(status));
    }
    if (deviceId != null && !deviceId.isBlank()) {
      all.removeIf(t -> !t.get("deviceId").equals(deviceId));
    }
    return ApiResponse.success(paginate(all, page, size));
  }

  @PostMapping("/tasks")
  public ApiResponse<Map<String, Object>> createTask(@RequestBody Map<String, Object> body) {
    String firmwareId = (String) body.get("firmwareId");
    @SuppressWarnings("unchecked")
    List<String> deviceIds = (List<String>) body.get("deviceIds");
    String taskId = "TASK-" + String.format("%03d", taskSeq++);
    Map<String, Object> task = new HashMap<>();
    task.put("taskId", taskId);
    task.put("firmwareId", firmwareId);
    task.put("deviceIds", deviceIds != null ? deviceIds : List.of());
    task.put("status", "PENDING");
    task.put("progress", 0);
    task.put("startedAt", null);
    task.put("finishedAt", null);
    task.put("createdAt", LocalDateTime.now());
    TASKS.put(taskId, task);
    return ApiResponse.success(Map.of("taskId", taskId, "status", "PENDING", "createdAt", LocalDateTime.now()));
  }

  @GetMapping("/tasks/{taskId}")
  public ApiResponse<Map<String, Object>> taskDetail(@PathVariable String taskId) {
    Map<String, Object> task = TASKS.get(taskId);
    if (task == null) {
      return ApiResponse.fail(404, "Task not found");
    }
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
  public ApiResponse<Map<String, Object>> cancelTask(@PathVariable String taskId) {
    Map<String, Object> task = TASKS.get(taskId);
    if (task == null) {
      return ApiResponse.fail(404, "Task not found");
    }
    task.put("status", "CANCELLED");
    task.put("finishedAt", LocalDateTime.now());
    return ApiResponse.success("任务已取消", Map.of("taskId", taskId, "status", "CANCELLED"));
  }

  @PostMapping("/tasks/{taskId}/retry")
  public ApiResponse<Map<String, Object>> retryTask(@PathVariable String taskId) {
    Map<String, Object> task = TASKS.get(taskId);
    if (task == null) {
      return ApiResponse.fail(404, "Task not found");
    }
    task.put("status", "PENDING");
    task.put("progress", 0);
    task.put("startedAt", null);
    task.put("finishedAt", null);
    return ApiResponse.success("任务已重置", Map.of("taskId", taskId, "status", "PENDING"));
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private static <T> PageResponse<T> paginate(List<T> list, int page, int size) {
    int total = list.size();
    int pages = (int) Math.ceil((double) total / size);
    int from = (page - 1) * size;
    int to = Math.min(from + size, total);
    List<T> records = from < total ? list.subList(from, to) : List.of();
    return new PageResponse<>(records, total, page, size, pages);
  }
}
