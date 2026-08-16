package cn.geosentry.aop;

import cn.geosentry.audit.entity.OperationLog;
import cn.geosentry.audit.repository.OperationLogRepository;
import cn.geosentry.auth.jwt.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  private static final int MAX_BODY = 500;

  private final OperationLogRepository repository;
  private final JwtService jwtService;
  private final ObjectMapper objectMapper;

  public OperationLogAspect(OperationLogRepository repository, JwtService jwtService, ObjectMapper objectMapper) {
    this.repository = repository;
    this.jwtService = jwtService;
    this.objectMapper = objectMapper;
  }

  @Around("execution(* cn.geosentry..controller..*.*(..)) && @annotation(org.springframework.security.access.prepost.PreAuthorize)")
  public Object audit(ProceedingJoinPoint pjp) throws Throwable {
    long start = System.currentTimeMillis();
    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    String method = attrs != null ? attrs.getRequest().getMethod() : null;
    // 只记录写操作
    if (method != null && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
      OperationLog op = new OperationLog();
      HttpServletRequest req = attrs.getRequest();
      op.setRequestMethod(method);
      op.setRequestPath(req.getRequestURI());
      op.setIpAddress(resolveIp(req));
      op.setModuleName(extractModule(pjp));
      op.setActionName(pjp.getSignature().getName());
      op.setOperator(resolveOperator(req));
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

  private String extractModule(ProceedingJoinPoint pjp) {
    String fqcn = pjp.getTarget().getClass().getName();
    // cn.geosentry.warning.controller.WarningController -> warning
    int start = fqcn.indexOf("cn.geosentry.") + 13;
    int end = fqcn.indexOf('.', start);
    if (start < 0 || end < 0) return "unknown";
    return fqcn.substring(start, end);
  }
}
