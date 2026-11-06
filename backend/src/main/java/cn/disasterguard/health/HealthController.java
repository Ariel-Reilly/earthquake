package cn.disasterguard.health;

import cn.disasterguard.common.api.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点。
 *
 * <ul>
 *   <li>{@code GET /api/system/health} — 默认检测 PostgreSQL（关键依赖），DB 不可用时返回 503</li>
 *   <li>{@code GET /api/system/health?detailed=true} — 检测全部依赖（PostgreSQL + 运行时状态）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/system")
public class HealthController {

  private static final Logger log = LoggerFactory.getLogger(HealthController.class);

  @Autowired(required = false)
  private DataSource dataSource;

  @Value("${tdengine.enabled:false}")
  private boolean tdengineEnabled;

  @GetMapping("/health")
  public ResponseEntity<ApiResponse<Map<String, Object>>> health(
      @RequestParam(defaultValue = "false") boolean detailed) {
    Map<String, Object> body = new LinkedHashMap<>();
    Map<String, Object> details = new LinkedHashMap<>();
    boolean allOk = true;

    // 1) PostgreSQL（必检）
    boolean dbOk = checkDatabase();
    details.put("postgresql", dbOk ? "OK" : "FAIL");
    if (!dbOk) {
      allOk = false;
      log.warn("健康检查：PostgreSQL 不可用");
    }

    // 2) 详细模式下补充运行时信息
    if (detailed) {
      long usedHeapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
      long maxHeapMb = Runtime.getRuntime().maxMemory() / 1024 / 1024;
      details.put("jvm_heap_used_mb", usedHeapMb);
      details.put("jvm_heap_max_mb", maxHeapMb);
      details.put("thread_count", Thread.activeCount());
      details.put("tdengine_enabled", tdengineEnabled);
    }

    body.put("status", allOk ? "UP" : "DEGRADED");
    body.put("service", "disasterguard-backend");
    body.put("details", details);
    body.put("timestamp", java.time.OffsetDateTime.now().toString());

    if (!allOk) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(ApiResponse.fail(503, "PostgreSQL 不可用"));
    }
    return ResponseEntity.ok(ApiResponse.success("ok", body));
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
}
