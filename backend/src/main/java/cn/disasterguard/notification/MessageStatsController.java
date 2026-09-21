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
 *   <li>{@code unread} — 状态为"未读"或"已发送未送达"的记录数</li>
 *   <li>{@code total} — 全量记录数</li>
 *   <li>{@code failed} — 发送失败的记录数（运维提示）</li>
 * </ul>
 *
 * <p>后续接入消息中心实体后，把 {@code countByStatus} 替换为消息表对应字段即可。
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
    //   PENDING -> SENT -> DELIVERED -> READ / FAILED
    long unread = recordRepository.countByStatus("PENDING")
        + recordRepository.countByStatus("SENT")
        + recordRepository.countByStatus("DELIVERED");
    long failed = recordRepository.countByStatus("FAILED");
    long total = recordRepository.count();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("unread", unread);
    result.put("failed", failed);
    result.put("total", total);
    return ApiResponse.success(result);
  }
}
