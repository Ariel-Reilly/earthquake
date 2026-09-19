package cn.disasterguard.warning;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
 *   <li>{@code POST /api/warnings/{id}/delivery/redeliver} — 重新发送失败通知</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/warnings")
public class WarningDeliveryController {

  private final WarningEventRepository warningRepository;
  private final Map<Long, List<Map<String, Object>>> deliveryRecords = new ConcurrentHashMap<>();
  private final AtomicLong recordIdGen = new AtomicLong(1000);

  public WarningDeliveryController(WarningEventRepository warningRepository) {
    this.warningRepository = warningRepository;
  }

  @GetMapping("/{id}/delivery/summary")
  public ApiResponse<Map<String, Object>> getSummary(@PathVariable Long id) {
    var warning = warningRepository.findById(id).orElse(null);
    if (warning == null) {
      return ApiResponse.fail(404, "预警不存在: " + id);
    }

    // 模拟送达数据
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("warningId", id);
    summary.put("totalRecipients", 156);
    summary.put("sent", 150);
    summary.put("delivered", 142);
    summary.put("failed", 8);
    summary.put("pending", 6);

    Map<String, Object> channels = new LinkedHashMap<>();
    channels.put("app", Map.of("sent", 100, "delivered", 95, "failed", 5));
    channels.put("sms", Map.of("sent", 30, "delivered", 28, "failed", 2));
    channels.put("email", Map.of("sent", 15, "delivered", 12, "failed", 3));
    channels.put("webhook", Map.of("sent", 5, "delivered", 5, "failed", 0));
    summary.put("channels", channels);

    return ApiResponse.success(summary);
  }

  @GetMapping("/{id}/delivery/records")
  public ApiResponse<Map<String, Object>> getRecords(
      @PathVariable Long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String channel) {

    List<Map<String, Object>> allRecords = deliveryRecords.computeIfAbsent(id, k -> {
      List<Map<String, Object>> records = new ArrayList<>();
      String[] channels = {"app", "sms", "email", "webhook"};
      String[] statuses = {"DELIVERED", "DELIVERED", "FAILED", "PENDING"};
      String[] recipients = {"张三", "李四", "王五", "应急指挥中心", "值班员A"};
      for (int i = 0; i < 15; i++) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", recordIdGen.incrementAndGet());
        record.put("channel", channels[i % 4]);
        record.put("recipient", recipients[i % 5]);
        record.put("status", statuses[i % 4]);
        record.put("sentAt", java.time.OffsetDateTime.now().minusMinutes(i * 5));
        if ("DELIVERED".equals(statuses[i % 4])) {
          record.put("deliveredAt", java.time.OffsetDateTime.now().minusMinutes(i * 5 - 1));
        }
        if ("FAILED".equals(statuses[i % 4])) {
          record.put("error", "网络超时，发送失败");
        }
        records.add(record);
      }
      return records;
    });

    var filtered = allRecords.stream()
        .filter(r -> channel == null || channel.isBlank()
            || channel.equals(r.get("channel")))
        .toList();

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);
    int total = filtered.size();
    int fromIndex = Math.min((safePage - 1) * safeSize, total);
    int toIndex = Math.min(fromIndex + safeSize, total);
    List<Map<String, Object>> items = filtered.subList(fromIndex, toIndex);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("total", total);
    return ApiResponse.success(result);
  }

  @PostMapping("/{id}/delivery/redeliver")
  public ApiResponse<Map<String, Object>> redeliver(
      @PathVariable Long id,
      @RequestBody Map<String, Object> body) {
    var warning = warningRepository.findById(id).orElse(null);
    if (warning == null) {
      return ApiResponse.fail(404, "预警不存在: " + id);
    }

    @SuppressWarnings("unchecked")
    List<String> channels = (List<String>) body.getOrDefault("channels",
        List.of("app", "sms", "email"));
    @SuppressWarnings("unchecked")
    List<String> recipients = (List<String>) body.getOrDefault("recipients",
        List.of());

    // 模拟重新发送
    int resent = channels.size() * (recipients.isEmpty() ? 1 : recipients.size());
    int failed = (int) (resent * 0.1); // 10% 失败率

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("resent", resent);
    result.put("failed", failed);
    result.put("message", failed == 0 ? "全部重发成功" : "部分重发失败，请检查网络连接");
    return ApiResponse.success(result);
  }
}
