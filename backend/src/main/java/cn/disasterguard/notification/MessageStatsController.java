package cn.disasterguard.notification;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.notification.repository.NotificationRecordRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 消息中心统计（v1.7.0+ 待办 / 消息红点用）。
 *
 * <p>前端 dashboardApi.js 在 {@code refreshDynamicBadges} 中以轮询方式拉取本端点，
 * 用于顶栏"消息中心"红点。
 *
 * <p>当前实现以 notification_record 表作为底层数据源：
 * <ul>
 *   <li>{@code unread} — 用户未读记录数（去掉 READ 之外的所有记录）</li>
 *   <li>{@code total} — 全量记录数</li>
 *   <li>{@code failed} — 发送失败的记录数（运维提示）</li>
 * </ul>
 *
 * <p><b>口径修正（红点不消失问题）</b>：{@code unread} 曾按
 * {@code PENDING + SENT + DELIVERED} 三个状态求和，但 {@code DELIVERED} 是"渠道回执已送达"，
 * 与"用户是否已读"无关。派发类链路（如 RuleEngine → NotificationRouter）只写
 * SENT / PARTIAL / DELIVERED，从不写 READ，于是这些记录永远被算作未读——
 * 用户在消息中心点了"标已读"（status=READ）之后红点也消不掉。
 *
 * <p>现统一为 {@code total - countByStatus("READ")}，与 {@link MessageController#list}
 * 的 {@code read=false} 分路（{@code r.status <> 'READ'}）保持同一口径，
 * 保证"红点数字 == 列表里未读条数"。
 */
@RestController
@RequestMapping("/api/messages")
public class MessageStatsController {

  private final NotificationRecordRepository recordRepository;

  public MessageStatsController(NotificationRecordRepository recordRepository) {
    this.recordRepository = recordRepository;
  }

  @GetMapping("/stats")
  @PreAuthorize("hasAuthority('messages:view')")
  public ApiResponse<Map<String, Object>> stats() {
    // notification_record.status 取值见 NotificationService.send() 流转：
    //   PENDING -> SENT -> (PARTIAL / DELIVERED) -> READ / FAILED
    // 已读判定唯一口径 = status == "READ"，与 MessageController#list(read=false) 一致。
    long total = recordRepository.count();
    long read = recordRepository.countByStatus("READ");
    long unread = Math.max(0, total - read);
    long failed = recordRepository.countByStatus("FAILED");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("unread", unread);
    result.put("read", read);
    result.put("failed", failed);
    result.put("total", total);
    return ApiResponse.success(result);
  }
}
