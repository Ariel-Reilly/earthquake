package cn.disasterguard.governance;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据资产目录（从真实库表现场盘点）。
 *
 * <p>v1.8.0 重构：此前 {@code MOCK_ASSETS} 是写死的 4 条编造资产
 * （USGS地震目录 2.3GB / 设备监测指标 15.7GB 等，含虚构的 size / quality / updatedAt），
 * {@code /usage} 也是写死的 4 条领域统计，与库里实际有什么数据毫无关系。
 *
 * <p>现在资产目录 = <b>真实存在的业务库表</b>：每条资产对应一张真实表，
 * {@code recordCount} 走真实 {@code count(*)}，{@code size} 走
 * {@code pg_total_relation_size}，{@code updatedAt} 取该表最近更新时间。
 * 库里没有的表不会出现在目录里；行数为 0 的表如实显示 0。
 *
 * <p>仍保留的一个诚实边界：{@code /assets/{id}/access-requests} 返回 501 ——
 * 资产台账未落库 + 无审批流，无法真正受理申请。
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

  private static final Logger log = LoggerFactory.getLogger(CatalogController.class);

  /**
   * 纳入资产目录的真实业务表 → 资产元数据（领域 / 描述 / 格式 / 标签）。
   *
   * <p>只列业务表，剔除纯技术表（spatial_ref_sys / sys_* 权限表另算）。
   */
  private static final Map<String, AssetMeta> ASSET_TABLES = new LinkedHashMap<>();

  static {
    // 领域, 中文名, 描述, 格式, 标签
    ASSET_TABLES.put("device_metric", new AssetMeta("DEVICE", "设备监测指标", "物联网设备上报的时序监测指标", "TDengine/PostgreSQL", List.of("IoT", "设备", "指标")));
    ASSET_TABLES.put("device_trajectory", new AssetMeta("DEVICE", "设备轨迹", "设备 GPS 历史位置采样", "PostGIS", List.of("轨迹", "GPS", "回放")));
    ASSET_TABLES.put("monitoring_device", new AssetMeta("DEVICE", "监测设备台账", "监测设备基础档案", "PostgreSQL", List.of("设备", "台账")));
    ASSET_TABLES.put("unified_event", new AssetMeta("GIS", "统一隐患点", "隐患点 / 灾害点统一视图", "PostGIS", List.of("隐患", "矢量", "GIS")));
    ASSET_TABLES.put("region", new AssetMeta("GIS", "行政区划", "市 / 区县 / 街道行政区划", "PostGIS", List.of("区划", "地理")));
    ASSET_TABLES.put("warning_event", new AssetMeta("WARNING", "预警事件", "预警发布与流转记录", "PostgreSQL", List.of("预警", "事件")));
    ASSET_TABLES.put("warning_delivery_record", new AssetMeta("WARNING", "预警送达记录", "预警多渠道送达明细", "PostgreSQL", List.of("预警", "送达")));
    ASSET_TABLES.put("emergency_event", new AssetMeta("EMERGENCY", "应急事件", "应急处置事件记录", "PostgreSQL", List.of("应急", "事件")));
    ASSET_TABLES.put("emergency_plan", new AssetMeta("EMERGENCY", "应急预案", "应急处置预案库", "PostgreSQL", List.of("应急", "预案")));
    ASSET_TABLES.put("resource", new AssetMeta("EMERGENCY", "应急资源", "救援队 / 避难场所 / 物资库", "PostGIS", List.of("资源", "救援")));
    ASSET_TABLES.put("inspection_task", new AssetMeta("INSPECTION", "巡检任务", "现场巡检任务与执行状态", "PostgreSQL", List.of("巡检", "任务")));
    ASSET_TABLES.put("inspection_record", new AssetMeta("INSPECTION", "巡检记录", "现场巡检打卡与结果", "PostGIS", List.of("巡检", "记录")));
    ASSET_TABLES.put("ai_model", new AssetMeta("AI", "AI 模型", "AI 模型注册表", "PostgreSQL", List.of("AI", "模型")));
    ASSET_TABLES.put("ai_device_health", new AssetMeta("AI", "设备健康度", "AI 设备健康评估结果", "PostgreSQL", List.of("AI", "健康度")));
    ASSET_TABLES.put("ai_comprehensive_score", new AssetMeta("AI", "综合评分", "AI 综合风险评分", "PostgreSQL", List.of("AI", "评分")));
    ASSET_TABLES.put("risk_score_log", new AssetMeta("AI", "风险评分日志", "风险评分历史轨迹", "PostgreSQL", List.of("风险", "评分")));
    ASSET_TABLES.put("operation_log", new AssetMeta("AUDIT", "操作审计日志", "全量写操作审计留痕", "PostgreSQL", List.of("审计", "日志")));
    ASSET_TABLES.put("rule", new AssetMeta("RULE", "规则库", "预警 / 联动规则定义", "PostgreSQL", List.of("规则", "联动")));
    ASSET_TABLES.put("rule_execution_log", new AssetMeta("RULE", "规则执行日志", "规则触发与执行留痕", "PostgreSQL", List.of("规则", "执行")));
  }

  private final JdbcTemplate jdbcTemplate;
  private final CatalogService catalogService;

  @Autowired
  public CatalogController(@Autowired(required = false) DataSource dataSource,
                           CatalogService catalogService) {
    this.jdbcTemplate = dataSource == null ? null : new JdbcTemplate(dataSource);
    this.catalogService = catalogService;
  }

  /** 盘点真实库表，产出资产列表。库不可达时返回空列表（不伪造）。 */
  private List<Map<String, Object>> loadRealAssets() {
    List<Map<String, Object>> out = new ArrayList<>();
    if (jdbcTemplate == null) {
      // 实盘库不可用时仍返回用户已登记的资产（CatalogService 用同一 DataSource）
      out.addAll(catalogService.listRegistered());
      return out;
    }
    long fakeId = 0;
    for (Map.Entry<String, AssetMeta> e : ASSET_TABLES.entrySet()) {
      String table = e.getKey();
      AssetMeta meta = e.getValue();
      try {
        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + quote(table), Long.class);
        Long bytes = jdbcTemplate.queryForObject(
            "SELECT pg_total_relation_size(?::regclass)", Long.class, table);
        OffsetDateTime updatedAt = latestUpdateOf(table);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", ++fakeId);
        a.put("table", table);
        a.put("name", meta.cnName());
        a.put("domain", meta.domain());
        a.put("description", meta.description());
        a.put("format", meta.format());
        a.put("size", humanSize(bytes));
        a.put("sizeBytes", bytes);
        a.put("recordCount", count);
        a.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        a.put("owner", "system");
        a.put("tags", meta.tags());
        // 质量分：按是否有数据 + 行数规模给出可解释的口径，不再是魔数
        a.put("quality", qualityOf(count));
        out.add(a);
      } catch (Exception ex) {
        // 单张表盘点失败不影响整体（表可能被删/改名）
        log.debug("资产盘点跳过表 {}: {}", table, ex.getMessage());
      }
    }
    // 合并用户登记的数据资产（真实持久化，来自 sys_dict）
    out.addAll(catalogService.listRegistered());
    return out;
  }

  /** 取表最近更新时间：优先 updated_at，其次 created_at / recorded_at，都无则 null。 */
  private OffsetDateTime latestUpdateOf(String table) {
    for (String col : List.of("updated_at", "created_at", "recorded_at", "inspect_at", "generated_at")) {
      try {
        return jdbcTemplate.queryForObject(
            "SELECT max(" + col + ") FROM " + quote(table) + " WHERE " + col + " IS NOT NULL",
            OffsetDateTime.class);
      } catch (Exception ignored) {
        // 该表无此列，试下一个
      }
    }
    return null;
  }

  /** 质量分口径（可解释）：空表 0；行数越多基础分越高，上限 99。 */
  private int qualityOf(Long count) {
    if (count == null || count <= 0) {
      return 0;
    }
    double base = 60 + Math.log10(count + 1) * 12;
    return (int) Math.min(99, Math.round(base));
  }

  private String humanSize(Long bytes) {
    if (bytes == null) {
      return "—";
    }
    double b = bytes;
    String[] units = {"B", "KB", "MB", "GB", "TB"};
    int i = 0;
    while (b >= 1024 && i < units.length - 1) {
      b /= 1024;
      i++;
    }
    return (i == 0 ? String.format("%.0f", b) : String.format("%.1f", b)) + units[i];
  }

  private String quote(String ident) {
    return "\"" + ident.replace("\"", "\"\"") + "\"";
  }

  @GetMapping("/summary")
  @PreAuthorize("hasAuthority('data:catalog:view')")
  public ApiResponse<Map<String, Object>> summary() {
    List<Map<String, Object>> assets = loadRealAssets();
    long totalRecords = assets.stream()
        .mapToLong(a -> ((Number) a.get("recordCount")).longValue())
        .sum();
    long totalDomains = assets.stream().map(a -> a.get("domain")).distinct().count();
    long nonEmpty = assets.stream()
        .filter(a -> ((Number) a.get("recordCount")).longValue() > 0)
        .count();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("totalAssets", (long) assets.size());
    result.put("totalDomains", totalDomains);
    result.put("totalDatasets", (long) assets.size());
    result.put("nonEmptyAssets", nonEmpty);
    result.put("emptyAssets", assets.size() - nonEmpty);
    result.put("totalRecords", totalRecords);
    result.put("lastUpdated", OffsetDateTime.now(ZoneOffset.UTC).toString());
    result.put("dataSource", "PostgreSQL 业务库实盘");
    return ApiResponse.success(result);
  }

  @GetMapping("/assets")
  @PreAuthorize("hasAuthority('data:catalog:view')")
  public ApiResponse<PageResponse<Map<String, Object>>> listAssets(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String domain,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String tag) {

    List<Map<String, Object>> filtered = loadRealAssets().stream()
        .filter(a -> domain == null || domain.isBlank() || domain.equals(a.get("domain")))
        .filter(a -> keyword == null || keyword.isBlank()
            || String.valueOf(a.get("name")).contains(keyword)
            || String.valueOf(a.get("description")).contains(keyword))
        .filter(a -> tag == null || tag.isBlank()
            || ((List<?>) a.get("tags")).stream().anyMatch(t -> String.valueOf(t).contains(tag)))
        .toList();

    int total = filtered.size();
    int safeSize = Math.max(size, 1);
    int pages = (int) Math.ceil((double) total / safeSize);
    int fromIndex = Math.max((page - 1) * safeSize, 0);
    int toIndex = Math.min(fromIndex + safeSize, total);
    List<Map<String, Object>> items = fromIndex < total
        ? new ArrayList<>(filtered.subList(fromIndex, toIndex))
        : new ArrayList<>();

    return ApiResponse.success(new PageResponse<>(items, total, page, safeSize, pages));
  }

  /**
   * 登记数据资产（POST /api/catalog/assets）。
   *
   * <p>复用 {@link CatalogService} 的真实持久化（现有 {@code sys_dict} 通用字典表，
   * 不新建表 / 不改 schema），登记后资产会并入 {@link #loadRealAssets()} 出现在目录
   * 列表与详情中，不是假成功、不是 501 桩。操作人取 SecurityContext，不信任请求体。
   * 目录无专用写权限码，故用 hasAnyRole('ADMIN','SYSTEM_ADMIN') 兜底。
   */
  @PostMapping("/assets")
  @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN')")
  public ApiResponse<Map<String, Object>> createAsset(
      @RequestBody(required = false) Map<String, Object> body,
      Authentication authentication) {
    String operator = authentication == null ? "system" : authentication.getName();
    try {
      Map<String, Object> created = catalogService.register(body, operator);
      return ApiResponse.success("资产已登记", created);
    } catch (IllegalArgumentException e) {
      return ApiResponse.fail(400, e.getMessage());
    } catch (IllegalStateException e) {
      return ApiResponse.fail(503, e.getMessage());
    } catch (Exception e) {
      log.warn("登记数据资产失败: {}", e.getMessage());
      return ApiResponse.fail(500, "登记数据资产失败: " + e.getMessage());
    }
  }

  @GetMapping("/assets/{id}")
  @PreAuthorize("hasAuthority('data:catalog:view')")
  public ApiResponse<Map<String, Object>> getAsset(@PathVariable Long id) {
    Map<String, Object> asset = loadRealAssets().stream()
        .filter(a -> id.equals(a.get("id")))
        .findFirst()
        .orElse(null);
    if (asset == null) {
      return ApiResponse.fail(404, "资产不存在: " + id);
    }

    Object tableObj = asset.get("table");
    String table = tableObj == null ? null : String.valueOf(tableObj);
    Map<String, Object> detail = new HashMap<>(asset);
    if (table == null) {
      // 用户登记的资产没有对应实盘库表，如实给出空 schema 与 CUSTOM 血缘
      detail.put("schema", List.of());
      detail.put("lineage", List.of(Map.of(
          "source", "用户登记", "type", "CUSTOM",
          "code", asset.get("code") == null ? "" : String.valueOf(asset.get("code")))));
    } else {
      detail.put("schema", realColumns(table));
      detail.put("lineage", List.of(Map.of(
          "source", "PostgreSQL 业务库",
          "type", "TABLE",
          "table", table)));
    }
    detail.put("accessInfo", Map.of(
        "apiEndpoint", "/api/catalog/assets/" + id,
        "documentation", "—",
        "license", "内部"));
    return ApiResponse.success(detail);
  }

  /** 读取真实列定义（information_schema），失败返回空列表。 */
  private List<Map<String, Object>> realColumns(String table) {
    if (jdbcTemplate == null) {
      return List.of();
    }
    try {
      return jdbcTemplate.query(
          "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
              + "WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position",
          (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", rs.getString("column_name"));
            m.put("type", rs.getString("data_type"));
            m.put("nullable", "YES".equalsIgnoreCase(rs.getString("is_nullable")));
            return m;
          }, table);
    } catch (Exception ex) {
      return List.of();
    }
  }

  /**
   * 更新资产标签。
   *
   * <p>诚实说明：资产目录是库表的实时盘点视图，没有独立的标签存储表，
   * 因此本端点<b>不持久化</b>标签，仅回显本次请求的标签并明确标注
   * {@code persisted=false}。原实现同样不落库，却返回"标签更新成功"，属于虚假成功。
   */
  @PutMapping("/assets/{id}/tags")
  @PreAuthorize("hasAuthority('data:catalog:view')")
  public ApiResponse<Map<String, Object>> updateTags(
      @PathVariable Long id, @RequestBody Map<String, Object> request) {
    Map<String, Object> asset = loadRealAssets().stream()
        .filter(a -> id.equals(a.get("id")))
        .findFirst()
        .orElse(null);
    if (asset == null) {
      return ApiResponse.fail(404, "资产不存在: " + id);
    }
    @SuppressWarnings("unchecked")
    List<String> tags = (List<String>) request.get("tags");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("tags", tags == null ? List.of() : tags);
    result.put("persisted", false);
    result.put("updatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
    return ApiResponse.success("标签未持久化（资产目录为库表实时盘点，无标签存储表）", result);
  }

  /** 领域用量统计：按真实资产的领域聚合，不再写死。 */
  @GetMapping("/usage")
  @PreAuthorize("hasAuthority('data:catalog:view')")
  public ApiResponse<List<Map<String, Object>>> usage() {
    Map<String, Map<String, Object>> byDomain = new LinkedHashMap<>();
    for (Map<String, Object> a : loadRealAssets()) {
      String domain = String.valueOf(a.get("domain"));
      Map<String, Object> agg = byDomain.computeIfAbsent(domain, k -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", k);
        m.put("assetCount", 0L);
        m.put("recordCount", 0L);
        m.put("sizeBytes", 0L);
        m.put("lastAccess", null);
        m.put("accessCount", null);
        return m;
      });
      agg.put("assetCount", (Long) agg.get("assetCount") + 1);
      agg.put("recordCount", (Long) agg.get("recordCount")
          + ((Number) a.get("recordCount")).longValue());
      agg.put("sizeBytes", (Long) agg.get("sizeBytes")
          + (a.get("sizeBytes") == null ? 0L : ((Number) a.get("sizeBytes")).longValue()));
    }
    List<Map<String, Object>> out = new ArrayList<>(byDomain.values());
    for (Map<String, Object> m : out) {
      m.put("size", humanSize((Long) m.get("sizeBytes")));
      // 访问统计需接入访问日志，当前不可得，如实为 null（前端据此显示"—"）
      m.put("lastAccess", null);
      m.put("accessCount", null);
    }
    return ApiResponse.success(out);
  }

  @PostMapping("/assets/{id}/subscribe")
  @PreAuthorize("hasAuthority('data:catalog:view')")
  public ApiResponse<Map<String, Object>> subscribe(
      @PathVariable Long id, @RequestBody Map<String, Object> request) {
    String email = (String) request.get("email");
    String webhook = (String) request.get("webhook");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("subscriptionId", UUID.randomUUID().toString());
    result.put("assetId", id);
    result.put("email", email != null ? email : "");
    result.put("webhook", webhook != null ? webhook : "");
    result.put("createdAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
    result.put("dispatched", false);
    return ApiResponse.success("订阅已登记（未接入推送网关，不会真正发送通知）", result);
  }

  /**
   * 提交数据资产访问申请。
   *
   * <p>资产目录已改为真实库表盘点，但没有落库的资产台账与审批流，
   * 仍无法真正受理申请。与其返回假的"申请已提交"，不如明确告知未实现。
   */
  @PostMapping("/assets/{id}/access-requests")
  @PreAuthorize("hasAuthority('data:catalog:view')")
  public ApiResponse<Map<String, Object>> requestAccess(
      @PathVariable Long id, @RequestBody(required = false) Map<String, Object> payload) {
    if (payload == null || payload.get("scenario") == null) {
      return ApiResponse.fail(400, "请填写申请场景");
    }
    return ApiResponse.fail(501,
        "数据资产访问申请尚未实现：资产目录为库表实时盘点，无资产台账与审批流，暂无法受理申请（资产 " + id + "）");
  }

  /** 资产元数据。 */
  private record AssetMeta(
      String domain, String cnName, String description, String format, List<String> tags) {}
}
