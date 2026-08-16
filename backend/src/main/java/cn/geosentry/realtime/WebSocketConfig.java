package cn.geosentry.realtime;

import cn.geosentry.auth.jwt.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置：注册 {@link RealtimeBroadcastService} 处理 {@code /ws} 路径，
 * 并通过 {@link JwtHandshakeInterceptor} 校验 query 参数中的鉴权凭据。
 *
 * <p>推荐连接方式（更安全）：
 * <pre>
 *   // 1. 先用 JWT 获取一次性 ticket
 *   const { ticket } = await fetch("/api/auth/ws-ticket", { headers: authHeader }).then(r => r.json()).data;
 *   // 2. 用 ticket 连接，60s 内一次性消费
 *   new WebSocket(`ws://host:port/ws?ticket=${ticket}`);
 * </pre>
 *
 * <p>兼容旧客户端（不推荐，生产环境可设 {@code geosentry.ws.require-ticket=true} 强制禁用）：
 * <pre>
 *   new WebSocket(`ws://host:port/ws?token=${authToken}`);
 * </pre>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final RealtimeBroadcastService broadcastService;
  private final JwtService jwtService;
  private final WsTicketRepository ticketRepository;
  private final boolean requireTicketOnly;

  @Value("${geosentry.realtime.websocket-enabled:true}")
  private boolean enabled;

  public WebSocketConfig(RealtimeBroadcastService broadcastService, JwtService jwtService,
                         WsTicketRepository ticketRepository,
                         @Value("${geosentry.ws.require-ticket:false}") boolean requireTicketOnly) {
    this.broadcastService = broadcastService;
    this.jwtService = jwtService;
    this.ticketRepository = ticketRepository;
    this.requireTicketOnly = requireTicketOnly;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    if (!enabled) {
      return;
    }
    registry.addHandler(broadcastService, "/ws")
        .addInterceptors(new JwtHandshakeInterceptor(jwtService, ticketRepository, requireTicketOnly))
        .setAllowedOriginPatterns("*");
  }
}
