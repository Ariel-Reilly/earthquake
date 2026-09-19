package cn.disasterguard.auth;

import cn.disasterguard.common.api.ApiResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账户安全（Account Security）REST API。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET /api/account/security} — 安全设置</li>
 *   <li>{@code POST /api/account/2fa} — 切换两步验证</li>
 *   <li>{@code GET /api/account/login-history} — 登录历史</li>
 *   <li>{@code GET /api/account/sessions} — 活跃会话</li>
 *   <li>{@code DELETE /api/account/sessions/{sessionId}} — 撤销会话</li>
 *   <li>{@code GET /api/account/audit} — 账户审计日志</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/account")
public class AccountSecurityController {

  private static final AtomicLong HISTORY_ID_GEN = new AtomicLong(1000);
  private static final AtomicLong SESSION_ID_GEN = new AtomicLong(1000);
  private static final AtomicLong AUDIT_ID_GEN = new AtomicLong(1000);

  private final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();
  private final Map<Long, Map<String, Object>> loginHistory = new ConcurrentHashMap<>();
  private final Map<Long, Map<String, Object>> auditLog = new ConcurrentHashMap<>();

  public AccountSecurityController() {
    initSampleData();
  }

  private void initSampleData() {
    // 初始化登录历史
    String[] ips = {"192.168.1.100", "10.0.0.50", "172.16.0.25"};
    String[] devices = {"Chrome/Windows", "Safari/macOS", "Chrome/Android"};
    String[] locations = {"北京", "上海", "广州"};
    String[] statuses = {"SUCCESS", "SUCCESS", "FAILED"};
    for (int i = 0; i < 5; i++) {
      long id = HISTORY_ID_GEN.incrementAndGet();
      Map<String, Object> history = new LinkedHashMap<>();
      history.put("id", id);
      history.put("ip", ips[i % 3]);
      history.put("device", devices[i % 3]);
      history.put("location", locations[i % 3]);
      history.put("loggedInAt", OffsetDateTime.now().minusDays(i));
      history.put("loggedOutAt", i < 3 ? OffsetDateTime.now().minusDays(i).plusHours(2) : null);
      history.put("status", statuses[i % 3]);
      loginHistory.put(id, history);
    }

    // 初始化活跃会话
    for (int i = 0; i < 2; i++) {
      long sessionId = SESSION_ID_GEN.incrementAndGet();
      Map<String, Object> session = new LinkedHashMap<>();
      session.put("sessionId", "sess_" + sessionId);
      session.put("device", i == 0 ? "Chrome/Windows" : "Mobile App/iOS");
      session.put("ip", i == 0 ? "192.168.1.100" : "10.0.0.25");
      session.put("location", i == 0 ? "北京" : "深圳");
      session.put("loggedInAt", OffsetDateTime.now().minusHours(i + 1));
      session.put("lastActive", OffsetDateTime.now().minusMinutes(i * 10));
      sessions.put(String.valueOf(sessionId), session);
    }

    // 初始化审计日志
    String[] actions = {"修改密码", "启用两步验证", "登录", "退出登录", "修改手机号"};
    for (int i = 0; i < 5; i++) {
      long id = AUDIT_ID_GEN.incrementAndGet();
      Map<String, Object> audit = new LinkedHashMap<>();
      audit.put("id", id);
      audit.put("action", actions[i % 5]);
      audit.put("detail", actions[i % 5] + "操作完成");
      audit.put("ip", "192.168.1." + (100 + i));
      audit.put("timestamp", OffsetDateTime.now().minusHours(i * 3));
      auditLog.put(id, audit);
    }
  }

  @GetMapping("/security")
  public ApiResponse<Map<String, Object>> getSecuritySettings() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("username", "admin");
    result.put("email", "admin@example.com");
    result.put("phone", "138****8888");
    result.put("twoFactorEnabled", false);
    result.put("lastPasswordChange", OffsetDateTime.now().minusDays(30));
    result.put("securityLevel", "MEDIUM");
    return ApiResponse.success(result);
  }

  @PostMapping("/2fa")
  public ApiResponse<Map<String, Object>> toggle2fa(@RequestBody Map<String, Object> body) {
    boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
    String method = String.valueOf(body.getOrDefault("method", "APP"));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("enabled", enabled);
    result.put("method", enabled ? method : null);
    return ApiResponse.success(result);
  }

  /** 别名：/security/2fa（前端 doc 路径兼容）。 */
  @PostMapping("/security/2fa")
  public ApiResponse<Map<String, Object>> toggle2faAlias(@RequestBody Map<String, Object> body) {
    return toggle2fa(body);
  }

  /** 重新生成 2FA 备用恢复码。 */
  @PostMapping("/security/backup-codes/regenerate")
  public ApiResponse<Map<String, Object>> regenerateBackupCodes() {
    java.util.List<String> codes = new java.util.ArrayList<>();
    for (int i = 0; i < 10; i++) {
      codes.add(java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("codes", codes);
    result.put("generatedAt", OffsetDateTime.now());
    result.put("count", codes.size());
    return ApiResponse.success("备用码已重新生成，请妥善保管", result);
  }

  @GetMapping("/login-history")
  public ApiResponse<Map<String, Object>> getLoginHistory(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    var all = loginHistory.values().stream()
        .sorted((a, b) -> {
          var ta = (OffsetDateTime) b.get("loggedInAt");
          var tb = (OffsetDateTime) a.get("loggedInAt");
          return ta.compareTo(tb);
        })
        .toList();

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);
    int total = all.size();
    int fromIndex = Math.min((safePage - 1) * safeSize, total);
    int toIndex = Math.min(fromIndex + safeSize, total);
    List<Map<String, Object>> items = all.subList(fromIndex, toIndex);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("total", total);
    return ApiResponse.success(result);
  }

  @GetMapping("/sessions")
  public ApiResponse<List<Map<String, Object>>> getSessions() {
    return ApiResponse.success(new ArrayList<>(sessions.values()));
  }

  @DeleteMapping("/sessions/{sessionId}")
  public ApiResponse<Map<String, Object>> revokeSession(@PathVariable String sessionId) {
    if (!sessions.containsKey(sessionId)) {
      return ApiResponse.fail(404, "会话不存在: " + sessionId);
    }
    sessions.remove(sessionId);
    return ApiResponse.success(Map.of("revoked", sessionId));
  }

  @GetMapping("/audit")
  public ApiResponse<Map<String, Object>> getAuditLog(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String type) {
    var all = auditLog.values().stream()
        .filter(a -> type == null || type.isBlank()
            || type.equals(a.get("action")))
        .sorted((a, b) -> {
          var ta = (OffsetDateTime) b.get("timestamp");
          var tb = (OffsetDateTime) a.get("timestamp");
          return ta.compareTo(tb);
        })
        .toList();

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);
    int total = all.size();
    int fromIndex = Math.min((safePage - 1) * safeSize, total);
    int toIndex = Math.min(fromIndex + safeSize, total);
    List<Map<String, Object>> items = all.subList(fromIndex, toIndex);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("total", total);
    return ApiResponse.success(result);
  }
}
