package cn.geosentry.common.exception;

import cn.geosentry.ai.AiUnavailableException;
import cn.geosentry.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** AppException 业务码 → HTTP 状态码映射。未命中的回退到 400。 */
  private static final Map<Integer, HttpStatus> CODE_TO_STATUS = Map.of(
      401, HttpStatus.UNAUTHORIZED,
      403, HttpStatus.FORBIDDEN,
      404, HttpStatus.NOT_FOUND,
      409, HttpStatus.CONFLICT,
      413, HttpStatus.PAYLOAD_TOO_LARGE,
      422, HttpStatus.UNPROCESSABLE_ENTITY
  );

  @ExceptionHandler(AiUnavailableException.class)
  public ResponseEntity<ApiResponse<Void>> handleAiUnavailable(AiUnavailableException ex) {
    log.warn("AI 服务不可用: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
  }

  @ExceptionHandler(AppException.class)
  public ResponseEntity<ApiResponse<Void>> handleAppException(AppException ex) {
    // 业务码就是 HTTP 状态码（约定），但兜底做范围校验，防止非 HTTP 码误入
    int code = ex.getCode();
    HttpStatus status;
    if (code >= 400 && code < 600) {
      status = CODE_TO_STATUS.getOrDefault(code, HttpStatus.valueOf(code));
    } else {
      status = HttpStatus.BAD_REQUEST;
    }
    log.warn("业务异常 code={}: {}", code, ex.getMessage());
    return ResponseEntity.status(status).body(ApiResponse.fail(code, ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(FieldError::getDefaultMessage)
        .orElse("参数校验失败");
    log.warn("参数校验失败: {}", message);
    return ResponseEntity.badRequest().body(ApiResponse.fail(400, message));
  }

  /** 处理 @Validated 注解加在 @RequestParam/@PathVariable 上时的约束违反（如 @Min/@Max/@Pattern）。 */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
    String message = ex.getConstraintViolations().stream()
        .findFirst()
        .map(v -> v.getMessage() != null ? v.getMessage() : v.getPropertyPath() + " 校验失败")
        .orElse("参数校验失败");
    log.warn("约束校验失败: {}", message);
    return ResponseEntity.badRequest().body(ApiResponse.fail(400, message));
  }

  /** 处理必填 @RequestParam 缺失（如 /warning?keyword= 需要 page 参数）。 */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
    return ResponseEntity.badRequest().body(ApiResponse.fail(400, "缺少必填参数: " + ex.getParameterName()));
  }

  /** 处理参数类型不匹配（如 /warning/abc 期望 Long 但传了字符串）。 */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    String name = ex.getName();
    String value = ex.getValue() == null ? "null" : ex.getValue().toString();
    return ResponseEntity.badRequest().body(ApiResponse.fail(400, "参数 '" + name + "' 类型错误，值: " + value));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex, HttpServletRequest request) {
    // 未捕获异常：记录完整堆栈，便于生产排查
    log.error("未捕获异常 uri={}, method={}: ", request.getRequestURI(), request.getMethod(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.fail(500, "系统繁忙，请稍后重试"));
  }
}
