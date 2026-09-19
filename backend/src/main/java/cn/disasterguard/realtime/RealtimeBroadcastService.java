package cn.disasterguard.realtime;

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
    log.warn("WebSocket 传输错误: session={}, error={}，将清理 session", session.getId(), exception.getMessage());
    // 某些异常场景（如中间服务器 RST）不会触发 afterConnectionClosed，这里兜底清理
    sessions.remove(session.getId());
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

  /**
   * v1.6.0：广播 AI 实时监测发现的新灾害点。
   * payload 是 HazardDto，前端 realtimeService.js 监听 hazard 事件并 upsert 到列表。
   */
  public void broadcastHazard(Object hazardData) {
    send("hazard", hazardData);
  }

  /** v2.0：广播大屏消息（通知 / 应急指令）。前端大屏订阅 screen 事件。 */
  public void broadcastScreen(Object screenData) {
    send("screen", screenData);
  }

  /** 当前活跃连接数。 */
  public int connectionCount() {
    return sessions.size();
  }

  private void send(String type, Object data) {
    if (sessions.isEmpty()) return;
    String json;
    try {
      json = mapper.writeValueAsString(Map.of(
          "type", type,
          "data", data,
          "version", versionCounter.incrementAndGet()
      ));
    } catch (IOException e) {
      log.warn("WebSocket 广播序列化失败 type={}: {}", type, e.getMessage());
      return;
    }
    TextMessage msg = new TextMessage(json);
    // per-session try-catch：一个断线 session 不影响其余客户端。
    // 先 snapshot 一次 values()，避免迭代过程中 remove() 在某些 JDK 下抛 CME。
    java.util.List<WebSocketSession> snapshot = new java.util.ArrayList<>(sessions.values());
    for (WebSocketSession session : snapshot) {
      try {
        if (session.isOpen()) {
          session.sendMessage(msg);
        }
      } catch (Exception ex) {
        log.debug("WebSocket 发送失败 session={}，将移除: {}", session.getId(), ex.getMessage());
        sessions.remove(session.getId());
      }
    }
  }
}
