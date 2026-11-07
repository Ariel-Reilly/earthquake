package cn.disasterguard.ai;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 两个历史 KNOWN_GAPS 端点的显式 501 桩。
 *
 * <p>{@code GET /api/ai/drift} 与 {@code GET /api/ai/multimodal} 由
 * driftMonitorService.js / multiModalService.js 在「直连 ai-service 失败」时
 * 作为后端代理回退路径调用，但 Spring Boot 侧从未实现任何转发，运行时只会 404。
 * 这两个 404 长期被登记在三份门禁脚本的 KNOWN_GAPS 基线里。
 *
 * <p>此处不再让它们静默 404，而是明确返回 HTTP 501 + {@code deprecated:true}：
 * 调用方能一眼看出「未实现且已弃用」，不会把 404 误判成网关故障或接口已通。
 * 前端应直连 ai-service（默认 {@code http://localhost:8001/api/ai}）使用
 * drift / multimodal 能力；Spring Boot 侧不补演示转发，避免把演示数据包装成真实接口。
 *
 * <p>注意权限边界：这些端点沿用 {@code ai:insight:view}，**未认证调用会得到 403 而不是 501**。
 * 这是刻意的——不为已弃用端点开放匿名访问；如果你在 curl 里看到 403，先确认是否带了有效 token。
 *
 * @deprecated 这两组后端代理路径已弃用，不计划实现；请改用 ai-service 直连。
 */
@Deprecated
@RestController
@RequestMapping("/api/ai")
public class AiDeprecatedGapController {

  private static ResponseEntity<Map<String, Object>> notImplemented(String path) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "NOT_IMPLEMENTED");
    body.put("deprecated", true);
    body.put("path", path);
    body.put("message", "该后端代理端点未实现且已弃用，不要把它当作真实数据接口");
    body.put("replacement", "直连 ai-service：http://localhost:8001/api/ai/drift/* 或 /api/ai/multimodal/*");
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(body);
  }

  /** @deprecated 见类注释：保留显式 501，避免历史缺口继续以 404 形式静默存在。 */
  @Deprecated
  @GetMapping("/drift")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ResponseEntity<Map<String, Object>> drift() {
    return notImplemented("/api/ai/drift");
  }

  /** @deprecated 见类注释。 */
  @Deprecated
  @GetMapping("/multimodal")
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ResponseEntity<Map<String, Object>> multimodal() {
    return notImplemented("/api/ai/multimodal");
  }

  /**
   * 子路径兜底：前端代理回退实际请求的是 {@code /api/ai/drift/detect}、
   * {@code /api/ai/multimodal/insight/{id}} 等动态路径，同样返回 501 而非 404。
   *
   * <p>只接管 GET / POST（前端的两类实际用法）；其余动词不再被吞成 501，
   * 会正常落到 405，避免把未来在此路径下新增的真实接口一并遮蔽（R8 P2-4）。
   *
   * @deprecated 见类注释。
   */
  @Deprecated
  @RequestMapping(value = {"/drift/**", "/multimodal/**"},
      method = {RequestMethod.GET, RequestMethod.POST})
  @PreAuthorize("hasAuthority('ai:insight:view')")
  public ResponseEntity<Map<String, Object>> subPaths(HttpServletRequest request) {
    return notImplemented(request.getRequestURI());
  }
}
