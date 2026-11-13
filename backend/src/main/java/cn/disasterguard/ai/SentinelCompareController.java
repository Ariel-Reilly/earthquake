package cn.disasterguard.ai;

import cn.disasterguard.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 时相对比（灾前/灾后）网关代理 — B1（2026-11-13，甲方决策「时相对比必做」）。
 *
 * <p>ai-service 的 {@code /api/ai/sentinel/compose|jobs|diff|diff-jobs} 已完整实现，
 * 但浏览器直连 8001 存在三个交付问题：生产环境端口不可达、ai-service 无 JWT 校验
 * （见 KNOWN_LIMITATIONS C5）、合成图 URL 是 {@code http://localhost:8001/sentinel_files/...}
 * 或 {@code file://...}（见 C3）。本控制器把整条链路收回 Java 网关：
 *
 * <ul>
 *   <li>所有请求都走 {@code /api/ai/sentinel/**}，由 {@code satellite:view} 权限码 + JWT 保护；</li>
 *   <li>把上游返回的图片 URL 重写为同源 {@code /api/ai/sentinel/image/{filename}}，
 *       并由本控制器代理二进制，避免暴露 8001 与 CSP {@code img-src 'self'} 冲突；</li>
 *   <li>上游错误转成可读中文 + 原始状态码，AI 服务不可达统一 502。</li>
 * </ul>
 *
 * <p>注意不要与 {@code AiDeprecatedGapController} 的 501 占位混淆：那只覆盖
 * {@code /api/ai/drift/**} 与 {@code /api/ai/multimodal/**}。
 */
@RestController
@RequestMapping("/api/ai/sentinel")
@PreAuthorize("hasAuthority('satellite:view')")
public class SentinelCompareController {

  private static final Logger log = LoggerFactory.getLogger(SentinelCompareController.class);
  private static final String IMAGE_PREFIX = "/api/ai/sentinel/image/";
  private static final Pattern SAFE_FILENAME =
      Pattern.compile("^[A-Za-z0-9._-]+\\.(?:png|jpe?g|tiff?)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern IMAGE_FILENAME =
      Pattern.compile("([A-Za-z0-9._-]+\\.(?:png|jpe?g|tiff?))$", Pattern.CASE_INSENSITIVE);

  private final RestClient client;
  private final RestClient imageClient;
  private final ObjectMapper objectMapper;

  public SentinelCompareController(AiProperties properties, ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5_000);
    factory.setReadTimeout(60_000);
    String base = properties.serviceBaseUrl().replaceAll("/+$", "");
    this.client = RestClient.builder().baseUrl(base).requestFactory(factory).build();
    // 图片静态目录挂在 ai-service 根路径 /sentinel_files，而不是 /api/ai 下。
    String origin = URI.create(base).resolve("/").toString().replaceAll("/+$", "");
    this.imageClient = RestClient.builder().baseUrl(origin).requestFactory(factory).build();
  }

  /** 提交一期 Sentinel 合成任务（灾前 / 灾后各一次）。 */
  @PostMapping("/compose")
  public ResponseEntity<?> compose(@RequestBody(required = false) Map<String, Object> body) {
    ResponseEntity<?> invalid = validateBbox(body);
    if (invalid != null) {
      return invalid;
    }
    return post("/sentinel/compose", body);
  }

  /** 查询合成任务状态。 */
  @GetMapping("/jobs/{jobId}")
  public ResponseEntity<?> job(@PathVariable String jobId) {
    return get("/sentinel/jobs/" + encode(jobId));
  }

  /** 取消合成任务。 */
  @PostMapping("/jobs/{jobId}/cancel")
  public ResponseEntity<?> cancel(@PathVariable String jobId) {
    return post("/sentinel/jobs/" + encode(jobId) + "/cancel", Map.of());
  }

  /** 提交两期差异计算任务。 */
  @PostMapping("/diff")
  public ResponseEntity<?> diff(@RequestBody(required = false) Map<String, Object> body) {
    ResponseEntity<?> invalid = validateBbox(body);
    if (invalid != null) {
      return invalid;
    }
    return post("/sentinel/diff", body);
  }

  /** 查询差异任务状态。 */
  @GetMapping("/diff-jobs/{jobId}")
  public ResponseEntity<?> diffJob(@PathVariable String jobId) {
    return get("/sentinel/diff-jobs/" + encode(jobId));
  }

  /**
   * 代理 ai-service 落盘的合成图 / 差异热力图。
   *
   * <p>文件名严格白名单校验，防止路径穿越读取容器内任意文件。前端必须用带 JWT 的
   * fetch 取 blob（{@code <img src>} 不会自动携带 Authorization 头）。
   */
  @GetMapping("/image/{filename:.+}")
  public ResponseEntity<byte[]> image(@PathVariable String filename) {
    if (!SAFE_FILENAME.matcher(filename).matches()) {
      return ResponseEntity.badRequest().build();
    }
    try {
      byte[] bytes = imageClient.get()
          .uri("/sentinel_files/{filename}", filename)
          .retrieve()
          .body(byte[].class);
      if (bytes == null || bytes.length == 0) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
      }
      return ResponseEntity.ok().contentType(contentTypeOf(filename)).body(bytes);
    } catch (Exception e) {
      log.warn("时相对比图片代理失败 filename={}: {}", filename, e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
  }

  // ────────────────────────── 内部实现 ──────────────────────────

  private ResponseEntity<?> post(String path, Map<String, Object> body) {
    try {
      Map<String, Object> payload = body == null ? Map.of() : body;
      Map<String, Object> result = client.post()
          .uri(path)
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(Map.class);
      return ResponseEntity.ok(rewriteUrls(result));
    } catch (Exception e) {
      return failure(e);
    }
  }

  private ResponseEntity<?> get(String path) {
    try {
      Map<String, Object> result = client.get().uri(path).retrieve().body(Map.class);
      return ResponseEntity.ok(rewriteUrls(result));
    } catch (Exception e) {
      return failure(e);
    }
  }

  /** 上游错误透传状态码 + 可读中文；连接类异常统一 502。 */
  @SuppressWarnings("unchecked")
  private ResponseEntity<Map<String, Object>> failure(Exception e) {
    if (e instanceof RestClientResponseException rce) {
      String message = extractUpstreamMessage(rce.getResponseBodyAsString());
      return ResponseEntity.status(rce.getStatusCode().value())
          .body(Map.of("code", "SENTINEL_UPSTREAM_ERROR", "message", message));
    }
    log.warn("时相对比上游不可达: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(Map.of(
            "code", "SENTINEL_UNAVAILABLE",
            "message", "AI 卫星服务不可用，请确认 ai-service 已启动且 AI_SERVICE_BASE_URL 配置正确"));
  }

  private String extractUpstreamMessage(String raw) {
    if (raw != null && !raw.isBlank()) {
      try {
        JsonNode node = objectMapper.readTree(raw);
        JsonNode detail = node.get("detail");
        if (detail == null) {
          detail = node.get("message");
        }
        if (detail != null && !detail.asText().isBlank()) {
          return detail.asText();
        }
      } catch (Exception ignored) {
        // 非 JSON 响应：截断后直接展示，避免把整页 HTML 抛给前端
      }
      return raw.length() > 200 ? raw.substring(0, 200) : raw;
    }
    return "AI 卫星服务返回错误（无详情）";
  }

  /** bbox 必须是 4 个有限数字，避免把非法 AOI 直接丢给上游变成难以理解的 422。 */
  private ResponseEntity<?> validateBbox(Map<String, Object> body) {
    if (body == null || !body.containsKey("bbox")) {
      return null;
    }
    Object raw = body.get("bbox");
    if (!(raw instanceof List<?> list) || list.size() != 4
        || list.stream().anyMatch(x -> !(x instanceof Number n) || !Double.isFinite(n.doubleValue()))) {
      return ResponseEntity.badRequest()
          .body(Map.of("code", "INVALID_BBOX", "message", "bbox 必须是 4 个数值：[minLon, minLat, maxLon, maxLat]"));
    }
    return null;
  }

  /** 递归把上游图片 URL 改成同源代理地址。 */
  private Object rewriteUrls(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((k, v) -> out.put(String.valueOf(k), rewriteUrls(v)));
      return out;
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      list.forEach(v -> out.add(rewriteUrls(v)));
      return out;
    }
    if (value instanceof String s) {
      return rewriteOne(s);
    }
    return value;
  }

  private String rewriteOne(String value) {
    boolean fromUpstream = value.contains("/sentinel_files/")
        || value.startsWith("file://") || value.startsWith("mock://");
    if (!fromUpstream) {
      return value;
    }
    Matcher m = IMAGE_FILENAME.matcher(value);
    return m.find() ? IMAGE_PREFIX + m.group(1) : value;
  }

  private MediaType contentTypeOf(String filename) {
    String lower = filename.toLowerCase();
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
      return MediaType.IMAGE_JPEG;
    }
    if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
      return MediaType.parseMediaType("image/tiff");
    }
    return MediaType.IMAGE_PNG;
  }

  private String encode(String segment) {
    // jobId 是 UUID；只保留安全字符，避免路径注入
    return segment.replaceAll("[^A-Za-z0-9._-]", "");
  }
}
