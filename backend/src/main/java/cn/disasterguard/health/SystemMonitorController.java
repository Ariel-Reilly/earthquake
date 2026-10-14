package cn.disasterguard.health;

import cn.disasterguard.common.api.ApiResponse;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统监控（v1.7.0+ 系统监控页面）。
 *
 * <p>前端 dashboardApi.js 依赖：{@code GET /api/system/monitor}.
 *
 * <p>返回前端监控页所需的完整结构：{@code services / apiCalls24h / storage / trends / alerts}。
 * 其中：
 * <ul>
 *   <li>{@code services} — 对 PostgreSQL / Redis / MinIO / EMQX / AI Service 做真实 TCP 连通性探测，
 *       附带往返延迟（ms）；探测失败即 UP→DOWN，不伪造状态。</li>
 *   <li>{@code apiCalls24h} — 暂无法从无状态的 HTTP 层统计，返回 {@code available:false}。</li>
 *   <li>{@code trends} — 趋势需要历史采样，返回 {@code available:false}。</li>
 *   <li>{@code alerts} — 由真实探测结果派生（依赖不可用、CPU/内存超阈值），无告警则为空数组。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/system")
public class SystemMonitorController {

  private static final Logger log = LoggerFactory.getLogger(SystemMonitorController.class);

  /** JVM 启动时间（毫秒）。 */
  private final RuntimeMXBean runtimeMX = ManagementFactory.getRuntimeMXBean();

  /** 内存信息。 */
  private final MemoryMXBean memoryMX = ManagementFactory.getMemoryMXBean();

  /** OS 信息（含 CPU 负载）。 */
  private final OperatingSystemMXBean osMX = ManagementFactory.getOperatingSystemMXBean();

  @Autowired(required = false)
  private DataSource dataSource;

  @Value("${spring.data.redis.host:127.0.0.1}")
  private String redisHost;

  @Value("${spring.data.redis.port:6379}")
  private int redisPort;

  @Value("${minio.endpoint:http://127.0.0.1:9000}")
  private String minioEndpoint;

  @Value("${emqx.enabled:false}")
  private boolean emqxEnabled;

  /** EMQX MQTT 地址（tcp://host:port 形式）。 */
  @Value("${emqx.mqtt-url:tcp://127.0.0.1:1883}")
  private String emqxMqttUrl;

  @Value("${ai.enabled:true}")
  private boolean aiEnabled;

  /** AI 服务基础地址（http://host:port/api/ai 形式）。 */
  @Value("${ai.service-base-url:http://127.0.0.1:8001/api/ai}")
  private String aiServiceBaseUrl;

  /** CPU 使用率超过该值（%）时产生告警。 */
  private static final double CPU_ALERT_THRESHOLD = 90.0;

  /** 内存使用率超过该值（%）时产生告警。 */
  private static final double MEM_ALERT_THRESHOLD = 90.0;

  /** TCP 探测超时（毫秒）。 */
  private static final int PROBE_TIMEOUT_MS = 1200;

  /** JVM 启动预热窗口（秒）：窗口内不报 CPU 告警，规避 JMX 首采样偏高。 */
  private static final long CPU_WARMUP_SECONDS = 60;

  /** 获取系统监控指标。 */
  @GetMapping("/monitor")
  @PreAuthorize("hasAuthority('monitor:view')")
  public ApiResponse<Map<String, Object>> monitor() {
    double cpuUsage = getCpuUsage();
    long heapUsed = memoryMX.getHeapMemoryUsage().getUsed();
    long heapMax = memoryMX.getHeapMemoryUsage().getMax();
    double memoryUsage = heapMax > 0 ? Math.round(heapUsed * 10000.0 / heapMax) / 100.0 : 0.0;
    long jvmUptime = runtimeMX.getUptime() / 1000;

    List<Map<String, Object>> services = probeServices();
    List<Map<String, Object>> alerts =
        deriveAlerts(services, cpuUsage, memoryUsage, jvmUptime);

    Map<String, Object> result = new LinkedHashMap<>();
    // 基础指标
    result.put("cpuUsage", cpuUsage);
    result.put("memoryUsage", memoryUsage);
    result.put("jvmUptime", jvmUptime);
    result.put("activeUsers", 0);
    result.put("timestamp", System.currentTimeMillis());

    // 前端监控页所需结构
    result.put("services", services);
    // 24h 接口调用统计：HTTP 层无状态，暂不可得
    result.put("apiCalls24h", Map.of("available", false));
    // 存储用量：需按库实际统计，暂不可得（前端据此显示"暂无数据"）
    result.put("storage", Map.of("available", false));
    // 趋势：需要时间序列采样，暂不可得（前端据此显示"暂无数据"而非假曲线）
    result.put("trends", Map.of(
        "available", false,
        "cpu", List.of(),
        "gpu", List.of(),
        "mem", List.of()));
    result.put("alerts", alerts);
    return ApiResponse.success(result);
  }

