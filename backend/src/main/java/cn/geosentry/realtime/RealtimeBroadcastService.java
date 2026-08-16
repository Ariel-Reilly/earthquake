package cn.geosentry.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 实时广播 WebSocket 处理器。
 *
 * <p>协议：客户端连接 {@code /ws}，服务端推送 JSON 消息，形如：
 * <pre>{
 *   "type": "warning" | "device" | "personnel" | "emergency",
 *   "data": { ... },
 *   "version": 123
 * }</pre>
 *
 * <p>前端（{@code realtimeService.js}）基于消息的 {@code version} 字段做幂等 upsert，
 * 只接收"更新的"数据，避免 WebSocket 重连后状态回退闪烁。
 */
@Component
public class RealtimeBroadcastService extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(RealtimeBroadcastService.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  /**
   * 服务端递增版本号，用于前端幂等合并——同一 entityId 下 version 递增则覆盖旧消息。
   * 初始值取当前毫秒时间戳，确保服务重启后 version 仍单调递增，
   * 不会因归零导致前端丢弃重启初期的新消息。
   */
  private final AtomicLong versionCounter = new AtomicLong(System.currentTimeMillis());

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    sessions.put(session.getId(), session);
    log.info("WebSocket 客户端已连接: session={}, 当前连接数={}", session.getId(), sessions.size());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    sessions.remove(session.getId());
    log.info("WebSocket 客户端已断开: session={}, status={}, 剩余连接数={}",
        session.getId(), status, sessions.size());
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    log.warn("WebSocket 传输错误: session={}, error={}", session.getId(), exception.getMessage());
  }

  /** 广播预警事件（新建/状态变更）。 */
  public void broadcastWarning(Object warningData) {
    send("warning", warningData);
  }

  /** 广播设备状态变更（上线/离线/指标刷新）。 */
  public void broadcastDevice(Object deviceData) {
    send("device", deviceData);
  }

  /** 广播值守人员位置更新。 */
  public void broadcastPersonnel(Object personnelData) {
    send("personnel", personnelData);
  }

  /** 广播应急事件（新建/闭环）。 */
  public void broadcastEmergency(Object emergencyData) {
    send("emergency", emergencyData);
  }

  /** 当前活跃连接数。 */
  public int connectionCount() {
    return sessions.size();
  }

  private void send(String type, Object data) {
    if (sessions.isEmpty()) return;
    try {
      Map<String, Object> payload = Map.of(
          "type", type,
          "data", data,
          "version", versionCounter.incrementAndGet()
      );
      String json = mapper.writeValueAsString(payload);
      TextMessage msg = new TextMessage(json);
      for (WebSocketSession session : sessions.values()) {
        if (session.isOpen()) {
          session.sendMessage(msg);
        }
      }
    } catch (IOException e) {
      log.warn("WebSocket 广播失败 type={}: {}", type, e.getMessage());
    }
  }
}
