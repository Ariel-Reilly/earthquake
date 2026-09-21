package cn.disasterguard.common.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cn.disasterguard.auth.jwt.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtAuthenticationFilterTest {

  private JwtAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    filter = new JwtAuthenticationFilter(mock(JwtService.class));
  }

  @Test
  @DisplayName("WebSocket 握手路径应跳过 JWT 过滤")
  void shouldNotFilter_websocketPaths() {
    assertTrue(filter.shouldNotFilter(createRequest("/ws")));
    assertTrue(filter.shouldNotFilter(createRequest("/ws/connect")));
    assertTrue(filter.shouldNotFilter(createRequest("/ws/topic/warnings")));
  }

  @Test
  @DisplayName("匿名登录端点应跳过 JWT 过滤")
  void shouldNotFilter_anonymousEndpoints() {
    assertTrue(filter.shouldNotFilter(createRequest("/api/auth/login")));
    assertTrue(filter.shouldNotFilter(createRequest("/api/auth/logout")));
    assertTrue(filter.shouldNotFilter(createRequest("/api/system/health")));
  }

  @Test
  @DisplayName("Swagger 路径应跳过 JWT 过滤")
  void shouldNotFilter_swaggerPaths() {
    assertTrue(filter.shouldNotFilter(createRequest("/swagger-ui/index.html")));
    assertTrue(filter.shouldNotFilter(createRequest("/v3/api-docs")));
    assertTrue(filter.shouldNotFilter(createRequest("/swagger-ui/swagger-ui.css")));
  }

  @Test
  @DisplayName("需要鉴权的端点应执行 JWT 过滤")
  void shouldNotFilter_protectedEndpoints() {
    assertFalse(filter.shouldNotFilter(createRequest("/api/hazards")));
    assertFalse(filter.shouldNotFilter(createRequest("/api/devices")));
    assertFalse(filter.shouldNotFilter(createRequest("/api/warnings")));
    assertFalse(filter.shouldNotFilter(createRequest("/api/auth/change-password")));
    assertFalse(filter.shouldNotFilter(createRequest("/api/auth/ws-ticket")));
    assertFalse(filter.shouldNotFilter(createRequest("/api/users")));
  }

  /**
   * 创建测试用的 HttpServletRequest mock。
   * 注：这里使用纯 mockito mock，因为只需要验证 shouldNotFilter 逻辑。
   */
  private HttpServletRequest createRequest(String uri) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    org.mockito.Mockito.when(request.getRequestURI()).thenReturn(uri);
    return request;
  }
}
