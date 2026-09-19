package cn.disasterguard.notification;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.notification.entity.NotificationRecord;
import cn.disasterguard.notification.repository.NotificationRecordRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息中心（v1.7.0+ 消息列表页面）。
 *
 * <p>前端 dashboardApi.js 依赖：
 * <ul>
 *   <li>{@code GET /api/messages} — 消息列表（分页 + read 状态过滤）</li>
 *   <li>{@code POST /api/messages/{id}/read} — 标记单条已读</li>
 *   <li>{@code POST /api/messages/read-all} — 全部标为已读</li>
 * </ul>
 *
 * <p>数据源：{@code notification_record} 表 via {@link NotificationRecordRepository}.
 * 注意：实体无独立 {@code read} 字段，以 {@code status == "READ"} 判定已读。
 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

  private final NotificationRecordRepository recordRepository;

  public MessageController(NotificationRecordRepository recordRepository) {
    this.recordRepository = recordRepository;
  }

  /**
   * 查询消息列表（分页 + 多维过滤）。
   *
   * <p>过滤参数：
   * <ul>
   *   <li>{@code read} — true 仅已读 / false 仅未读 / null 不过滤</li>
   *   <li>{@code bizType} / {@code type} — 业务类型（WARNING / EMERGENCY / TASK / SYSTEM 等）</li>
   *   <li>{@code disasterType} — 灾害类型</li>
   *   <li>{@code level} / {@code severity} — 严重等级（HIGH / MEDIUM / LOW）</li>
   *   <li>{@code keyword} — 关键字（标题 + 内容 + 创建人模糊匹配）</li>
   * </ul>
   *
   * <p>{@code type}/{@code severity} 与 {@code bizType}/{@code level} 是同一组过滤维度的两套别名，
   * 前端旧页面（MessageCenterPage.vue）使用短名，后端存储使用规范名；任一字段非空均生效，
   * 同名字段以 bizType/level 为准。
   *
   * <p>所有过滤通过 {@link NotificationRecordRepository#findByFilters} 单条 SQL 处理，
   * 避免先全表加载再过滤造成的内存压力。
   */
  @GetMapping
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) Boolean read,
      @RequestParam(required = false) String bizType,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String disasterType,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String keyword) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);

    // 别名兜底：type → bizType，severity → level（任一非空均接受，规范名优先）
    String effectiveBizType = firstNonEmpty(bizType, type);
    String effectiveLevel = firstNonEmpty(level, severity);

    // 业务层面 read=true → status=READ；read=false → status≠READ（统一在 DB 层处理两个分路）
    Boolean readFilter = read;

    var pageable = org.springframework.data.domain.PageRequest.of(
        safePage - 1, safeSize,
        org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));

    String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

    // findByFilters 不支持 keyword + read=false 的组合（含 keyword 时退化为内存二次过滤）
    var pageResult = recordRepository.findByFilters(
        emptyToNull(effectiveBizType),
        emptyToNull(disasterType),
        emptyToNull(effectiveLevel),
        readFilter,
        null,
        pageable);
    List<NotificationRecord> all = pageResult.getContent();
    if (kw != null) {
      all = all.stream()
          .filter(r -> contains(r.getTitle(), kw) || contains(r.getContent(), kw) || contains(r.getCreatedBy(), kw))
          .toList();
    }

    List<Map<String, Object>> items = all.stream().map(this::toDto).toList();

    Map<String, Object> result = new LinkedHashMap<>();
    int total = (kw == null) ? (int) pageResult.getTotalElements() : all.size();
    result.put("items", items);
    result.put("total", total);
    result.put("page", safePage);
    result.put("size", safeSize);
    result.put("pages", Math.max(1, (int) Math.ceil(total / (double) safeSize)));
    return ApiResponse.success(result);
  }

  private String firstNonEmpty(String primary, String alias) {
    if (primary != null && !primary.isBlank()) return primary;
    if (alias != null && !alias.isBlank()) return alias;
    return null;
  }

  private String emptyToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  private boolean contains(String field, String keyword) {
    if (field == null) return false;
    return field.toLowerCase().contains(keyword.toLowerCase());
  }

  /** 标记单条消息为已读。 */
  @PostMapping("/{id}/read")
  public ApiResponse<Void> markRead(@PathVariable Long id) {
    NotificationRecord record = recordRepository.findById(id).orElse(null);
    if (record == null) {
      return ApiResponse.fail(404, "消息不存在");
    }
    record.setStatus("READ");
    record.setUpdatedAt(OffsetDateTime.now());
    recordRepository.save(record);
    return ApiResponse.success("已标记为已读", null);
  }

  /** 全部标为已读。 */
  @PostMapping("/read-all")
  public ApiResponse<Void> markAllRead() {
    var all = recordRepository.findAll();
    int count = 0;
    for (NotificationRecord r : all) {
      if (!"READ".equals(r.getStatus())) {
        r.setStatus("READ");
        r.setUpdatedAt(OffsetDateTime.now());
        recordRepository.save(r);
        count++;
      }
    }
    return ApiResponse.success("已标记 " + count + " 条消息为已读", null);
  }

  private Map<String, Object> toDto(NotificationRecord r) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("id", r.getId());
    dto.put("bizType", r.getBizType());
    dto.put("bizId", r.getBizId());
    dto.put("disasterType", r.getDisasterType());
    dto.put("level", r.getLevel());
    dto.put("title", r.getTitle());
    dto.put("content", r.getContent());
    dto.put("channels", r.getChannels());
    dto.put("status", r.getStatus());
    dto.put("read", "READ".equals(r.getStatus()));
    dto.put("createdAt", r.getCreatedAt());
    dto.put("updatedAt", r.getUpdatedAt());
    dto.put("createdBy", r.getCreatedBy());
    return dto;
  }
}