  /**
   * 对已启用的依赖服务做真实 TCP 连通性探测。
   *
   * <p>未启用的服务（EMQX / TDengine 等）标记为 {@code DISABLED}，不算 DOWN。
   */
  private List<Map<String, Object>> probeServices() {
    List<Map<String, Object>> list = new ArrayList<>();

    // PostgreSQL：复用连接池，顺带测一次 SELECT 1 的往返
    Map<String, Object> pg = new LinkedHashMap<>();
    pg.put("name", "PostgreSQL");
    long pgStart = System.nanoTime();
    boolean pgOk = checkDatabase();
    pg.put("state", pgOk ? "UP" : "DOWN");
    pg.put("latency", pgOk ? (System.nanoTime() - pgStart) / 1_000_000 : -1);
    list.add(pg);

    // Redis / MinIO / AI Service / EMQX：TCP 探测
    list.add(tcpProbe("Redis", redisHost, redisPort, true));
    list.add(tcpProbe("MinIO", resolveHost(minioEndpoint), resolvePort(minioEndpoint, 9000), true));
    list.add(tcpProbe(
        "AI Service",
        resolveHost(aiServiceBaseUrl),
        resolvePort(aiServiceBaseUrl, 8001),
        aiEnabled));
    list.add(tcpProbe(
        "EMQX",
        resolveHost(emqxMqttUrl),
        resolvePort(emqxMqttUrl, 1883),
        emqxEnabled));

    // 后端自身
    Map<String, Object> backend = new LinkedHashMap<>();
    backend.put("name", "Backend API");
    backend.put("state", "UP");
    backend.put("latency", 0);
    list.add(0, backend);

    return list;
  }

