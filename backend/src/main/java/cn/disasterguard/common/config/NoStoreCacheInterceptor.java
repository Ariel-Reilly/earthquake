package cn.disasterguard.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 防止前端浏览器（包括反向代理/网关）缓存任何 API 响应。
 *
 * <p>前端业务页面跳进跳出时，浏览器默认会复用同一 URL 的 GET 响应缓存，
 * 导致"看着像需要手动刷新"的问题。前端虽然已经在
 * {@code frontend/src/services/_architecture/core/httpClient.js}
 * 给 fetch 加上 {@code cache: "no-store"}，但拦截器是更稳妥的兜底：
 *
 * <ul>
 *   <li>任何绕过 httpClient 的脚本（控制台 / 第三方组件）也会被保护；</li>
 *   <li>经 Nginx / CDN 等反向代理回源时也能立即失效本地/边缘缓存；</li>
 *   <li>POST/PUT/PATCH/DELETE 等非幂等请求不会被错误缓存。</li>
 * </ul>
 *
 * <p>只作用于 {@code /api/**} 路径下的响应，避免影响静态资源（Swagger、
 * 上传文件、SSE 流式推送等）的缓存策略。
 */
@Component
public class NoStoreCacheInterceptor implements HandlerInterceptor {

  private static final String CACHE_CONTROL_VALUE = "no-store, no-cache, must-revalidate, max-age=0";
  private static final String API_PREFIX = "/api/";

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String path = request.getRequestURI();
    if (path == null || !path.startsWith(API_PREFIX)) {
      return true;
    }
    // 仅干预可缓存的 GET/HEAD，其它写方法本身不会被缓存，但显式覆盖更稳妥
    String method = request.getMethod();
    boolean relevant = HttpMethod.GET.matches(method)
        || HttpMethod.HEAD.matches(method)
        || HttpMethod.POST.matches(method)
        || HttpMethod.PUT.matches(method)
        || HttpMethod.PATCH.matches(method)
        || HttpMethod.DELETE.matches(method);
    if (!relevant) {
      return true;
    }
    response.setHeader("Cache-Control", CACHE_CONTROL_VALUE);
    response.setHeader("Pragma", "no-cache");
    response.setDateHeader("Expires", 0L);
    return true;
  }
}
