package cn.disasterguard.governance;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

  private static final List<Map<String, Object>> MOCK_ASSETS = List.of(
      asset(1L, "USGS地震目录", "SEISMIC", "USGS全球地震目录", "JSON", "2.3GB", 2450000L, "2026-09-15T00:00:00Z", "system", List.of("地震", "USGS", "全球"), 95),
      asset(2L, "设备监测指标", "DEVICE", "IoT设备上报指标", "TDengine", "15.7GB", 15000000L, "2026-09-15T12:00:00Z", "system", List.of("IoT", "设备", "指标"), 98),
      asset(3L, "气象观测数据", "WEATHER", "气象站实时观测", "JSON", "8.2GB", 5200000L, "2026-09-15T06:00:00Z", "system", List.of("气象", "降水", "温度"), 92),
      asset(4L, "灾害点档案", "GIS", "隐患点矢量档案", "GeoJSON", "156MB", 450L, "2026-09-14T18:00:00Z", "admin", List.of("隐患", "矢量", "GIS"), 99)
  );

  private static Map<String, Object> asset(Long id, String name, String domain, String description, String format, String size, Long recordCount, String updatedAt, String owner, List<String> tags, int quality) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("id", id);
    m.put("name", name);
    m.put("domain", domain);
    m.put("description", description);
    m.put("format", format);
    m.put("size", size);
    m.put("recordCount", recordCount);
    m.put("updatedAt", updatedAt);
    m.put("owner", owner);
    m.put("tags", tags);
    m.put("quality", quality);
    return m;
  }

  @GetMapping("/summary")
  public ApiResponse<Map<String, Object>> summary() {
    Map<String, Object> result = Map.of(
        "totalAssets", 4L,
        "totalDomains", 4L,
        "totalDatasets", 12L,
        "lastUpdated", OffsetDateTime.now(ZoneOffset.UTC).toString()
    );
    return ApiResponse.success(result);
  }

  @GetMapping("/assets")
  public ApiResponse<PageResponse<Map<String, Object>>> listAssets(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String domain,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String tag
  ) {
    List<Map<String, Object>> filtered = MOCK_ASSETS.stream()
        .filter(a -> domain == null || domain.isBlank() || a.get("domain").equals(domain))
        .filter(a -> keyword == null || keyword.isBlank() ||
            ((String) a.get("name")).contains(keyword) ||
            ((String) a.get("description")).contains(keyword))
        .filter(a -> tag == null || tag.isBlank() ||
            ((List<String>) a.get("tags")).stream().anyMatch(t -> t.contains(tag)))
        .toList();

    int total = filtered.size();
    int pages = (int) Math.ceil((double) total / size);
    int fromIndex = (page - 1) * size;
    int toIndex = Math.min(fromIndex + size, total);

    List<Map<String, Object>> items = fromIndex < total
        ? filtered.subList(fromIndex, toIndex)
        : new ArrayList<>();

    PageResponse<Map<String, Object>> response = new PageResponse<>(items, total, page, size, pages);
    return ApiResponse.success(response);
  }

  @GetMapping("/assets/{id}")
  public ApiResponse<Map<String, Object>> getAsset(@PathVariable Long id) {
    Map<String, Object> asset = MOCK_ASSETS.stream()
        .filter(a -> a.get("id").equals(id))
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Asset not found: " + id));

    Map<String, Object> detail = new HashMap<>(asset);
    detail.put("schema", List.of(
        Map.of("field", "id", "type", "integer", "description", "唯一标识"),
        Map.of("field", "timestamp", "type", "datetime", "description", "记录时间"),
        Map.of("field", "latitude", "type", "double", "description", "纬度"),
        Map.of("field", "longitude", "type", "double", "description", "经度"),
        Map.of("field", "magnitude", "type", "double", "description", "震级")
    ));
    detail.put("lineage", List.of(
        Map.of("source", "USGS Feed", "type", "INGEST", "lastSync", "2026-09-15T00:00:00Z")
    ));
    detail.put("accessInfo", Map.of(
        "apiEndpoint", "/api/data/seismic",
        "documentation", "https://docs.geosentry.io/datasets/usgs",
        "license", "CC BY 4.0"
    ));

    return ApiResponse.success(detail);
  }

  @PutMapping("/assets/{id}/tags")
  public ApiResponse<Map<String, Object>> updateTags(
      @PathVariable Long id,
      @RequestBody Map<String, Object> request
  ) {
    @SuppressWarnings("unchecked")
    List<String> tags = (List<String>) request.get("tags");
    Map<String, Object> result = Map.of(
        "id", id,
        "tags", tags,
        "updatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString()
    );
    return ApiResponse.success("标签更新成功", result);
  }

  @GetMapping("/usage")
  public ApiResponse<List<Map<String, Object>>> usage() {
    List<Map<String, Object>> stats = List.of(
        Map.of(
            "domain", "SEISMIC",
            "assetCount", 3L,
            "recordCount", 5200000L,
            "lastAccess", "2026-09-15T14:30:00Z",
            "accessCount", 1520L
        ),
        Map.of(
            "domain", "DEVICE",
            "assetCount", 5L,
            "recordCount", 28000000L,
            "lastAccess", "2026-09-15T14:45:00Z",
            "accessCount", 3450L
        ),
        Map.of(
            "domain", "WEATHER",
            "assetCount", 4L,
            "recordCount", 8500000L,
            "lastAccess", "2026-09-15T14:20:00Z",
            "accessCount", 890L
        ),
        Map.of(
            "domain", "GIS",
            "assetCount", 2L,
            "recordCount", 1250L,
            "lastAccess", "2026-09-15T13:00:00Z",
            "accessCount", 420L
        )
    );
    return ApiResponse.success(stats);
  }

  @PostMapping("/assets/{id}/subscribe")
  public ApiResponse<Map<String, Object>> subscribe(
      @PathVariable Long id,
      @RequestBody Map<String, Object> request
  ) {
    String email = (String) request.get("email");
    String webhook = (String) request.get("webhook");
    String subscriptionId = UUID.randomUUID().toString();

    Map<String, Object> result = Map.of(
        "subscriptionId", subscriptionId,
        "assetId", id,
        "email", email != null ? email : "",
        "webhook", webhook != null ? webhook : "",
        "createdAt", OffsetDateTime.now(ZoneOffset.UTC).toString()
    );
    return ApiResponse.success("订阅成功", result);
  }
}
