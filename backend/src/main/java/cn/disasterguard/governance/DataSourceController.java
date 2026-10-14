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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
