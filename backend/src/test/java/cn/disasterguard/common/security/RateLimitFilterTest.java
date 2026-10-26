package cn.disasterguard.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;

class RateLimitFilterTest {

  private RateLimitFilter filter;

  @BeforeEach
  void setUp() {
    filter = new RateLimitFilter();
  }

  @Test
  @DisplayName("公开端点应启用限流")
  void shouldNotFilter_publicEndpoints() {
    // shouldNotFilter 返回 true 表示"跳过本过滤器"。公开端点恰恰是要限流的，
    // 因此这里必须断言 false；此前断言为 true，与 DisplayName 的意图正好相反。
    assertEquals(false, filter.shouldNotFilter(createRequest("/api/weather/now")));
    assertEquals(false, filter.shouldNotFilter(createRequest("/api/sentinel/coverage")));
    assertEquals(false, filter.shouldNotFilter(createRequest("/swagger-ui/index.html")));
    assertEquals(false, filter.shouldNotFilter(createRequest("/v3/api-docs")));
  }

  @Test
  @DisplayName("非公开端点应跳过限流")
  void shouldNotFilter_nonPublicEndpoints() {
    // 非公开路径已由 JWT 鉴权保护，无需额外限流，shouldNotFilter 应为 true（跳过）。
    assertEquals(true, filter.shouldNotFilter(createRequest("/api/hazards")));
  }

  @Test
  @DisplayName("超出限流阈值应返回 429")
  void tooManyRequests_returns429() throws Exception {
    String clientIp = "192.168.1.100";
    // 使用 sentinel 端点（每分钟 30）
    for (int i = 0; i < 30; i++) {
      HttpServletRequest req = createRequest("/api/sentinel/coverage");
      HttpServletResponse res = mock(HttpServletResponse.class);
      when(req.getRemoteAddr()).thenReturn(clientIp);
      when(req.getHeader("X-Forwarded-For")).thenReturn(null);
      when(req.getHeader("X-Real-IP")).thenReturn(null);
      StringWriter sw = new StringWriter();
      when(res.getWriter()).thenReturn(new PrintWriter(sw));
      MockFilterChain chain = new MockFilterChain();
      filter.doFilter(req, res, chain);
    }

    // 第 31 次请求应被限流
    HttpServletRequest req = createRequest("/api/sentinel/coverage");
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(req.getRemoteAddr()).thenReturn(clientIp);
    StringWriter sw = new StringWriter();
    when(res.getWriter()).thenReturn(new PrintWriter(sw));
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(req, res, chain);

    // 验证状态码为 429 或响应包含限流信息
    // 注：mock 设置状态码需要额外的 mockito stubbing
    assertTrue(sw.toString().contains("429") || sw.toString().contains("请求过于频繁"));
  }

  @Test
  @DisplayName("不同 IP 应独立计数")
  void differentIpsHaveSeparateCounters() throws Exception {
    // IP1 用满 sentinel 配额
    for (int i = 0; i < 30; i++) {
      HttpServletRequest req = createRequest("/api/sentinel/coverage");
      HttpServletResponse res = mock(HttpServletResponse.class);
      when(req.getRemoteAddr()).thenReturn("10.0.0.1");
      filter.doFilter(req, res, new MockFilterChain());
    }

    // IP2 仍可请求
    HttpServletRequest req = createRequest("/api/sentinel/coverage");
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(req.getRemoteAddr()).thenReturn("10.0.0.2");
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(req, res, chain);
    // MockFilterChain 只有在被放行后才会记录 request；能取到 request 就说明未被限流。
    assertNotNull(chain.getRequest(), "IP2 不应被 IP1 的计数影响");
  }

  /**
   * 创建测试用的 HttpServletRequest mock。
   */
  private HttpServletRequest createRequest(String uri) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn(uri);
    return request;
  }
}
