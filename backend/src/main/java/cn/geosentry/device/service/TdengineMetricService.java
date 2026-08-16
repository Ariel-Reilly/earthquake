package cn.geosentry.device.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * TDengine 时序写入服务（脚手架）。
 *
 * <p>当 TDengine 可用时（{@code tdengine.enabled=true} 且 JDBC 驱动在 classpath），
 * 设备指标上报可额外写入 TDengine 超级表 {@code device_metric}，
 * 为时序查询/趋势分析提供高性能存储；不可用时静默降级，仍以 PostgreSQL 为主存储。
 *
 * <p>本服务不硬依赖 TDengine JDBC jar，通过 {@link DriverManager} 动态加载：
 * 部署时将 {@code taos-jdbc-*.jar} 放入 app/lib 或 Docker 镜像即可启用。
 */
@Service
public class TdengineMetricService {

  private static final Logger log = LoggerFactory.getLogger(TdengineMetricService.class);

  private final boolean enabled;
  private final String url;
  private final String username;
  private final String password;
  private final boolean driverAvailable;

  public TdengineMetricService(
      @Value("${tdengine.enabled:false}") boolean enabled,
      @Value("${tdengine.url:jdbc:TAOS-RS://localhost:6041/geohazard}") String url,
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
    }
  }

  /**
   * 写入单条设备指标到 TDengine。
   *
   * @return true=写入成功；false=TDengine 不可用或写入失败（静默）
   */
  public boolean writeMetric(String deviceCode, String metricCode, double value, OffsetDateTime timestamp) {
    if (!enabled || !driverAvailable || deviceCode == null || metricCode == null) {
      return false;
    }
    try (Connection conn = DriverManager.getConnection(url, username, password);
         PreparedStatement ps = conn.prepareStatement(
             "INSERT INTO device_metric_" + sanitize(deviceCode)
                 + " (ts, metric_code, value) VALUES (?, ?, ?)")) {
      ps.setTimestamp(1, Timestamp.from(timestamp.toInstant()));
      ps.setString(2, metricCode);
      ps.setDouble(3, value);
      ps.executeUpdate();
      return true;
    } catch (SQLException e) {
      log.warn("TDengine 写入失败 device={}, metric={}, ts={}: {}", deviceCode, metricCode, timestamp, e.getMessage());
      return false;
    }
  }

  public boolean isEnabled() {
    return enabled && driverAvailable;
  }

  /** TDengine 子表名限制：仅保留字母数字和下划线。 */
  private String sanitize(String name) {
    return name == null ? "unknown" : name.replaceAll("[^a-zA-Z0-9_]", "_");
  }
}
