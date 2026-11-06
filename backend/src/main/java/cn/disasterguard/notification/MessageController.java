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
import org.springframework.security.access.prepost.PreAuthorize;

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
  @PreAuthorize("hasAuthority('messages:view')")
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

    // keyword 已下推到 DB 层（findByFilters 的 LIKE 匹配 title/content/createdBy），
    // 分页与总数均由数据库计算，不再做内存二次过滤，total 始终为全局匹配数。
    var pageResult = recordRepository.findByFilters(
        emptyToNull(effectiveBizType),
        emptyToNull(disasterType),
        emptyToNull(effectiveLevel),
        readFilter,
        kw,
        null,
        pageable);
    List<Map<String, Object>> items = pageResult.getContent().stream().map(this::toDto).toList();

    Map<String, Object> result = new LinkedHashMap<>();
    int total = (int) pageResult.getTotalElements();
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

  /** 标记单条消息为已读。 */
  @PostMapping("/{id}/read")
  @PreAuthorize("hasAuthority('messages:view')")
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

  /**
   * 全部标为已读。
   *
   * <p>口径与 {@link #markRead} 完全一致：只把"当前未读"（{@code status <> 'READ'}）的记录
   * 置为 READ，避免把 FAILED 这类运维记录也吞掉。返回实际更新条数，前端据此立即清红点。
   */
  @PostMapping("/read-all")
  @PreAuthorize("hasAuthority('messages:view')")
  public ApiResponse<Map<String, Object>> markAllRead() {
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
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("updated", count);
    result.put("unread", 0);
    return ApiResponse.success("已标记 " + count + " 条消息为已读", result);
  }

  /**
   * 记录 → 前端消息中心 DTO。
   *
   * <p>字段名对齐前端 {@code MessageCenterPage.vue} 与 {@code DashboardLayout} 的既有契约：
   * <ul>
   *   <li>{@code type} — 由 {@code biz_type} 归一化为 warning/ai/task/system，
   *       前端按此做 Tab 过滤与图标分发（曾只返回 bizType，导致前端筛选项全部落空、
   *       列表看起来"没有消息"）；</li>
   *   <li>{@code severity} — 由 {@code level} 归一化为 high/medium/low；</li>
   *   <li>{@code unread} — 布尔，前端红点与"标已读"按钮都读它（曾缺失，前端恒为 undefined，
   *       于是每条都渲染成已读、统计恒为 0）；</li>
   *   <li>{@code time} — 相对时间文案（前端的 mock 形态就是字符串）；</li>
   *   <li>同时保留 {@code level} / {@code bizType} 原字段，供后端其他消费方使用。</li>
   * </ul>
   */
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
    // 已读唯一口径 = status == READ（与 stats 端点一致）
    dto.put("read", "READ".equals(r.getStatus()));
    dto.put("unread", !"READ".equals(r.getStatus()));

    // ── 前端契约字段（归一化） ──
    dto.put("type", normalizeType(r.getBizType()));
    dto.put("severity", normalizeSeverity(r.getLevel()));
    dto.put("time", relativeTime(r.getCreatedAt()));

    dto.put("createdAt", r.getCreatedAt());
    dto.put("updatedAt", r.getUpdatedAt());
    dto.put("createdBy", r.getCreatedBy());
    return dto;
  }

  /** 业务类型 → 前端消息类型（warning / ai / task / system）。未知一律归 system。 */
  private String normalizeType(String bizType) {
    if (bizType == null) return "system";
    return switch (bizType.trim().toUpperCase()) {
      case "WARNING", "WARN", "DISASTER" -> "warning";
      case "AI", "AI_ANALYSIS", "AI_INSIGHT" -> "ai";
      case "TASK", "INSPECTION", "EMERGENCY", "CONFIRMATION" -> "task";
      default -> "system";
    };
  }

  /** 等级 → 前端紧急度（high / medium / low）。未知一律 low。 */
  private String normalizeSeverity(String level) {
    if (level == null) return "low";
    return switch (level.trim().toUpperCase()) {
      case "HIGH", "RED", "ORANGE", "CRITICAL", "SEVERE" -> "high";
      case "MEDIUM", "YELLOW", "MODERATE" -> "medium";
      default -> "low";
    };
  }

  /**
   * 生成相对时间文案（"刚刚 / N分钟前 / N小时前 / N天前"），
   * 超过 7 天退化为 {@code yyyy-MM-dd}。前端直接展示该字符串，无需再解析。
   */
  private String relativeTime(OffsetDateTime createdAt) {
    if (createdAt == null) return "—";
    long minutes = java.time.Duration.between(createdAt, OffsetDateTime.now()).toMinutes();
    if (minutes < 1) return "刚刚";
    if (minutes < 60) return minutes + "分钟前";
    long hours = minutes / 60;
    if (hours < 24) return hours + "小时前";
    long days = hours / 24;
    if (days <= 7) return days + "天前";
    return createdAt.toLocalDate().toString();
  }
}
