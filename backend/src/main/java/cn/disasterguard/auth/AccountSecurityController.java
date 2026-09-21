package cn.disasterguard.auth;

import cn.disasterguard.auth.entity.AccountAuditEntry;
import cn.disasterguard.auth.entity.LoginHistoryEntry;
import cn.disasterguard.auth.entity.LoginSession;
import cn.disasterguard.auth.repository.AccountAuditEntryRepository;
import cn.disasterguard.auth.repository.LoginHistoryEntryRepository;
import cn.disasterguard.auth.repository.LoginSessionRepository;
import cn.disasterguard.common.api.ApiResponse;
import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

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
 *
 * <p>数据持久化：sessions / loginHistory / auditLog 三个内存 Map 已改为
 * JSONB 落库（login_session / login_history_entry / account_audit_entry），
 * 端点路径与响应结构保持不变。
 */
@RestController
@RequestMapping("/api/account")
public class AccountSecurityController {

  /** 种子数据起始 id，与原内存版 AtomicLong(1000) + incrementAndGet 的首值一致。 */
  private static final long SEED_ID_BASE = 1000;

  private final LoginSessionRepository sessionRepository;
  private final LoginHistoryEntryRepository loginHistoryRepository;
  private final AccountAuditEntryRepository auditLogRepository;

  private long historyIdGen;
  private long auditIdGen;

  public AccountSecurityController(
      LoginSessionRepository sessionRepository,
      LoginHistoryEntryRepository loginHistoryRepository,
      AccountAuditEntryRepository auditLogRepository) {
    this.sessionRepository = sessionRepository;
    this.loginHistoryRepository = loginHistoryRepository;
    this.auditLogRepository = auditLogRepository;
  }

  @PostConstruct
  void init() {
    if (loginHistoryRepository.count() == 0) {
      String[] ips = {"192.168.1.100", "10.0.0.50", "172.16.0.25"};
      String[] devices = {"Chrome/Windows", "Safari/macOS", "Chrome/Android"};
      String[] locations = {"北京", "上海", "广州"};
      String[] statuses = {"SUCCESS", "SUCCESS", "FAILED"};
      for (int i = 0; i < 5; i++) {
        long id = SEED_ID_BASE + i + 1;
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("id", id);
        history.put("ip", ips[i % 3]);
        history.put("device", devices[i % 3]);
        history.put("location", locations[i % 3]);
        history.put("loggedInAt", OffsetDateTime.now().minusDays(i).toString());
        history.put("loggedOutAt",
            i < 3 ? OffsetDateTime.now().minusDays(i).plusHours(2).toString() : null);
        history.put("status", statuses[i % 3]);
        LoginHistoryEntry entry = new LoginHistoryEntry();
        entry.setId(id);
        entry.setPayload(history);
        loginHistoryRepository.save(entry);
      }
    }

    if (sessionRepository.count() == 0) {
      String[] devices = {"Chrome/Windows", "Mobile App/iOS"};
      String[] ips = {"192.168.1.100", "10.0.0.25"};
      String[] locations = {"北京", "深圳"};
      for (int i = 0; i < 2; i++) {
        long sessionId = SEED_ID_BASE + i + 1;
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("sessionId", "sess_" + sessionId);
        session.put("device", devices[i]);
        session.put("ip", ips[i]);
        session.put("location", locations[i]);
        session.put("loggedInAt", OffsetDateTime.now().minusHours(i + 1).toString());
        session.put("lastActive", OffsetDateTime.now().minusMinutes(i * 10).toString());
        LoginSession entity = new LoginSession();
        entity.setId(String.valueOf(sessionId));
        entity.setPayload(session);
        sessionRepository.save(entity);
      }
    }

    if (auditLogRepository.count() == 0) {
      String[] actions = {"修改密码", "启用两步验证", "登录", "退出登录", "修改手机号"};
      for (int i = 0; i < 5; i++) {
        long id = SEED_ID_BASE + i + 1;
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("id", id);
        audit.put("action", actions[i % 5]);
        audit.put("detail", actions[i % 5] + "操作完成");
        audit.put("ip", "192.168.1." + (100 + i));
        audit.put("timestamp", OffsetDateTime.now().minusHours(i * 3).toString());
        AccountAuditEntry entry = new AccountAuditEntry();
        entry.setId(id);
        entry.setPayload(audit);
        auditLogRepository.save(entry);
      }
    }

    historyIdGen = loginHistoryRepository.findMaxId();
    auditIdGen = auditLogRepository.findMaxId();
  }

  @GetMapping("/security")
  @PreAuthorize("hasAuthority('settings:view')")
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
  @PreAuthorize("hasAuthority('settings:edit')")
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
  @PreAuthorize("hasAuthority('settings:edit')")
  public ApiResponse<Map<String, Object>> toggle2faAlias(@RequestBody Map<String, Object> body) {
    return toggle2fa(body);
  }

  /** 重新生成 2FA 备用恢复码。 */
  @PostMapping("/security/backup-codes/regenerate")
  @PreAuthorize("hasAuthority('settings:edit')")
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
  @PreAuthorize("hasAuthority('settings:view')")
  public ApiResponse<Map<String, Object>> getLoginHistory(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    // payload 中 loggedInAt 为 ISO 字符串，解析后按时间倒序（与原内存版一致）
    var all = loginHistoryRepository.findAll().stream()
        .map(LoginHistoryEntry::getPayload)
        .sorted((a, b) -> {
          var ta = OffsetDateTime.parse(String.valueOf(b.get("loggedInAt")));
          var tb = OffsetDateTime.parse(String.valueOf(a.get("loggedInAt")));
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
  @PreAuthorize("hasAuthority('settings:view')")
  public ApiResponse<List<Map<String, Object>>> getSessions() {
    List<Map<String, Object>> items = new ArrayList<>();
    for (LoginSession session : sessionRepository.findAll()) {
      items.add(session.getPayload());
    }
    return ApiResponse.success(items);
  }

  @DeleteMapping("/sessions/{sessionId}")
  @PreAuthorize("hasAuthority('settings:edit')")
  public ApiResponse<Map<String, Object>> revokeSession(@PathVariable String sessionId) {
    var existing = sessionRepository.findById(sessionId);
    if (existing.isEmpty()) {
      return ApiResponse.fail(404, "会话不存在: " + sessionId);
    }
    sessionRepository.delete(existing.get());
    return ApiResponse.success(Map.of("revoked", sessionId));
  }

  @GetMapping("/audit")
  @PreAuthorize("hasAuthority('settings:view')")
  public ApiResponse<Map<String, Object>> getAuditLog(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String type) {
    // payload 中 timestamp 为 ISO 字符串，解析后按时间倒序（与原内存版一致）
    var all = auditLogRepository.findAll().stream()
        .map(AccountAuditEntry::getPayload)
        .filter(a -> type == null || type.isBlank()
            || type.equals(a.get("action")))
        .sorted(Comparator.comparing(
            (Map<String, Object> a) ->
                OffsetDateTime.parse(String.valueOf(a.get("timestamp")))).reversed())
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
