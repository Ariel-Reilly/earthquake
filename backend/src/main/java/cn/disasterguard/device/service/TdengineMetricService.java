package cn.disasterguard.device.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * TDengine 时序写入服务。
 *
 * <p>当 TDengine 可用时（{@code tdengine.enabled=true} 且 JDBC 驱动在 classpath），
 * 设备指标上报可额外写入 TDengine 超级表 {@code device_metric}，
 * 为时序查询/趋势分析提供高性能存储；不可用时静默降级，仍以 PostgreSQL 为主存储。
 *
 * <p>连接策略：每次写入获取短生命周期连接（TDengine 无连接池支持，driver 内部做了优化）；
 * 子表 {@code device_metric_<deviceCode>} 在首次写入该设备时自动创建（CREATE TABLE IF NOT EXISTS）。
 *
 * <p>超级表和种子子表由 {@code infra/tdengine/init.sql} 在 TDengine 容器首次启动时创建。
 */
@Service
public class TdengineMetricService {

  private static final Logger log = LoggerFactory.getLogger(TdengineMetricService.class);

  /** 每个子表名最大长度限制，超出截断。 */
  private static final int MAX_TABLE_NAME_LEN = 64;

  private final boolean enabled;
  private final String url;
  private final String username;
  private final String password;
  private final boolean driverAvailable;
  /** 已确认存在的子表缓存，避免频繁 DDL。 */
  private final Map<String, Boolean> tableCache = new ConcurrentHashMap<>();
  /** 连接异常熔断标记——连续失败时跳过连接，避免拖慢主链路。 */
  private final AtomicBoolean circuitOpen = new AtomicBoolean(false);
  /** 熔断恢复时间戳（epoch ms），到期后尝试恢复。 */
  private volatile long circuitRecoverAtMs = 0L;
  private static final long CIRCUIT_OPEN_DURATION_MS = 30_000L;  // 30s

  public TdengineMetricService(
      @Value("${tdengine.enabled:false}") boolean enabled,
      @Value("${tdengine.url:jdbc:TAOS-RS://localhost:6041/disasterguard}") String url,
      @Value("${tdengine.username:root}") String username,
      @Value("${tdengine.password:taosdata}") String password) {
    this.enabled = enabled;
    this.url = url;
    this.username = username;
    this.password = password;
    // 尝试探测 TDengine JDBC 驱动是否在 classpath
    boolean found = false;
    try {
      Class.forName("com.taosdata.jdbc.rs.RestfulDriver");
      found = true;
    } catch (ClassNotFoundException ignored) {
      try {
        Class.forName("com.taosdata.jdbc.TSDBDriver");
        found = true;
      } catch (ClassNotFoundException ignored2) {
        // driver not present
      }
    }
    this.driverAvailable = found;
    if (enabled && !found) {
      log.warn("tdengine.enabled=true 但未找到 TDengine JDBC 驱动（com.taosdata.jdbc），时序写入已降级为跳过");
    } else if (enabled && found) {
      log.info("TDengine 驱动已就绪，时序写入已启用");
    }
  }

  /**
   * 写入单条设备指标到 TDengine（自动确保子表存在）。
   *
   * @return true=写入成功；false=TDengine 不可用或写入失败
   */
  public boolean writeMetric(String deviceCode, String metricCode, double value, OffsetDateTime timestamp) {
    if (!isEnabled() || deviceCode == null || metricCode == null) {
      return false;
    }

    // 熔断器：熔断期间直接跳过，避免拖慢主链路
    if (circuitOpen.get()) {
      if (System.currentTimeMillis() < circuitRecoverAtMs) {
        return false;
      }
      // 到期，尝试恢复
      circuitOpen.set(false);
    }

    String tableName = buildTableName(deviceCode);
    try (Connection conn = openConnection()) {
      // 确保子表存在（幂等，已缓存）
      ensureTable(conn, tableName, deviceCode);

      try (PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO " + tableName + " (ts, metric_code, value) VALUES (?, ?, ?)")) {
        ps.setTimestamp(1, Timestamp.from(timestamp.toInstant()));
        ps.setString(2, metricCode);
        ps.setDouble(3, value);
        ps.executeUpdate();
        return true;
      }
    } catch (SQLException e) {
      log.warn("TDengine 写入失败 device={}, metric={}, ts={}: {}", deviceCode, metricCode, timestamp, e.getMessage());
      openCircuit();
      return false;
    }
  }

  public boolean isEnabled() {
    return enabled && driverAvailable;
  }

  /** 从连接池/驱动获取连接（TDengine RS driver 内部已做连接复用）。 */
  private Connection openConnection() throws SQLException {
    return DriverManager.getConnection(url, username, password);
  }

  /**
   * 确保子表存在（CREATE TABLE IF NOT EXISTS），成功后写入缓存避免重复 DDL。
   */
  private void ensureTable(Connection conn, String tableName, String deviceCode) {
    if (tableCache.containsKey(tableName)) {
      return;
    }
    try (Statement stmt = conn.createStatement()) {
      // TDengine 语法：CREATE TABLE IF NOT EXISTS <name> USING <stable> TAGS (...)
      // hazard_code 标签设为 NULL（不知道也没关系，不影响查询）
      stmt.execute("CREATE TABLE IF NOT EXISTS " + tableName
          + " USING device_metric TAGS ('" + sanitize(deviceCode) + "', NULL)");
      tableCache.put(tableName, Boolean.TRUE);
    } catch (SQLException e) {
      // 表已存在但 cache 未命中（并发场景），忽略错误
      log.debug("TDengine 子表创建（可能已存在）{}: {}", tableName, e.getMessage());
      tableCache.put(tableName, Boolean.TRUE);
    }
  }

  /** 熔断打开——连续失败后跳过连接，定期重试恢复。 */
  private void openCircuit() {
    if (circuitOpen.compareAndSet(false, true)) {
      circuitRecoverAtMs = System.currentTimeMillis() + CIRCUIT_OPEN_DURATION_MS;
      log.warn("TDengine 熔断器已打开，{}s 后尝试恢复", CIRCUIT_OPEN_DURATION_MS / 1000);
    }
  }

  /** TDengine 子表名：device_metric_<sanitized>，限制长度。 */
  private String buildTableName(String deviceCode) {
    String suffix = sanitize(deviceCode).toLowerCase();
    String full = "device_metric_" + suffix;
    return full.length() > MAX_TABLE_NAME_LEN ? full.substring(0, MAX_TABLE_NAME_LEN) : full;
  }

  /** TDengine 标识符限制：仅保留字母数字和下划线。 */
  private String sanitize(String name) {
    if (name == null) return "unknown";
    return name.replaceAll("[^a-zA-Z0-9_]", "_");
  }
}
