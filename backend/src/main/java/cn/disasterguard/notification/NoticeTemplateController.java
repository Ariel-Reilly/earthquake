package cn.disasterguard.notification;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.notification.dto.NoticeTemplateDto;
import cn.disasterguard.notification.dto.SendResultDto;
import cn.disasterguard.notification.dto.TestSendRequest;
import cn.disasterguard.notification.entity.NoticeTemplate;
import cn.disasterguard.notification.repository.NoticeTemplateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知模板管理（v2.2 补齐）。
 *
 * <p>本 Controller 填补一个长期存在的缺口：{@code notice_template} 表与
 * {@code NoticeTemplatePage.vue} 都已就绪，但后端始终没有对应接口，导致前端
 * 5 个调用全部 404、页面只能靠 {@code sampleTemplates} 兜底显示假数据。
 *
 * <p>前端 dashboardApi.js 依赖：
 * <ul>
 *   <li>{@code GET /api/notice-templates} — 列表（分页 + 灾种/等级/渠道/启停过滤）</li>
 *   <li>{@code GET /api/notice-templates/{id}} — 详情</li>
 *   <li>{@code POST /api/notice-templates} — 新建</li>
 *   <li>{@code PUT /api/notice-templates/{id}} — 更新</li>
 *   <li>{@code DELETE /api/notice-templates/{id}} — 删除</li>
 *   <li>{@code POST /api/notice-templates/{id}/toggle} — 启停切换</li>
 * </ul>
 *
 * <p>渠道建模：原表是「一行 = 一个模板绑定单个渠道」。管理页要求一个模板配置多个
 * 渠道正文，因此正文映射写入 JSONB 列 {@code channel_contents}；遗留的
 * {@code channel} / {@code content_template} 两列同步写入首个有内容的渠道，
 * 使旧数据和既有索引保持可用。读取时若 {@code channel_contents} 为空则回退为
 * {@code {channel: content_template}}，无需迁移历史数据。
 */
@RestController
@RequestMapping("/api/notice-templates")
public class NoticeTemplateController {

  private static final Logger log = LoggerFactory.getLogger(NoticeTemplateController.class);

  private final NoticeTemplateRepository repository;
  private final ObjectMapper objectMapper;
  private final NotificationService notificationService;

