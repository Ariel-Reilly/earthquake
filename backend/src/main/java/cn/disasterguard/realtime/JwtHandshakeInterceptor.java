package cn.disasterguard.realtime;

import cn.disasterguard.auth.jwt.JwtService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 握手鉴权拦截器。
 *
 * <p>支持两种鉴权模式（推荐第一种）：
 * <ol>
 *   <li>一次性 ticket：客户端先用 JWT 调 {@code GET /api/auth/ws-ticket} 获取 ticket，
 *       然后用 {@code ws://host/ws?ticket=xxx} 连接。ticket 60s TTL，一次性消费，
 *       反向代理/服务器日志里只看到随机 token，无泄露风险。</li>
 *   <li>原始 JWT：兼容旧客户端，直接 {@code ws://host/ws?token=eyJhbGci...}。
 *       注意：浏览器 WebSocket API 不支持自定义 Header，只能放 query，
 *       token 会出现在服务器日志中。</li>
 * </ol>
 *
 * <p>握手校验失败返回 401 并拒绝升级到 WebSocket。成功后注入 Authentication，
 * 供后续业务代码按需使用。
 */
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

  private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);

  private final JwtService jwtService;
  private final WsTicketStore ticketRepository;
  private final boolean requireTicketOnly;

  public JwtHandshakeInterceptor(JwtService jwtService, WsTicketStore ticketRepository,
                                 @Value("${disasterguard.ws.require-ticket:false}") boolean requireTicketOnly) {
    this.jwtService = jwtService;
    this.ticketRepository = ticketRepository;
    this.requireTicketOnly = requireTicketOnly;
  }

  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
      WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
    QueryParams params = parseQuery(request.getURI().toString());

    // 优先尝试一次性 ticket（更安全）
    if (params.ticket != null && !params.ticket.isBlank()) {
      var ticket = ticketRepository.consume(params.ticket);
      if (ticket == null) {
        log.warn("WebSocket 握手拒绝：ticket 无效/已过期/已使用, remote={}", request.getRemoteAddress());
        response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
        return false;
      }
      return injectAuth(ticket.username(), ticket.role(), request, attributes);
    }

    // require-ticket 模式下禁止 raw JWT fallback
    if (requireTicketOnly) {
      log.warn("WebSocket 握手拒绝：require-ticket=true 但未提供 ticket 参数, remote={}", request.getRemoteAddress());
      response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
      return false;
    }

    // fallback：原始 JWT（兼容旧客户端）
    String token = params.token;
    if (token == null || token.isBlank()) {
      log.warn("WebSocket 握手拒绝：缺失 ticket 或 token query 参数, remote={}", request.getRemoteAddress());
      response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
      return false;
    }
    try {
      var claims = jwtService.parseToken(token);
      String username = claims.getSubject();
      String role = String.valueOf(claims.getOrDefault("role", "USER"));
      return injectAuth(username, role, request, attributes);
    } catch (Exception ex) {
      log.warn("WebSocket 握手拒绝：token 无效, reason={}, remote={}", ex.getMessage(), request.getRemoteAddress());
      response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
      return false;
    }
  }

  private boolean injectAuth(String username, String role, ServerHttpRequest request, Map<String, Object> attributes) {
    // 用户身份存入 WebSocket session attributes（WebSocket 会话不经过 Spring Security filter chain，
    // SecurityContext 在线程池复用时会污染后续 HTTP 请求，因此不在这里注入）
    attributes.put("wsUser", username);
    attributes.put("wsRole", role);
    attributes.put("wsRoleNormalized", role.toUpperCase());
    log.info("WebSocket 握手成功 user={}, role={}, remote={}", username, role, request.getRemoteAddress());
    return true;
  }

  @Override
  public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
      WebSocketHandler wsHandler, Exception exception) {
    if (exception != null) {
      log.warn("WebSocket 握手异常: {}", exception.getMessage());
    }
  }

  /** 解析 URI 的 query 参数，兼容 query 的 token/access_token/ticket。 */
  private QueryParams parseQuery(String uri) {
    QueryParams result = new QueryParams();
    int q = uri.indexOf('?');
    if (q < 0) return result;
    String query = uri.substring(q + 1);
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) continue;
      String k = pair.substring(0, eq);
      String v = pair.substring(eq + 1);
      if ("ticket".equals(k)) {
        result.ticket = v;
      } else if ("token".equals(k) || "access_token".equals(k)) {
        if (v.startsWith("Bearer ")) v = v.substring(7);
        result.token = v;
      }
    }
    return result;
  }

  private static final class QueryParams {
    String token;
    String ticket;
  }
}
