package cn.disasterguard.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局限流过滤器：保护公开端点免受滥用。
 * 
 * 限流策略：
 * - /api/weather/** - 每 IP 每分钟 60 请求
 * - /api/sentinel/** - 每 IP 每分钟 30 请求
 * - /swagger-ui/** - 每 IP 每分钟 30 请求
 * - 其他公开端点 - 每 IP 每分钟 100 请求
 */
@Component
@Order(1) // 在 JWT 过滤器之前执行
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // 滑动窗口限流：每个 IP 的请求时间戳队列
    private final Map<String, SlidingWindowRateLimiter> limiters = new ConcurrentHashMap<>();

    // 限流阈值配置（每分钟）
    private static final int WEATHER_LIMIT = 60;
    private static final int SENTINEL_LIMIT = 30;
    private static final int SWAGGER_LIMIT = 30;
    private static final int DEFAULT_LIMIT = 100;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 只对公开端点限流
        return !path.startsWith("/api/weather")
            && !path.startsWith("/api/sentinel")
            && !path.startsWith("/swagger-ui")
            && !path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String clientIp = getClientIp(request);
        String path = request.getRequestURI();
        int limit = getLimit(path);

        SlidingWindowRateLimiter limiter = limiters.computeIfAbsent(clientIp,
            k -> new SlidingWindowRateLimiter());

        if (!limiter.tryAcquire(limit)) {
            log.warn("Rate limit exceeded for IP {} on path {}", clientIp, path);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", "60");
            response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"retryAfter\":60}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private int getLimit(String path) {
        if (path.startsWith("/api/weather")) {
            return WEATHER_LIMIT;
        } else if (path.startsWith("/api/sentinel")) {
            return SENTINEL_LIMIT;
        } else if (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) {
            return SWAGGER_LIMIT;
        }
        return DEFAULT_LIMIT;
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    /**
     * 滑动窗口限流器实现
     */
    private static class SlidingWindowRateLimiter {
        private static final long WINDOW_MS = 60_000; // 1 分钟窗口
        private final AtomicInteger count = new AtomicInteger(0);
        private long windowStart = System.currentTimeMillis();

        public synchronized boolean tryAcquire(int limit) {
            long now = System.currentTimeMillis();
            // 滑动窗口：超过 1 分钟则重置计数
            if (now - windowStart > WINDOW_MS) {
                count.set(0);
                windowStart = now;
            }
            if (count.get() < limit) {
                count.incrementAndGet();
                return true;
            }
            return false;
        }
    }
}