  /**
   * 单个 TCP 连通性探测。
   *
   * @param enabled=false 时返回 {@code DISABLED}（不探测、不算故障）
   */
  private Map<String, Object> tcpProbe(String name, String host, int port, boolean enabled) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("name", name);
    if (!enabled) {
      item.put("state", "DISABLED");
      item.put("latency", -1);
      return item;
    }
    long start = System.nanoTime();
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
      item.put("state", "UP");
      item.put("latency", (System.nanoTime() - start) / 1_000_000);
    } catch (Exception e) {
      log.debug("服务探测失败 {}:{} — {}", host, port, e.getMessage());
      item.put("state", "DOWN");
      item.put("latency", -1);
    }
    return item;
  }

  /** 从 endpoint URL 解析主机名；解析失败回退默认值。支持 http:// 与 tcp:// 两种形式。 */
  private String resolveHost(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      return "127.0.0.1";
    }
    // 去掉 scheme（http:// / tcp:// / TAOS-RS:// 等非标准 scheme 用正则剥离）
    String rest = endpoint.replaceFirst("^[A-Za-z][A-Za-z0-9+.\\-]*://", "");
    int slash = rest.indexOf('/');
    if (slash >= 0) {
      rest = rest.substring(0, slash);
    }
    int colon = rest.lastIndexOf(':');
    String host = colon > 0 ? rest.substring(0, colon) : rest;
    return host.isBlank() ? "127.0.0.1" : host;
  }

  /** 从 endpoint URL 解析端口；解析失败回退默认值。 */
  private int resolvePort(String endpoint, int fallback) {
    if (endpoint == null || endpoint.isBlank()) {
      return fallback;
    }
    String rest = endpoint.replaceFirst("^[A-Za-z][A-Za-z0-9+.\\-]*://", "");
    int slash = rest.indexOf('/');
    if (slash >= 0) {
      rest = rest.substring(0, slash);
    }
    int colon = rest.lastIndexOf(':');
    if (colon > 0 && colon < rest.length() - 1) {
      try {
        return Integer.parseInt(rest.substring(colon + 1));
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  /** 由真实探测结果与指标派生告警；无告警返回空列表。 */
  private List<Map<String, Object>> deriveAlerts(
      List<Map<String, Object>> services, double cpuUsage, double memUsage, long jvmUptimeSec) {
    List<Map<String, Object>> alerts = new ArrayList<>();
    long seq = 1;

    // 1) 服务不可用告警
    for (Map<String, Object> svc : services) {
      if ("DOWN".equals(svc.get("state"))) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", seq++);
        a.put("level", "critical");
        a.put("source", svc.get("name"));
        a.put("title", svc.get("name") + " 服务不可达");
        a.put("time", "当前");
        a.put("resolved", false);
        alerts.add(a);
      }
    }

    // 2) CPU 阈值告警（仅当采集到有效值 ≥ 0，且已过 JVM 启动预热窗口）
    //    JMX getCpuLoad() 在 JVM 刚启动时首个采样常返回 1.0，会造成误报，
    //    因此在启动后 60 秒内不产生 CPU 告警。
    if (jvmUptimeSec > CPU_WARMUP_SECONDS && cpuUsage >= CPU_ALERT_THRESHOLD) {
      Map<String, Object> a = new LinkedHashMap<>();
      a.put("id", seq++);
      a.put("level", "warning");
      a.put("source", "Backend API");
      a.put("title", String.format("CPU 使用率偏高（%.1f%%）", cpuUsage));
      a.put("time", "当前");
      a.put("resolved", false);
      alerts.add(a);
    }

    // 3) 内存阈值告警
    if (memUsage >= MEM_ALERT_THRESHOLD) {
      Map<String, Object> a = new LinkedHashMap<>();
      a.put("id", seq++);
      a.put("level", "warning");
      a.put("source", "Backend API");
      a.put("title", String.format("JVM 堆内存使用率偏高（%.1f%%）", memUsage));
      a.put("time", "当前");
      a.put("resolved", false);
      alerts.add(a);
    }

    // 4) 无告警时给出一条信息级说明，明确"当前无异常"，不伪造历史条目
    if (alerts.isEmpty()) {
      Map<String, Object> a = new LinkedHashMap<>();
      a.put("id", seq);
      a.put("level", "info");
      a.put("source", "System");
      a.put("title", jvmUptimeSec > 0
          ? String.format("全部已启用依赖正常（JVM 已运行 %d 秒）", jvmUptimeSec)
          : "全部已启用依赖正常");
      a.put("time", "当前");
      a.put("resolved", true);
      alerts.add(a);
    }
    return alerts;
  }

  /** 轻量 PostgreSQL 连通性检查（SELECT 1）。 */
  private boolean checkDatabase() {
    if (dataSource == null) {
      return false;
    }
    try (var conn = dataSource.getConnection();
         var stmt = conn.createStatement();
         var rs = stmt.executeQuery("SELECT 1")) {
      return rs.next();
    } catch (Exception ex) {
      log.debug("数据库连通性检查失败: {}", ex.getMessage());
      return false;
    }
  }

  /**
   * 尝试读取操作系统 CPU 负载。
   * 兼容不同 JVM 实现：Linux / Windows / Mac / 容器环境。
   */
  private double getCpuUsage() {
    try {
      // 方式一：OperatingSystemMXBean.getSystemLoadAverage()（Linux/Unix）
      double loadAvg = osMX.getSystemLoadAverage();
      if (loadAvg >= 0) {
        int processors = osMX.getAvailableProcessors();
        return Math.round(loadAvg / processors * 10000) / 100.0;
      }
      // 方式二：UnixOperatingSystemMXBean.getCpuLoad()（JDK 17+ on Linux/macOS）
      if (osMX instanceof com.sun.management.OperatingSystemMXBean sunOs) {
        double cpuLoad = sunOs.getCpuLoad();
        if (cpuLoad >= 0) {
          return Math.round(cpuLoad * 10000) / 100.0;
        }
      }
    } catch (Exception ignored) {
      // 容器 / 受限环境可能拒绝访问，返回占位
    }
    return -1.0;
  }
}
