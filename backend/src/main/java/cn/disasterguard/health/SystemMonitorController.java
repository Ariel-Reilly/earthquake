package cn.disasterguard.health;

import cn.disasterguard.common.api.ApiResponse;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统监控（v1.7.0+ 系统监控页面）。
 *
 * <p>前端 dashboardApi.js 依赖：{@code GET /api/system/monitor}.
 *
 * <p>通过 JMX Runtime / OperatingSystem / Memory MXBean 采集 CPU、内存、JVM 运行时信息。
 * DB 连接池 / Redis 状态通过 Spring 内建 beans 读取，无注入时返回占位值。
 */
@RestController
@RequestMapping("/api/system")
public class SystemMonitorController {

  /** JVM 启动时间（毫秒）。 */
  private final RuntimeMXBean runtimeMX = ManagementFactory.getRuntimeMXBean();

  /** 内存信息。 */
  private final MemoryMXBean memoryMX = ManagementFactory.getMemoryMXBean();

  /** OS 信息（含 CPU 负载）。 */
  private final OperatingSystemMXBean osMX = ManagementFactory.getOperatingSystemMXBean();

  /** 获取系统监控指标。 */
  @GetMapping("/monitor")
  public ApiResponse<Map<String, Object>> monitor() {
    // CPU 使用率（JVM 进程级）
    double cpuUsage = getCpuUsage();

    // 内存使用率
    long heapUsed = memoryMX.getHeapMemoryUsage().getUsed();
    long heapMax = memoryMX.getHeapMemoryUsage().getMax();
    double memoryUsage = heapMax > 0 ? Math.round(heapUsed * 10000.0 / heapMax) / 100.0 : 0.0;

    // JVM 运行时
    long uptimeMs = runtimeMX.getUptime();
    long jvmUptime = uptimeMs / 1000; // 秒

    // 活跃用户数（暂无会话存储，占位）
    int activeUsers = 0;

    // DB 活跃连接数（无注入时占位）
    int dbPoolActive = -1;

    // Redis 状态（无注入时占位）
    String redisStatus = "unknown";

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("cpuUsage", cpuUsage);
    result.put("memoryUsage", memoryUsage);
    result.put("jvmUptime", jvmUptime);
    result.put("dbPoolActive", dbPoolActive);
    result.put("redisStatus", redisStatus);
    result.put("activeUsers", activeUsers);
    result.put("timestamp", System.currentTimeMillis());
    return ApiResponse.success(result);
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
