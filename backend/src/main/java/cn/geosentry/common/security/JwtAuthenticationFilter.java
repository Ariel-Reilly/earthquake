package cn.geosentry.common.security;

import cn.geosentry.auth.jwt.JwtService;
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

  private final JwtService jwtService;

  public JwtAuthenticationFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
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
      // 清理 SecurityContext：Tomcat 线程池复用时残留身份会污染后续请求（与 DeviceAuthFilter 保持一致）
      SecurityContextHolder.clearContext();
    }
  }
}