  public NoticeTemplateController(NoticeTemplateRepository repository,
                                  ObjectMapper objectMapper,
                                  NotificationService notificationService) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.notificationService = notificationService;
  }

  /**
   * 模板列表（分页 + 多维过滤）。
   *
   * @param hazardType 灾害类型（对应 disaster_type，取值同 unified_event.hazard_type，
   *                   如 landslide / collapse）
   * @param status     "enabled" / "disabled"，其余值视为不过滤
   */
  @GetMapping
  @PreAuthorize("hasAuthority('notice:view')")
  public ApiResponse<PageResponse<Map<String, Object>>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String hazardType,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) String status) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 1);

    var pageable = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "updatedAt"));

    var result = repository.findByFilters(
        emptyToNull(hazardType),
        emptyToNull(level),
        emptyToNull(channel),
        parseStatus(status),
        pageable);

    return ApiResponse.success(PageResponse.of(result.map(this::toDto)));
  }

  /** 模板详情。 */
  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('notice:view')")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    NoticeTemplate entity = repository.findById(id).orElse(null);
    if (entity == null) {
      return ApiResponse.fail(404, "模板不存在");
    }
    return ApiResponse.success(toDto(entity));
  }

  /** 新建模板。 */
  @PostMapping
  @PreAuthorize("hasAuthority('notice:edit')")
  public ApiResponse<Map<String, Object>> create(@RequestBody(required = false) NoticeTemplateDto body) {
    if (body == null || isBlank(body.getName())) {
      return ApiResponse.fail(400, "请填写模板名称");
    }
    if (!hasAnyContent(body.getChannels())) {
      return ApiResponse.fail(400, "请至少填写一个渠道的内容");
    }

    NoticeTemplate entity = new NoticeTemplate();
    entity.setTemplateCode(generateTemplateCode());
    entity.setCreatedBy(currentUsername());
    apply(entity, body);
    entity.setCreatedAt(OffsetDateTime.now());
    return ApiResponse.success("模板已创建", toDto(repository.save(entity)));
  }

  /** 更新模板（全量覆盖可编辑字段）。 */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('notice:edit')")
  public ApiResponse<Map<String, Object>> update(
      @PathVariable Long id,
      @RequestBody(required = false) NoticeTemplateDto body) {
    NoticeTemplate entity = repository.findById(id).orElse(null);
    if (entity == null) {
      return ApiResponse.fail(404, "模板不存在");
    }
    if (body == null || isBlank(body.getName())) {
      return ApiResponse.fail(400, "请填写模板名称");
    }
    if (!hasAnyContent(body.getChannels())) {
      return ApiResponse.fail(400, "请至少填写一个渠道的内容");
    }
    apply(entity, body);
    return ApiResponse.success("模板已更新", toDto(repository.save(entity)));
  }

  /** 删除模板。 */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('notice:edit')")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    if (!repository.existsById(id)) {
      return ApiResponse.fail(404, "模板不存在");
    }
    repository.deleteById(id);
    return ApiResponse.success("模板已删除", null);
  }

  /** 启停切换。 */
  @PostMapping("/{id}/toggle")
  @PreAuthorize("hasAuthority('notice:edit')")
  public ApiResponse<Map<String, Object>> toggle(@PathVariable Long id) {
    NoticeTemplate entity = repository.findById(id).orElse(null);
    if (entity == null) {
      return ApiResponse.fail(404, "模板不存在");
    }
    entity.setEnabled(!Boolean.TRUE.equals(entity.getEnabled()));
    entity.setUpdatedAt(OffsetDateTime.now());
    return ApiResponse.success(
        Boolean.TRUE.equals(entity.getEnabled()) ? "模板已启用" : "模板已停用",
        toDto(repository.save(entity)));
  }

  /**
   * 测试发送通知模板（POST /api/notice-templates/{id}/test）。
   *
   * <p>复用 {@link NotificationService#testSend(TestSendRequest)} 的真实发送链路
   * （最终调用各 {@code NotificationSender} 实现，与 {@link NotificationDispatcher}
   * 生产投递共用同一套发送器）。用模板里该渠道的正文做真实变量渲染后发送，
   * 并如实透传真实结果：通道未配置 / 发送器禁用时返回明确失败原因，绝不伪造成功。
   * 操作人取 SecurityContext，不信任请求体里的 operator。
   */
  @PostMapping("/{id}/test")
  @PreAuthorize("hasAuthority('notice:edit')")
  public ApiResponse<Map<String, Object>> test(
      @PathVariable Long id,
      @RequestBody(required = false) Map<String, Object> body) {
    NoticeTemplate entity = repository.findById(id).orElse(null);
    if (entity == null) {
      return ApiResponse.fail(404, "模板不存在");
    }
    Map<String, Object> b = body == null ? Map.of() : body;
    String operator = currentUsername();

    // 1. 渠道：优先请求体，其次模板已配置渠道
    Map<String, String> channels = readChannels(entity);
    String channel = str(b.get("channel"));
    if (isBlank(channel)) {
      channel = channels.keySet().stream().findFirst().orElse(null);
    }
    if (isBlank(channel)) {
      return ApiResponse.fail(400, "模板未配置任何通知渠道，且请求未指定 channel，无法测试发送");
    }
    channel = channel.trim().toUpperCase(Locale.ROOT);

    // 2. 发送目标
    String target = str(b.get("target"));
    if (isBlank(target)) {
      return ApiResponse.fail(400, "请填写测试发送目标 target（手机号 / 邮箱 / 设备 Token）");
    }
    target = target.trim();

    // 3. 真实变量渲染（operator 强制取自 SecurityContext，覆盖请求体同名字段）
    Map<String, Object> variables = new LinkedHashMap<>();
    if (b.get("variables") instanceof Map<?, ?> raw) {
      raw.forEach((k, v) -> {
        if (k != null) {
          variables.put(String.valueOf(k), v);
        }
      });
    }
    variables.putIfAbsent("name", entity.getTemplateName());
    variables.putIfAbsent("level", entity.getLevel());
    variables.putIfAbsent("hazardType", entity.getDisasterType());
    variables.putIfAbsent("time", OffsetDateTime.now().toString());
    variables.put("operator", operator);

    String titleTemplate = isBlank(entity.getTitleTemplate())
        ? "【通知模板测试】" + entity.getTemplateName()
        : entity.getTitleTemplate();
    String contentTemplate = templateContentFor(channels, channel);
    if (isBlank(contentTemplate)) {
      contentTemplate = "【模板测试】模板《" + entity.getTemplateName()
          + "》由 " + (operator == null ? "system" : operator) + " 发起测试发送。";
    }
    String title = render(titleTemplate, variables);
    String content = render(contentTemplate, variables);

    // 4. 真实发送链路
    SendResultDto result = notificationService.testSend(
        new TestSendRequest(channel, target, title, content, variables));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("templateId", id);
    data.put("templateName", entity.getTemplateName());
    data.put("channel", channel);
    data.put("target", target);
    data.put("title", title);
    data.put("operator", operator);
    data.put("status", result.status());
    data.put("results", result.results());
    data.put("message", result.message());
    if (!"SUCCESS".equals(result.status())) {
      return ApiResponse.fail(502,
          "测试发送失败（" + result.status() + "）：" + result.message());
    }
    return ApiResponse.success("测试发送成功", data);
  }

  // ---------------------------------------------------------------- 内部方法

  /** 取模板中指定渠道的正文（渠道名大小写不敏感）。 */
  private String templateContentFor(Map<String, String> channels, String channel) {
    for (Map.Entry<String, String> e : channels.entrySet()) {
      if (e.getKey() != null && e.getKey().equalsIgnoreCase(channel)) {
        return e.getValue();
      }
    }
    return null;
  }

  /** 真实变量渲染：替换 {{key}} 与 ${key} 占位符。 */
  private String render(String template, Map<String, Object> variables) {
    if (template == null) {
      return "";
    }
    String out = template;
    for (Map.Entry<String, Object> e : variables.entrySet()) {
      String val = e.getValue() == null ? "" : String.valueOf(e.getValue());
      out = out.replace("{{" + e.getKey() + "}}", val)
          .replace("${" + e.getKey() + "}", val);
    }
    return out;
  }

  private String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  private void apply(NoticeTemplate entity, NoticeTemplateDto body) {
    entity.setTemplateName(body.getName().trim());
    entity.setDisasterType(emptyToNull(body.getHazardType()));
    entity.setLevel(emptyToNull(body.getLevel()));
    entity.setEnabled(body.getEnabled() == null ? Boolean.TRUE : body.getEnabled());
    if (!isBlank(body.getTitleTemplate())) {
      entity.setTitleTemplate(body.getTitleTemplate().trim());
    }
    writeChannels(entity, body.getChannels());
    entity.setUpdatedAt(OffsetDateTime.now());
  }

  /**
   * 写入渠道映射：JSONB 主列 + 遗留单渠道列同步。
   */
  private void writeChannels(NoticeTemplate entity, Map<String, String> channels) {
    Map<String, String> cleaned = new LinkedHashMap<>();
    if (channels != null) {
      channels.forEach((key, value) -> {
        if (key != null && !key.isBlank()) {
          cleaned.put(key.trim(), value == null ? "" : value);
        }
      });
    }
    try {
      entity.setChannelContents(objectMapper.writeValueAsString(cleaned));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("模板渠道内容序列化失败", e);
    }

    String primary = null;
    String primaryContent = null;
    for (Map.Entry<String, String> entry : cleaned.entrySet()) {
      if (entry.getValue() != null && !entry.getValue().isBlank()) {
        primary = entry.getKey();
        primaryContent = entry.getValue();
        break;
      }
    }
    if (primary == null && !cleaned.isEmpty()) {
      primary = cleaned.keySet().iterator().next();
      primaryContent = cleaned.get(primary);
    }
    entity.setChannel(primary);
    entity.setContentTemplate(primaryContent);
  }

  /**
   * 读取渠道映射。JSONB 为空时回退为遗留单渠道列，兼容种子数据 NT-001..NT-005。
   */
  @SuppressWarnings("unchecked")
  private Map<String, String> readChannels(NoticeTemplate entity) {
    String json = entity.getChannelContents();
    if (json != null && !json.isBlank()) {
      try {
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        if (parsed != null && !parsed.isEmpty()) {
          Map<String, String> result = new LinkedHashMap<>();
          parsed.forEach((k, v) -> result.put(k, v == null ? "" : String.valueOf(v)));
          return result;
        }
      } catch (Exception e) {
        log.warn("通知模板渠道映射解析失败，回退为单渠道读取: id={}", entity.getId(), e);
      }
    }
    Map<String, String> fallback = new LinkedHashMap<>();
    if (entity.getChannel() != null && !entity.getChannel().isBlank()) {
      fallback.put(entity.getChannel(), entity.getContentTemplate() == null ? "" : entity.getContentTemplate());
    }
    return fallback;
  }

  private Map<String, Object> toDto(NoticeTemplate entity) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("id", entity.getId());
    dto.put("templateCode", entity.getTemplateCode());
    dto.put("name", entity.getTemplateName());
    dto.put("hazardType", entity.getDisasterType());
    dto.put("level", entity.getLevel());
    dto.put("channels", readChannels(entity));
    dto.put("titleTemplate", entity.getTitleTemplate());
    dto.put("enabled", Boolean.TRUE.equals(entity.getEnabled()));
    dto.put("createdBy", entity.getCreatedBy());
    dto.put("createdAt", entity.getCreatedAt());
    dto.put("updatedAt", entity.getUpdatedAt());
    return dto;
  }

  private String generateTemplateCode() {
    for (int i = 0; i < 5; i++) {
      String code = "NT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
      if (repository.findByTemplateCode(code) == null) {
        return code;
      }
    }
    return "NT-" + System.currentTimeMillis();
  }

  private String currentUsername() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
      return auth.getName();
    }
    return null;
  }

  private Boolean parseStatus(String status) {
    if (status == null || status.isBlank()) return null;
    String v = status.trim().toLowerCase(Locale.ROOT);
    if ("enabled".equals(v) || "true".equals(v) || "1".equals(v)) return Boolean.TRUE;
    if ("disabled".equals(v) || "false".equals(v) || "0".equals(v)) return Boolean.FALSE;
    return null;
  }

  private String emptyToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  private boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private boolean hasAnyContent(Map<String, String> channels) {
    if (channels == null || channels.isEmpty()) return false;
    return channels.values().stream().anyMatch(v -> v != null && !v.isBlank());
  }
}
