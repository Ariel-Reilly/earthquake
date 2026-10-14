package cn.disasterguard.warning;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.warning.entity.WarningDeliveryRecord;
import cn.disasterguard.warning.repository.WarningDeliveryRecordRepository;
import cn.disasterguard.warning.repository.WarningEventRepository;
import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>{@code GET /api/warnings/{id}/delivery/summary} — 送达汇总（真实聚合）</li>
 *   <li>{@code GET /api/warnings/{id}/delivery/records} — 送达记录（真实行表）</li>
 *   <li>{@code POST /api/warnings/{id}/delivery/redeliver} — 重新发送失败通知</li>
 * </ul>
 *
 * <p>v1.8.0 起去掉两处造假：
 * <ol>
 *   <li>summary 原写死 {@code totalRecipients=156 / sent=150 / delivered=142} 及各渠道常量，
 *       与真实记录无关；现改为按 {@code payload} 里的 status 字段真实聚合，库空则全 0。</li>
 *   <li>records 原在首次访问时"懒生成" 15 条虚构送达记录（张三/李四/网络超时等）并落库，
 *       属于凭空造数据；现只返回真实存在的记录，库空即如实返回空列表。</li>
 * </ol>
 *
 * <p>数据持久化：行表 {@code warning_delivery_record}
 * （record_id = 预警 id，payload = 单条记录）。
 */
@RestController
@RequestMapping("/api/warnings")
public class WarningDeliveryController {

  /** 种子记录起始 id，与原内存版 AtomicLong(1000) + incrementAndGet 的首值一致。 */
  private static final long SEED_ID_BASE = 1000;

  private final WarningEventRepository warningRepository;
  private final WarningDeliveryRecordRepository deliveryRecordRepository;

  private long recordIdGen;

  public WarningDeliveryController(
      WarningEventRepository warningRepository,
      WarningDeliveryRecordRepository deliveryRecordRepository) {
    this.warningRepository = warningRepository;
    this.deliveryRecordRepository = deliveryRecordRepository;
  }

  @PostConstruct
  void init() {
    recordIdGen = Math.max(deliveryRecordRepository.findMaxId(), SEED_ID_BASE);
  }

  /**
   * 送达汇总：按真实记录的 status 字段聚合，不再写死常量。
   *
   * <p>status 取值口径：{@code DELIVERED}（已送达）/ {@code SENT}（已发送未确认）/
   * {@code FAILED}（失败）/ {@code PENDING}（待发送）。
   */
  @GetMapping("/{id}/delivery/summary")
  @PreAuthorize("hasAuthority('warnings:view')")
  public ApiResponse<Map<String, Object>> getSummary(@PathVariable Long id) {
    var warning = warningRepository.findById(id).orElse(null);
    if (warning == null) {
      return ApiResponse.fail(404, "预警不存在: " + id);
    }

    List<Map<String, Object>> records = deliveryRecordRepository
        .findByRecordIdOrderByIdAsc(id).stream()
        .map(WarningDeliveryRecord::getPayload)
        .toList();

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

    // 按渠道聚合
    Map<String, Map<String, Object>> channels = new LinkedHashMap<>();
    for (Map<String, Object> r : records) {
      Object chObj = r.get("channel");
      String ch = chObj == null ? "unknown" : String.valueOf(chObj);
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
    summary.put("dataSource", "warning_delivery_record（真实库聚合）");
    return ApiResponse.success(summary);
  }

  /** 送达记录列表：只返回真实存在的记录，不再懒生成假数据。 */
  @GetMapping("/{id}/delivery/records")
  @PreAuthorize("hasAuthority('warnings:view')")
  public ApiResponse<Map<String, Object>> getRecords(
      @PathVariable Long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String channel) {

    var warning = warningRepository.findById(id).orElse(null);
    if (warning == null) {
      return ApiResponse.fail(404, "预警不存在: " + id);
    }

    List<Map<String, Object>> filtered = deliveryRecordRepository
        .findByRecordIdOrderByIdAsc(id).stream()
        .map(WarningDeliveryRecord::getPayload)
        .filter(r -> channel == null || channel.isBlank() || channel.equals(r.get("channel")))
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
    // 库中无记录时明确告知前端（前端据此显示空态而非"数据加载中"）
    result.put("empty", total == 0);
    return ApiResponse.success(result);
  }

  /**
   * 重新发送失败通知。
   *
   * <p><b>诚实说明</b>：本端点当前不具备真实短信/邮件/推送网关下发能力，
   * 因此仅将本次重发请求中的失败记录重新置为 {@code PENDING}（等待真实网关消费），
   * 不返回伪造的"成功/失败条数"。
   * 原实现的 {@code resent = channels × recipients} 与 {@code failed = 10%} 是纯编造。
   */
  @PostMapping("/{id}/delivery/redeliver")
  @PreAuthorize("hasAuthority('warnings:create')")
  public ApiResponse<Map<String, Object>> redeliver(
      @PathVariable Long id, @RequestBody Map<String, Object> body) {
    var warning = warningRepository.findById(id).orElse(null);
    if (warning == null) {
      return ApiResponse.fail(404, "预警不存在: " + id);
    }

    List<WarningDeliveryRecord> all =
        deliveryRecordRepository.findByRecordIdOrderByIdAsc(id);
    List<WarningDeliveryRecord> failedOnes = all.stream()
        .filter(r -> "FAILED".equals(String.valueOf(r.getPayload().get("status"))))
        .toList();

    for (WarningDeliveryRecord rec : failedOnes) {
      Map<String, Object> payload = new LinkedHashMap<>(rec.getPayload());
      payload.put("status", "PENDING");
      payload.put("requeuedAt", OffsetDateTime.now().toString());
      rec.setPayload(payload);
    }
    if (!failedOnes.isEmpty()) {
      deliveryRecordRepository.saveAll(failedOnes);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("requeued", failedOnes.size());
    result.put("gatewayConnected", false);
    result.put(
        "message",
        failedOnes.isEmpty()
            ? "无失败记录，无需重发"
            : "已将 " + failedOnes.size() + " 条失败记录置为待发送；当前未接入真实下发网关，不会真正送达");
    return ApiResponse.success(result);
  }

  private int countByStatus(List<Map<String, Object>> records, String status) {
    return (int) records.stream()
        .filter(r -> status.equals(String.valueOf(r.get("status"))))
        .count();
  }
}
