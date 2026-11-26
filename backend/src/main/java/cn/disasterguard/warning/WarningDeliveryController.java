package cn.disasterguard.warning;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.notification.NotificationDispatcher;
import cn.disasterguard.notification.dto.SendResultDto;
import cn.disasterguard.notification.entity.NotificationRecord;
import cn.disasterguard.notification.entity.NotificationReceipt;
import cn.disasterguard.notification.repository.NotificationReceiptRepository;
import cn.disasterguard.notification.repository.NotificationRecordRepository;
import cn.disasterguard.warning.entity.WarningEvent;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预警送达（Warning Delivery）REST API。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET /api/warnings/{id}/delivery/summary} — 送达汇总</li>
 *   <li>{@code GET /api/warnings/{id}/delivery/records} — 送达记录</li>
 *   <li>{@code POST /api/warnings/{id}/delivery/redeliver} — 重发失败通知</li>
 * </ul>
 *
 * <p><b>R11 P1-C2 数据源修正</b>：旧实现读 {@code warning_delivery_record}，
 * 但全仓没有任何 INSERT 写入该表（首次访问的"懒生成"也已按诚信要求移除），
 * 于是送达页恒为 0。真实回执在 {@code notification_record}（按 biz_id=预警编号）
 * + {@code notification_receipt}（每渠道每收件人一行）。
 *
 * <p>状态归一化：回执原始状态为 SUCCESS / MOCK_SENT / BLOCKED / FAILED，
 * 对外统一为 DELIVERED（真实送达）/ SENT（模拟发送，未真实送达）/ FAILED / PENDING，
 * 同时保留 {@code rawStatus} 便于排障。
 */
@RestController
@RequestMapping("/api/warnings")
public class WarningDeliveryController {

  private static final Logger log = LoggerFactory.getLogger(WarningDeliveryController.class);

  private final WarningEventRepository warningRepository;
  private final NotificationRecordRepository recordRepository;
  private final NotificationReceiptRepository receiptRepository;
  private final NotificationDispatcher notificationDispatcher;

  public WarningDeliveryController(
      WarningEventRepository warningRepository,
      NotificationRecordRepository recordRepository,
      NotificationReceiptRepository receiptRepository,
      NotificationDispatcher notificationDispatcher) {
    this.warningRepository = warningRepository;
    this.recordRepository = recordRepository;
    this.receiptRepository = receiptRepository;
    this.notificationDispatcher = notificationDispatcher;
  }

  /** 送达汇总：按真实回执聚合。 */
  @GetMapping("/{id}/delivery/summary")
  @PreAuthorize("hasAuthority('warnings:view')")
  public ApiResponse<Map<String, Object>> getSummary(@PathVariable Long id) {
    WarningEvent warning = warningRepository.findById(id).orElse(null);
    if (warning == null) {
      return ApiResponse.fail(404, "预警不存在: " + id);
    }

    List<Map<String, Object>> records = loadRows(warning);
    int total = records.size();
    int delivered = countByStatus(records, "DELIVERED");
    int sent = countByStatus(records, "SENT") + delivered;
    int failed = countByStatus(records, "FAILED");
    int pending = countByStatus(records, "PENDING");

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("warningId", id);
    summary.put("totalRecipients", total);
    summary.put("sent", sent);
    summary.put("delivered", delivered);
    summary.put("failed", failed);
    summary.put("pending", pending);

    Map<String, Map<String, Object>> channels = new LinkedHashMap<>();
    for (Map<String, Object> r : records) {
      String ch = r.get("channel") == null ? "unknown" : String.valueOf(r.get("channel"));
      Map<String, Object> agg = channels.computeIfAbsent(ch, k -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sent", 0);
        m.put("delivered", 0);
        m.put("failed", 0);
        return m;
      });
      String status = String.valueOf(r.get("status"));
      if ("DELIVERED".equals(status)) {
        agg.put("delivered", (Integer) agg.get("delivered") + 1);
        agg.put("sent", (Integer) agg.get("sent") + 1);
      } else if ("SENT".equals(status)) {
        agg.put("sent", (Integer) agg.get("sent") + 1);
      } else if ("FAILED".equals(status)) {
        agg.put("failed", (Integer) agg.get("failed") + 1);
      }
    }

