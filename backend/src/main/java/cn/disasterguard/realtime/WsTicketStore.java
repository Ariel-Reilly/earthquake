package cn.disasterguard.realtime;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * WebSocket 一次性 ticket 仓库（内存实现）。
 *
 * <p>连接流程：
 * <ol>
 *   <li>客户端先用正常 JWT 调用 {@code POST /api/auth/ws-ticket} 获取一次性 ticket（60s TTL）</li>
 *   <li>客户端用 ticket 连接 WebSocket：{@code ws://host:port/ws?ticket=xxx}</li>
 *   <li>ticket 一次性使用，握手后立即失效</li>
 * </ol>
 *
 * <p>ticket 相比直接把 JWT 塞 query 的优势：反向代理/服务器日志里看到的是一次性随机 token，
 * 即使泄露也无法重放。生产环境可替换为 Redis 存储。
 */
@Component
public class WsTicketStore {

  private static final Logger log = LoggerFactory.getLogger(WsTicketStore.class);
  private static final long TTL_SECONDS = 60;
  private static final SecureRandom RNG = new SecureRandom();

  private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

  /** 生成一次性 ticket，返回 ticket 字符串（base64url 随机 24 字节）。 */
  public String issue(String username, String role) {
    byte[] raw = new byte[24];
    RNG.nextBytes(raw);
    String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant expires = Instant.now().plusSeconds(TTL_SECONDS);
    tickets.put(ticket, new Ticket(username, role, expires));
    return ticket;
  }

  /** 验证并消费 ticket（一次性）。返回 null 表示无效/已过期/已使用。 */
  public Ticket consume(String ticket) {
    if (ticket == null || ticket.isBlank()) return null;
    Ticket t = tickets.remove(ticket);
    if (t == null) return null;
    if (t.expires().isBefore(Instant.now())) {
      log.warn("WS ticket 已过期 user={}", t.username());
      return null;
    }
    return t;
  }

  /** 定期清理过期 ticket（避免内存增长）。 */
  @Scheduled(fixedRate = 30_000)
  public void sweep() {
    Instant now = Instant.now();
    int before = tickets.size();
    tickets.entrySet().removeIf(e -> e.getValue().expires().isBefore(now));
    int removed = before - tickets.size();
    if (removed > 0) {
      log.debug("清理过期 WS ticket {} 条（剩余 {}）", removed, tickets.size());
    }
  }

  public record Ticket(String username, String role, Instant expires) {}
}
