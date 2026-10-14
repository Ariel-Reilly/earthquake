package cn.disasterguard.aop;

import cn.disasterguard.audit.entity.OperationLog;
import cn.disasterguard.audit.repository.OperationLogRepository;
import cn.disasterguard.auth.jwt.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 统一操作审计切面。对所有 Controller 写方法（POST/PUT/DELETE）自动落 operation_log。
 *
 * <p>仅记录非敏感字段：请求体截断到 500 字符，密码/密钥等敏感属性不写入。
 */
@Aspect
@Component
public class OperationLogAspect {

  private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);

  /** 项目根包前缀（含结尾的点）。 */
  private static final String ROOT_PACKAGE = "cn.disasterguard.";

  /** 请求体记录上限。 */
  private static final int BODY_MAX_LEN = 500;

  /** 敏感字段：命中即整段脱敏，避免明文落库。 */
  private static final java.util.regex.Pattern SENSITIVE_KEY =
      java.util.regex.Pattern.compile(
          "(?i)\"(password|passwd|pwd|secret|token|accessKey|secretKey|access_key|secret_key)\"\\s*:\\s*\"[^\"]*\"");

  private final OperationLogRepository repository;
  private final JwtService jwtService;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  public OperationLogAspect(
      OperationLogRepository repository,
      JwtService jwtService,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.repository = repository;
    this.jwtService = jwtService;
    this.objectMapper = objectMapper;
  }

  /**
   * 对所有 Controller 方法进行审计，在切面内部跳过 GET/HEAD 读操作。
   * 去掉了 @PreAuthorize 注解依赖，覆盖所有写操作 Controller（即使没显式加权限注解也会被审计）。
   *
   * <p>切点匹配策略：项目 Controller 直接放在各业务包下（如 cn.disasterguard.warning.WarningController），
   * 不使用中间 controller 子包。因此按类名后缀 {@code *Controller} 匹配，避免遗漏任何控制器。
   */
  @Around("execution(* cn.disasterguard..*Controller.*(..))")
  public Object audit(ProceedingJoinPoint pjp) throws Throwable {
    long start = System.currentTimeMillis();
    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return pjp.proceed();
    }
    HttpServletRequest req = attrs.getRequest();
    String method = req.getMethod();
    // 只记录写操作
    if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
      OperationLog op = new OperationLog();
      op.setRequestMethod(method);
      op.setRequestPath(req.getRequestURI());
      op.setIpAddress(resolveIp(req));
      op.setModuleName(extractModule(pjp));
      op.setActionName(pjp.getSignature().getName());
      op.setOperator(resolveOperator(req));
      op.setRequestBody(extractRequestBody(pjp));
      op.setCreatedAt(OffsetDateTime.now());
      try {
        Object result = pjp.proceed();
        op.setResultStatus("SUCCESS");
        op.setCostMs(System.currentTimeMillis() - start);
        saveAsync(op);
        return result;
      } catch (Throwable t) {
        op.setResultStatus("FAIL: " + t.getClass().getSimpleName());
        op.setCostMs(System.currentTimeMillis() - start);
        saveAsync(op);
        throw t;
      }
    }
    return pjp.proceed();
  }

  private void saveAsync(OperationLog op) {
    try {
      repository.save(op);
    } catch (Exception ex) {
      // 审计失败不影响主流程
      log.debug("操作日志写入失败（忽略）: {}", ex.getMessage());
    }
  }

  private String resolveIp(HttpServletRequest req) {
    String xff = req.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
    return req.getRemoteAddr();
  }

  private String resolveOperator(HttpServletRequest req) {
    String auth = req.getHeader("Authorization");
    if (auth != null && auth.startsWith("Bearer ")) {
      try {
        return jwtService.parseToken(auth.substring(7)).getSubject();
      } catch (Exception ignored) {
        // token 无效就不记录操作员
        log.debug("解析 JWT 提取操作员失败（跳过）: {}", ignored.getMessage());
      }
    }
    return "anonymous";
  }

  /**
   * 提取并序列化请求体，用于审计留痕。
   *
   * <p>约束：
   * <ul>
   *   <li>只记录第一个参数的序列化结果（@RequestBody 参数通常为唯一参数）；</li>
   *   <li>基本类型 / 字符串 / Map / 自定义 DTO 走 JSON 序列化，Servlet 对象（HttpServletRequest 等）直接跳过；</li>
   *   <li>序列化失败（循环引用等）降级为 {@code toString()}；再失败则记为 {@code String.valueOf} 简写；</li>
   *   <li>命中敏感字段正则的 JSON 键值被替换为 {@code ***}；</li>
   *   <li>超过 {@link #BODY_MAX_LEN} 字符截断加省略号。</li>
   * </ul>
   *
   * @return 可落库的请求体字符串；无可序列化参数时返回 {@code null}
   */
  private String extractRequestBody(ProceedingJoinPoint pjp) {
    Object[] args = pjp.getArgs();
    if (args == null || args.length == 0 || args[0] == null) {
      return null;
    }
    Object arg = args[0];
    // Servlet 容器对象不参与落库（无法且无需序列化）
    if (arg instanceof jakarta.servlet.ServletRequest
        || arg instanceof jakarta.servlet.ServletResponse
        || arg instanceof org.springframework.web.multipart.MultipartFile) {
      return null;
    }

    String text;
    try {
      text = objectMapper.writeValueAsString(arg);
    } catch (Exception ex) {
      try {
        text = String.valueOf(arg);
      } catch (Exception inner) {
        return null;
      }
    }
    if (text == null) {
      return null;
    }

    // 敏感字段脱敏
    text = SENSITIVE_KEY.matcher(text).replaceAll("\"$1\":\"***\"");

    // 截断
    if (text.length() > BODY_MAX_LEN) {
      text = text.substring(0, BODY_MAX_LEN) + "...(truncated)";
    }
    return text;
  }

  private String extractModule(ProceedingJoinPoint pjp) {
    String fqcn = pjp.getTarget().getClass().getName();
    // cn.disasterguard.warning.WarningController -> warning
    int idx = fqcn.indexOf(ROOT_PACKAGE);
    if (idx < 0) return "unknown";
    int start = idx + ROOT_PACKAGE.length();
    int end = fqcn.indexOf('.', start);
    if (end < 0) return "unknown";
    return fqcn.substring(start, end);
  }
}
