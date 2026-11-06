package cn.disasterguard.governance;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.governance.entity.DataSource;
import cn.disasterguard.governance.repository.DataSourceRepository;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据源注册表（{@code data_source} 表）。
 *
 * <p>v1.8.0 起改为读取真实库：此前本控制器返回内存 mock 注册表
 * （USGS 地震目录 / 中国地震台网 / GPS CORS 等），与库中真实的 6 条数据源
 * （Sentinel-1/2、ERA5、GPM、本地 MQTT、SRTM）完全不符。
 *
 * <p>{@code /test} 端点原用 {@code Math.random()} 伪造 20~150ms 延迟，
 * 现改为对数据源 endpoint 做真实 TCP 连通性探测并计时；
 * 探测不到的（如 OAuth2 Web API 只接受 HTTPS 且需鉴权）如实返回
 * {@code probeSupported=false}，由前端提示"需人工确认"。
 */
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

  private static final Logger log = LoggerFactory.getLogger(DataSourceController.class);

  /** TCP 探测超时（毫秒）。 */
  private static final int PROBE_TIMEOUT_MS = 2000;

  private final DataSourceRepository repository;

  public DataSourceController(DataSourceRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('datasource:view')")
  public ApiResponse<List<Map<String, Object>>> list() {
    List<DataSource> rows = repository.findByOrderByIdAsc();
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (DataSource ds : rows) {
      out.add(toDto(ds));
    }
    return ApiResponse.success(out);
  }

  /**
   * 新建数据源（POST /api/datasources）。
   *
   * <p>复用 {@link #list()} / {@link #toggle(String, Map)} 的同库持久化：写入
   * {@code data_source} 表。操作人取 SecurityContext，不信任请求体。
   * 复用既有 {@code toDto} 映射，保证返回结构与列表一致。
   */
  @PostMapping
  @PreAuthorize("hasAuthority('datasource:create')")
  @Transactional
  public ApiResponse<Map<String, Object>> create(
      @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> b = body == null ? Map.of() : body;
    String code = str(b.get("code"));
    if (isBlank(code)) {
      return ApiResponse.fail(400, "数据源编码(code)不能为空");
    }
    if (repository.findByDsCode(code.trim()).isPresent()) {
      return ApiResponse.fail(409, "数据源编码已存在: " + code);
    }
    String name = firstNonBlank(b.get("name"), b.get("label"), b.get("dsName"));
    if (isBlank(name)) {
      return ApiResponse.fail(400, "数据源名称(name)不能为空");
    }

    DataSource ds = new DataSource();
    ds.setDsCode(code.trim());
    ds.setDsName(name.trim());
    ds.setDsType(firstNonBlank(b.get("type"), b.get("category"), b.get("dsType"), "CUSTOM").trim());
    ds.setEndpoint(firstNonBlank(b.get("endpoint"), buildEndpoint(b)));
    ds.setAuthType(str(b.get("authType")));
    ds.setStatus(Boolean.TRUE.equals(b.get("enabled")) ? "CONNECTED" : "DISCONNECTED");
    ds.setSyncInterval(intValue(b.get("syncInterval"), 3600));
    ds.setCreatedBy(currentOperator());
    OffsetDateTime now = OffsetDateTime.now();
    ds.setCreatedAt(now);
    ds.setUpdatedAt(now);
    return ApiResponse.success("数据源已创建", toDto(repository.save(ds)));
  }

  /**
   * 更新数据源（PUT /api/datasources/{id}）。
   *
   * <p>路径变量兼容数字 id 与 dsCode 两种形式（前端编辑页传的是 code）。
   * 仅覆盖请求中实际给出的字段，避免把未传字段清空。
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('datasource:edit')")
  @Transactional
  public ApiResponse<Map<String, Object>> update(
      @PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body) {
    DataSource ds = findForUpdate(id);
    if (ds == null) {
      return ApiResponse.fail(404, "数据源不存在: " + id);
    }
    Map<String, Object> b = body == null ? Map.of() : body;
    String name = firstNonBlank(b.get("name"), b.get("label"), b.get("dsName"));
    if (!isBlank(name)) {
      ds.setDsName(name.trim());
    }
    String type = firstNonBlank(b.get("type"), b.get("category"), b.get("dsType"));
    if (!isBlank(type)) {
      ds.setDsType(type.trim());
    }
    String endpoint = firstNonBlank(b.get("endpoint"), buildEndpoint(b));
    if (!isBlank(endpoint)) {
      ds.setEndpoint(endpoint.trim());
    }
    String authType = str(b.get("authType"));
    if (!isBlank(authType)) {
      ds.setAuthType(authType.trim());
    }
    if (b.containsKey("syncInterval")) {
      int fallback = ds.getSyncInterval() == null ? 3600 : ds.getSyncInterval();
      ds.setSyncInterval(intValue(b.get("syncInterval"), fallback));
    }
    if (b.containsKey("enabled")) {
      ds.setStatus(Boolean.TRUE.equals(b.get("enabled")) ? "CONNECTED" : "DISCONNECTED");
    }
    ds.setUpdatedAt(OffsetDateTime.now());
    return ApiResponse.success("数据源已更新", toDto(repository.save(ds)));
  }

  @PostMapping("/{code}/toggle")
  @PreAuthorize("hasAuthority('datasource:view')")
  @Transactional
  public ApiResponse<Map<String, Object>> toggle(
      @PathVariable String code, @RequestBody Map<String, Object> body) {
    DataSource ds = repository.findByDsCode(code).orElse(null);
    if (ds == null) {
      return ApiResponse.fail(404, "数据源不存在: " + code);
    }
    boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
    // 库里 status 的合法值：CONNECTED / DISCONNECTED / ERROR / SYNCING（见 ck_ds_status）
    ds.setStatus(enabled ? "CONNECTED" : "DISCONNECTED");
    ds.setLastSyncAt(OffsetDateTime.now());
    ds.setUpdatedAt(OffsetDateTime.now());
    repository.save(ds);
    return ApiResponse.success(enabled ? "数据源已启用" : "数据源已禁用", toDto(ds));
  }

  /**
   * 数据源连通性测试。
   *
   * <p>对 endpoint 的 host:port 做真实 TCP 握手并计时，不再随机生成延迟。
   * 无法解析或非 TCP 语义的 endpoint 返回 {@code probeSupported=false}。
   */
  @PostMapping("/{code}/test")
  @PreAuthorize("hasAuthority('datasource:view')")
  public ApiResponse<Map<String, Object>> test(@PathVariable String code) {
    DataSource ds = repository.findByDsCode(code).orElse(null);
    if (ds == null) {
      return ApiResponse.fail(404, "数据源不存在: " + code);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("code", ds.getDsCode());
    result.put("name", ds.getDsName());
    result.put("endpoint", ds.getEndpoint());

    HostPort hp = resolveHostPort(ds.getEndpoint());
    if (hp == null) {
      result.put("success", false);
      result.put("probeSupported", false);
      result.put("latencyMs", null);
      result.put("message", "该数据源未配置可探测的 host:port，需人工确认连通性");
      return ApiResponse.success(result);
    }

    long start = System.nanoTime();
    boolean ok;
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(hp.host, hp.port), PROBE_TIMEOUT_MS);
      ok = true;
    } catch (Exception ex) {
      ok = false;
      log.debug("数据源探测失败 {} -> {}:{}: {}", code, hp.host, hp.port, ex.getMessage());
    }
    long latency = (System.nanoTime() - start) / 1_000_000;

    result.put("success", ok);
    result.put("probeSupported", true);
    result.put("latencyMs", ok ? latency : -1);
    result.put("message", ok ? "TCP 连通（延迟 " + latency + " ms）" : "TCP 连接失败或超时");
    return ApiResponse.success(result);
  }

  /** 按数字 id 或 dsCode 定位数据源。 */
  private DataSource findForUpdate(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    try {
      var byId = repository.findById(Long.parseLong(id));
      if (byId.isPresent()) {
        return byId.get();
      }
    } catch (NumberFormatException ignored) {
      // 非数字主键，回退到 dsCode
    }
    return repository.findByDsCode(id).orElse(null);
  }

  /** 从 host / port / database 拼出可探测的 endpoint。 */
  private String buildEndpoint(Map<String, Object> b) {
    String host = str(b.get("host"));
    if (isBlank(host)) {
      return null;
    }
    StringBuilder sb = new StringBuilder(host.trim());
    String port = str(b.get("port"));
    if (!isBlank(port)) {
      sb.append(':').append(port.trim());
    }
    String database = str(b.get("database"));
    if (!isBlank(database)) {
      sb.append('/').append(database.trim());
    }
    return sb.toString();
  }

  private String firstNonBlank(Object... values) {
    for (Object v : values) {
      String s = str(v);
      if (!isBlank(s)) {
        return s;
      }
    }
    return null;
  }

  private int intValue(Object v, int fallback) {
    if (v instanceof Number n) {
      return n.intValue();
    }
    if (v != null) {
      try {
        return Integer.parseInt(String.valueOf(v).trim());
      } catch (NumberFormatException ignored) {
        // 落到 fallback
      }
    }
    return fallback;
  }

  private String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  private boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** 操作人一律取 SecurityContext，不信任请求体。 */
  private String currentOperator() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
      return auth.getName();
    }
    return "system";
  }

  /** 映射为前端契约。不含 auth_config（避免泄露凭据）。 */
  private Map<String, Object> toDto(DataSource ds) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", ds.getId());
    m.put("code", ds.getDsCode());
    m.put("name", ds.getDsName());
    m.put("type", ds.getDsType());
    m.put("endpoint", ds.getEndpoint());
    m.put("authType", ds.getAuthType());
    m.put("status", ds.getStatus());
    m.put("lastSyncAt", ds.getLastSyncAt());
    m.put("syncInterval", ds.getSyncInterval());
    m.put("enabled", "CONNECTED".equalsIgnoreCase(ds.getStatus())
        || "SYNCING".equalsIgnoreCase(ds.getStatus()));
    return m;
  }

  /** 从 endpoint 解析出 host:port；无法解析时返回 null。 */
  private HostPort resolveHostPort(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      return null;
    }
    String raw = endpoint.trim();
    try {
      // tcp://host:port / https://host / http://host:port
      if (raw.contains("://")) {
        URI uri = URI.create(raw.replace(" ", ""));
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
          return null;
        }
        int port = uri.getPort();
        if (port <= 0) {
          port = defaultPort(uri.getScheme());
        }
        return port > 0 ? new HostPort(host, port) : null;
      }
      // host:port 裸形式
      int idx = raw.lastIndexOf(':');
      if (idx > 0 && idx < raw.length() - 1) {
        String host = raw.substring(0, idx);
        int port = Integer.parseInt(raw.substring(idx + 1).trim());
        return port > 0 ? new HostPort(host, port) : null;
      }
      return null;
    } catch (Exception ex) {
      return null;
    }
  }

  private int defaultPort(String scheme) {
    if (scheme == null) {
      return -1;
    }
    return switch (scheme.toLowerCase()) {
      case "https" -> 443;
      case "http" -> 80;
      case "tcp", "mqtt" -> 1883;
      case "mqtts", "ssl" -> 8883;
      default -> -1;
    };
  }

  /** host + port 组合。 */
  private record HostPort(String host, int port) {}
}
