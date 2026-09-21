package cn.disasterguard.governance;

import cn.disasterguard.common.api.ApiResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

  // ── Mock data source registry ───────────────────────────────────────────────
  private static final Map<String, Map<String, Object>> DATASOURCES = new LinkedHashMap<>();

  static {
    addDs("USGS_EARTHQUAKE", "USGS 地震目录", "SEISMIC", true, "CONNECTED",
        LocalDateTime.now().minusMinutes(12), 48_231, null);
    addDs("CENC_SEISMIC", "中国地震台网", "SEISMIC", true, "CONNECTED",
        LocalDateTime.now().minusMinutes(5), 31_007, null);
    addDs("WEATHER_GovCN", "国家气象信息中心", "WEATHER", true, "CONNECTED",
        LocalDateTime.now().minusMinutes(30), 892_104, null);
    addDs("GPS_CORS", "GPS 连续运行站网", "GPS", false, "DISABLED",
        LocalDateTime.now().minusDays(2), 12_450, null);
    addDs("RAINFALL_HYET", "降水监测", "RAINFALL", true, "CONNECTED",
        LocalDateTime.now().minusMinutes(15), 5_678_320, null);
  }

  private static void addDs(String code, String name, String type, boolean enabled,
                             String status, LocalDateTime lastSync, long count, String error) {
    Map<String, Object> ds = new LinkedHashMap<>();
    ds.put("code", code);
    ds.put("name", name);
    ds.put("type", type);
    ds.put("enabled", enabled);
    ds.put("status", status);
    ds.put("lastSyncAt", lastSync);
    ds.put("recordCount", count);
    ds.put("errorMessage", error);
    DATASOURCES.put(code, ds);
  }

  @GetMapping
  @PreAuthorize("hasAuthority('datasource:view')")
  public ApiResponse<List<Map<String, Object>>> list() {
    return ApiResponse.success(new ArrayList<>(DATASOURCES.values()));
  }

  @PostMapping("/{code}/toggle")
  @PreAuthorize("hasAuthority('datasource:view')")
  public ApiResponse<Map<String, Object>> toggle(@PathVariable String code, @RequestBody Map<String, Object> body) {
    Map<String, Object> ds = DATASOURCES.get(code);
    if (ds == null) {
      return ApiResponse.fail(404, "Data source not found: " + code);
    }
    Object enabledObj = body == null ? null : body.get("enabled");
    boolean enabled = Boolean.TRUE.equals(enabledObj);
    ds.put("enabled", enabled);
    ds.put("status", enabled ? "CONNECTED" : "DISABLED");
    ds.put("lastSyncAt", LocalDateTime.now());
    return ApiResponse.success(enabled ? "数据源已启用" : "数据源已禁用", ds);
  }

  @PostMapping("/{code}/test")
  @PreAuthorize("hasAuthority('datasource:view')")
  public ApiResponse<Map<String, Object>> test(@PathVariable String code) {
    Map<String, Object> ds = DATASOURCES.get(code);
    if (ds == null) {
      return ApiResponse.fail(404, "Data source not found: " + code);
    }
    // Simulate connection test: random latency 20-150ms
    long latency = 20 + (long) (Math.random() * 130);
    boolean success = latency < 120;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("success", success);
    result.put("latencyMs", latency);
    result.put("message", success ? "连接正常" : "连接超时");
    return ApiResponse.success(result);
  }
}
