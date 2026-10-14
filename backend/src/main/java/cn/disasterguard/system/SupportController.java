package cn.disasterguard.system;

import cn.disasterguard.common.api.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帮助中心 / 用户支持。
 *
 * <p>前端 HelpCenterPage 依赖：{@code POST /api/support/feedback}。
 *
 * <p>实现说明：项目当前无独立的用户反馈表，反馈内容通过本端点落库到统一操作审计
 * （{@link cn.disasterguard.aop.OperationLogAspect} 自动记录 POST 请求体到
 * {@code operation_log}），管理员可在「日志审计」页按 {@code module_name = support}
 * 查询。这是"真实落库、可追溯"的最小实现，避免为了一个反馈表单新增表结构。
 *
 * <p>若后续需要用户侧查看反馈处理状态，再引独立 feedback 表。
 */
@RestController
@RequestMapping("/api/support")
public class SupportController {

  private static final Logger log = LoggerFactory.getLogger(SupportController.class);

  /** 反馈正文最大长度（与 operation_log.request_body 的 500 字符截断策略对齐）。 */
  private static final int MAX_CONTENT_LENGTH = 500;

  /** 允许的反馈类型。 */
  private static final java.util.Set<String> ALLOWED_TYPES =
      java.util.Set.of("question", "bug", "suggestion", "other");

  /**
   * 提交用户反馈。
   *
   * <p>POST /api/support/feedback，请求体：
   * <pre>
   * { "type": "question|bug|suggestion|other",
   *   "subject": "标题（可选）",
   *   "content": "正文（必填）",
   *   "contact": "联系方式（可选）" }
   * </pre>
   *
   * <p>返回体含 {@code ticketId}（受理号，供用户后续查询）与 {@code receivedAt}。
   */
  @PostMapping("/feedback")
  @PreAuthorize("hasAuthority('settings:view')")
  public ApiResponse<Map<String, Object>> submitFeedback(@RequestBody Map<String, Object> body) {
    String content = str(body.get("content"));
    if (content == null || content.isBlank()) {
      return ApiResponse.fail(400, "反馈内容不能为空");
    }
    if (content.length() > MAX_CONTENT_LENGTH) {
      content = content.substring(0, MAX_CONTENT_LENGTH);
    }

    String type = str(body.get("type"));
    if (type == null || !ALLOWED_TYPES.contains(type)) {
      type = "other";
    }
    String subject = str(body.get("subject"));
    String contact = str(body.get("contact"));

    String operator = currentOperator();
    String ticketId = "FB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    // operation_log 由切面自动落库（记录 operator / module / request_body）。
    // 这里额外打一条业务日志，便于按受理号检索。
    log.info(
        "用户反馈受理 受理号={} 类型={} 提交人={} 标题={} 联系方式={} 正文长度={}",
        ticketId, type, operator, subject, contact, content.length());

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ticketId", ticketId);
    result.put("type", type);
    result.put("receivedAt", java.time.OffsetDateTime.now().toString());
    result.put("message", "反馈已受理，管理员可在日志审计中查看");
    return ApiResponse.success(result);
  }

  /** 取当前登录用户名；不信任请求体。 */
  private String currentOperator() {
    try {
      var auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
        return auth.getName();
      }
    } catch (Exception e) {
      log.debug("无法从 SecurityContext 获取当前用户名: {}", e.getMessage());
    }
    return "anonymous";
  }

  private String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }
}
