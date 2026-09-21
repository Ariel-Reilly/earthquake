package cn.disasterguard.common.security;

import cn.disasterguard.auth.jwt.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

  /**
   * 把当前请求对应的原始 JWT 字符串写入该 request attribute，
   * 便于 {@code AuthController.change-password} 等需要主动吊销当前 token 的控制器读取。
   * 键名用 {@code disasterguard.*} 前缀避免与其他 filter 冲突。
   */
  public static final String CURRENT_JWT_ATTR = "disasterguard.auth.currentJwt";

  private final JwtService jwtService;

  public JwtAuthenticationFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    // WebSocket 握手路径由 JwtHandshakeInterceptor 在握手层鉴权，Spring Security 放行
    if (path.startsWith("/ws")) {
      return true;
    }
    // 只放行匿名端点；/api/auth/change-password、/api/auth/ws-ticket 等需要 JWT 身份，
    // 必须在过滤器内解析 token 注入 SecurityContext，否则 SecurityConfig 的
    // anyRequest().authenticated() 会拦截这些接口（改密/WS ticket 将永远 401）。
    return path.equals("/api/auth/login")
        || path.equals("/api/auth/logout")
        || path.startsWith("/swagger-ui")
        || path.startsWith("/v3/api-docs")
        || path.equals("/api/system/health");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    // 仅当本过滤器实际注入了 Authentication 时，才在 finally 中清空上下文；
    // 否则会把 DeviceAuthFilter 等下游过滤器设置的身份一并擦掉，导致设备数据上报永远 401。
    boolean authSetHere = false;
    if (header != null && header.startsWith("Bearer ")) {
      try {
        String token = header.substring(7).trim();
        var claims = jwtService.parseToken(token);
        String username = claims.getSubject();
        String role = Objects.toString(claims.get("role"), "USER");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        for (String permission : PermissionRegistry.permissionsOf(role)) {
          authorities.add(new SimpleGrantedAuthority(permission));
        }
        UserDetails principal = User.withUsername(username).password("").authorities(authorities).build();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // 暴露 raw token 给下游控制器（如 change-password 主动吊销当前会话）。
        request.setAttribute(CURRENT_JWT_ATTR, token);
        authSetHere = true;
      } catch (Exception ex) {
        // 仅捕获 token 解析/校验异常，返回 401。
        // 不要把 filterChain.doFilter 放进此 try：下游控制器异常会被误判为 401，
        // 触发前端 redirectToLogin 把用户踢回登录页，且真实错误被掩盖无法排查。
        log.warn("JWT 验证失败 {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"登录状态已过期，请重新登录\"}");
        return;
      }
    }
    try {
      filterChain.doFilter(request, response);
    } finally {
      // 只清理由本过滤器设置的 Authentication，避免误清其他过滤器（如 DeviceAuthFilter）设置的身份。
      if (authSetHere) {
        SecurityContextHolder.clearContext();
      }
    }
  }
}