    summary.put("channels", channels);
    summary.put("dataSource", "notification_receipt（真实回执聚合）");
    return ApiResponse.success(summary);
  }

  /** 送达记录列表：来自真实回执，库空即如实返回空列表。 */
  @GetMapping("/{id}/delivery/records")
  @PreAuthorize("hasAuthority('warnings:view')")
  public ApiResponse<Map<String, Object>> getRecords(
      @PathVariable Long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String channel) {

    WarningEvent warning = warningRepository.findById(id).orElse(null);
    if (warning == null) {
      return ApiResponse.fail(404, "预警不存在: " + id);
    }

    List<Map<String, Object>> filtered = loadRows(warning).stream()
        .filter(r -> channel == null || channel.isBlank() || channel.equals(r.get("channel")))
        .toList();

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);
    int total = filtered.size();
    int fromIndex = Math.min((safePage - 1) * safeSize, total);
    int toIndex = Math.min(fromIndex + safeSize, total);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", filtered.subList(fromIndex, toIndex));
    result.put("total", total);
    result.put("empty", total == 0);
    return ApiResponse.success(result);
  }

  /**
   * 重新发送失败通知。
   *
   * <p>真实调用 {@link NotificationDispatcher#retry(Long)}：按回执逐渠道重投，
   * 未配置网关时渠道会如实返回失败，本端点不再伪造成功条数。
   */
  @PostMapping("/{id}/delivery/redeliver")
  @PreAuthorize("hasAuthority('warnings:create')")
  public ApiResponse<Map<String, Object>> redeliver(
      @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
    WarningEvent warning = warningRepository.findById(id).orElse(null);
    if (warning == null) {
      return ApiResponse.fail(404, "预警不存在: " + id);
    }

    // 失败回执所属的投递记录（同一条 record 只 retry 一次）
    Set<Long> failedRecordIds = new LinkedHashSet<>();
    for (NotificationRecord record : recordRepository.findByBizIdOrderByIdAsc(warning.getWarningCode())) {
      boolean hasFailure = receiptRepository.findByRecordId(record.getId()).stream()
          .anyMatch(rc -> isFailure(rc.getStatus()));
      if (hasFailure) {
        failedRecordIds.add(record.getId());
      }
    }

    int succeeded = 0;
    int failed = 0;
    for (Long recordId : failedRecordIds) {
      try {
        SendResultDto r = notificationDispatcher.retry(recordId);
        long ok = r.results().stream()
            .filter(x -> "SUCCESS".equals(x.status()) || "MOCK_SENT".equals(x.status()))
            .count();
        if (ok > 0) {
          succeeded++;
        } else {
          failed++;
        }
      } catch (Exception ex) {
        failed++;
        log.warn("预警 {} 重发失败 recordId={}: {}", id, recordId, ex.getMessage());
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("attempted", failedRecordIds.size());
    result.put("succeeded", succeeded);
    result.put("failed", failed);
    result.put("message", failedRecordIds.isEmpty()
        ? "无失败记录，无需重发"
        : "已重试 " + failedRecordIds.size() + " 条投递记录（" + succeeded + " 成功 / " + failed + " 失败），"
            + "失败原因见回执 errorMsg");
    return ApiResponse.success(result);
  }

  /** 把预警编号下的全部投递记录 + 回执拍平成前端可用的行。 */
  private List<Map<String, Object>> loadRows(WarningEvent warning) {
    String bizId = warning.getWarningCode();
    if (bizId == null || bizId.isBlank()) {
      return List.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (NotificationRecord record : recordRepository.findByBizIdOrderByIdAsc(bizId)) {
      for (NotificationReceipt rc : receiptRepository.findByRecordId(record.getId())) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recordId", record.getId());
        row.put("bizType", record.getBizType());
        row.put("title", record.getTitle());
        row.put("channel", rc.getChannel());
        row.put("target", rc.getTarget());
        row.put("status", normalizeStatus(rc.getStatus()));
        row.put("rawStatus", rc.getStatus());
        row.put("errorMsg", rc.getErrorMsg());
        row.put("retryCount", rc.getRetryCount());
        row.put("sentAt", rc.getSentAt());
        row.put("deliveredAt", rc.getDeliveredAt());
        row.put("createdAt", rc.getCreatedAt());
        rows.add(row);
      }
    }
    return rows;
  }

  private static boolean isFailure(String rawStatus) {
    return rawStatus != null
        && !"SUCCESS".equals(rawStatus)
        && !"MOCK_SENT".equals(rawStatus)
        && !"PENDING".equals(rawStatus)
        && !"SENDING".equals(rawStatus);
  }

  /** 回执原始状态 → 送达页统一状态。 */
  private static String normalizeStatus(String rawStatus) {
    if (rawStatus == null) return "PENDING";
    return switch (rawStatus) {
      case "SUCCESS" -> "DELIVERED";
      case "MOCK_SENT" -> "SENT";
      case "PENDING", "SENDING" -> "PENDING";
      default -> "FAILED";
    };
  }

  private int countByStatus(List<Map<String, Object>> records, String status) {
    return (int) records.stream()
        .filter(r -> status.equals(String.valueOf(r.get("status"))))
        .count();
  }
}
