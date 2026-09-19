package cn.disasterguard.weather.service;

import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.config.WeatherProperties;
import cn.disasterguard.weather.WeatherProductKind;
import cn.disasterguard.weather.WeatherProductKind.SourceKind;
import cn.disasterguard.weather.dto.WeatherLegendDto;
import cn.disasterguard.weather.dto.WeatherLegendDto.LegendGrade;
import cn.disasterguard.weather.dto.WeatherProductDto;
import cn.disasterguard.weather.dto.WeatherProductDto.WeatherProductsResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 国家气象数据服务（NSMC + NMC）。
 *
 * <p>职责：
 * <ul>
 *   <li>探测每个产品的"最新一帧"URL（NSMC XML / NMC 静态产品 URL 时序探测）</li>
 *   <li>用本地缓存避免每请求都打外网（TTL 由 {@code disasterguard.weather.cache-ttl-seconds} 控制）</li>
 *   <li>提供 REST 友好的元数据 DTO 给前端</li>
 *   <li>提供代理转发图片的方法（{@link #proxyImage}）作为浏览器 CORS 兜底</li>
 * </ul>
 *
 * <p>数据源说明：
 * <ul>
 *   <li>NSMC（国家卫星气象中心）风云四号 A XML 清单：
 *       <code>http://img.nsmc.org.cn/PORTAL/NSMC/XML/FY4A/...</code></li>
 *   <li>NMC（中央气象台）预渲染产品：
 *       <code>https://image.nmc.cn/product/YYYY/MM/DD/&lt;KIND&gt/medium/...</code></li>
 * </ul>
 *
 * <p>两个数据源都不需要 API key（官方公开资源），但需要"外网可达"且 NMC 服务器对高频探测敏感，
 * 因此：
 * <ul>
 *   <li>默认缓存 5 分钟（同一产品的探测结果复用）</li>
 *   <li>探测只发 HTTP HEAD，单次超时 3 秒</li>
 *   <li>探测候选数量限制在最近 100 个时间档（覆盖回退到 600 分钟以前，足够断网恢复场景）</li>
 * </ul>
 */
@Service
public class WeatherService {

  private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

  // ============================ 数据源基地址 ============================
  private static final String NSMC_MANIFEST_BASE = "http://img.nsmc.org.cn/PORTAL/NSMC/XML";
  private static final String NMC_PRODUCT_BASE = "https://image.nmc.cn";

  // ============================ HTTP 探测参数 ============================
  private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final int MAX_NMC_SLOTS = 100;

  // ============================ 缓存 ============================
  private static final DateTimeFormatter NMC_TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

  private final HttpClient httpClient;
  private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
  private final long cacheTtlSeconds;
  private final boolean cacheEnabled;
  private final boolean proxyEnabled;

  private ScheduledExecutorService warmer;

  public WeatherService(WeatherProperties properties) {
    this.cacheTtlSeconds = properties.cacheTtlSeconds();
    this.cacheEnabled = properties.cacheEnabled();
    this.proxyEnabled = properties.proxyEnabled();
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        // Java 17 HttpClient 默认 followRedirects=ALWAYS，可省去显式设置；这里不调用
        // .followRedirects(...) 是为了兼容 JDK 17（HttpClient.Redirection 枚举在 JDK 21 才引入）。
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  @PostConstruct
  void initWarmer() {
    // 启动后台线程，每 10 分钟预热一次缓存，避免冷启动首请求慢
    warmer = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "weather-cache-warmer");
      t.setDaemon(true);
      return t;
    });
    warmer.scheduleWithFixedDelay(() -> {
      try {
        for (WeatherProductKind kind : WeatherProductKind.values()) {
          findLatestInternal(kind, true);
        }
      } catch (Exception e) {
        log.warn("weather cache warmer failed: {}", e.getMessage());
      }
    }, 30, 600, TimeUnit.SECONDS);
  }

  @PreDestroy
  void shutdownWarmer() {
    if (warmer != null) {
      warmer.shutdownNow();
      warmer = null;
    }
  }

  // ============================================================
  //  对外 API
  // ============================================================

  /** 列出所有产品目录（不拉最新 URL，仅返回元信息 + 缓存中的最新帧）。 */
  public List<WeatherProductDto> listProducts() {
    List<WeatherProductDto> result = new ArrayList<>();
    for (WeatherProductKind kind : WeatherProductKind.values()) {
      result.add(findLatest(kind));
    }
    return result;
  }

  /** 一次性返回所有产品的最新帧响应。 */
  public WeatherProductsResponse fetchAllLatest() {
    List<WeatherProductDto> products = listProducts();
    OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    int available = (int) products.stream().filter(WeatherProductDto::ok).count();
    return new WeatherProductsResponse(products, now, products.size(), available);
  }

  /** 拉取单个产品的最新帧。 */
  public WeatherProductDto findLatest(WeatherProductKind kind) {
    return findLatestInternal(kind, false);
  }

  /** 强制忽略缓存，立即拉新（用于运维手动刷新）。 */
  public WeatherProductDto findLatestForce(WeatherProductKind kind) {
    cache.remove(kind.kind());
    return findLatestInternal(kind, true);
  }

  /**
   * 把图片源以 HTTP 流转发给前端（解决浏览器 CORS 限制的备用通道）。
   * 仅当 {@link #proxyEnabled} = true 时允许调用。
   *
   * @param kind 产品枚举
   * @param out 响应输出流
   */
  public void proxyImage(WeatherProductKind kind, OutputStream out) throws IOException {
    if (!proxyEnabled) {
      throw new AppException(403, "weather proxy disabled");
    }
    WeatherProductDto product = findLatest(kind);
    if (!product.ok() || product.url() == null || product.url().isBlank()) {
      throw new AppException(404, "weather product unavailable: " + kind.kind());
    }
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(product.url()))
          .timeout(Duration.ofSeconds(15))
          .header("User-Agent", "disasterguard-backend/1.0")
          .GET()
          .build();
      HttpResponse<InputStream> response = httpClient.send(request, BodyHandlers.ofInputStream());
      int status = response.statusCode();
      if (status >= 400) {
        throw new AppException(status, "upstream " + status);
      }
      try (InputStream in = response.body()) {
        in.transferTo(out);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AppException(503, "weather proxy interrupted");
    }
  }

  /** 返回所有产品图例，前端用于右侧色带展示。 */
  public List<WeatherLegendDto> getLegends() {
    return List.of(
        new WeatherLegendDto("satellite", "风云四号 云图", "可见光 + 红外合成", List.of(
            new LegendGrade("#00000000", "白色越亮 = 云顶越高 / 越厚"))),
        new WeatherLegendDto("satelliteDisk", "风云四号 全圆盘", "可见光 + 红外合成", List.of(
            new LegendGrade("#00000000", "白色越亮 = 云顶越高 / 越厚"))),
        new WeatherLegendDto("radar", "雷达回波", "dBZ", List.of(
            new LegendGrade("#6c4ad6", "≥ 45 dBZ 强降水"),
            new LegendGrade("#1d83e0", "35–45 dBZ 中等降水"),
            new LegendGrade("#27b07e", "20–35 dBZ 小雨"),
            new LegendGrade("#cad44e", "5–20 dBZ 微降水"))),
        new WeatherLegendDto("precip24h", "24h 降水量", "mm", List.of(
            new LegendGrade("#a80000", "≥ 100 mm 暴雨"),
            new LegendGrade("#d63b3b", "50–100 mm 大雨"),
            new LegendGrade("#e78c3b", "25–50 mm 中雨"),
            new LegendGrade("#dec331", "10–25 mm 小雨"),
            new LegendGrade("#3b78d6", "0.1–10 mm 微量"))),
        new WeatherLegendDto("snow24h", "24h 积雪", "cm", List.of(
            new LegendGrade("#ffffff", "≥ 20 cm 大雪"),
            new LegendGrade("#cce0ff", "10–20 cm 中雪"),
            new LegendGrade("#a8c4f0", "5–10 cm 小雪"),
            new LegendGrade("#dde6f0", "微量积雪"))),
        new WeatherLegendDto("drought", "农业干旱", "等级", List.of(
            new LegendGrade("#a80000", "特旱"),
            new LegendGrade("#e57b21", "重旱"),
            new LegendGrade("#f0c045", "中旱"),
            new LegendGrade("#f5e692", "轻旱")))
    );
  }

  // ============================================================
  //  缓存读写
  // ============================================================

  private WeatherProductDto findLatestInternal(WeatherProductKind kind, boolean forceRefresh) {
    if (cacheEnabled && !forceRefresh) {
      CacheEntry entry = cache.get(kind.kind());
      if (entry != null && !entry.isExpired(cacheTtlSeconds)) {
        return entry.value;
      }
    }
    WeatherProductDto result = probeLatest(kind);
    cache.put(kind.kind(), new CacheEntry(result, System.currentTimeMillis()));
    return result;
  }

  // ============================================================
  //  探测逻辑：按产品类型分派
  // ============================================================

  private WeatherProductDto probeLatest(WeatherProductKind kind) {
    OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    try {
      return switch (kind.sourceKind()) {
        case NSMC -> probeNsmcLatest(kind, now);
        case NMC -> probeNmcProductLatest(kind, now);
      };
    } catch (Exception e) {
      log.warn("weather probe failed kind={} err={}", kind.kind(), e.getMessage());
      return WeatherProductDto.failure(
          kind.kind(), kind.label(), kind.description(),
          kind.sourceKind().name().toLowerCase(),
          now, kind.extent(), kind.refreshIntervalMinutes(),
          e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  /** NSMC XML 清单模式。解析清单第一张图作为最新帧。 */
  private WeatherProductDto probeNsmcLatest(WeatherProductKind kind, OffsetDateTime fetchedAt) throws Exception {
    String xmlUrl = NSMC_MANIFEST_BASE + "/" + kind.resource();
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(xmlUrl))
        .timeout(HTTP_REQUEST_TIMEOUT)
        .header("User-Agent", "disasterguard-backend/1.0")
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
    int status = response.statusCode();
    if (status >= 400) {
      throw new AppException(status, "NSMC HTTP " + status);
    }
    String body = response.body();
    String url = null;
    String time = null;
    if (body != null && !body.isBlank()) {
      Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
          .parse(new org.xml.sax.InputSource(new java.io.StringReader(body)));
      NodeList imageList = doc.getElementsByTagName("image");
      if (imageList.getLength() == 0) {
        // 兜底：直接抓 <url> 顶层节点
        NodeList urlList = doc.getElementsByTagName("url");
        if (urlList.getLength() > 0) {
          url = urlList.item(0).getTextContent() == null ? null : urlList.item(0).getTextContent().trim();
        }
      } else {
        Element first = (Element) imageList.item(0);
        // Element#getAttribute 已经返回 String，不必 cast 到 Attr
        url = first.getAttribute("url");
        if (url != null && url.isBlank()) url = null;
        time = first.getAttribute("time");
      }
    }
    if (url == null || url.isBlank()) {
      throw new AppException(502, "NSMC manifest empty");
    }
    return WeatherProductDto.success(
        kind.kind(), kind.label(), kind.description(),
        kind.sourceKind().name().toLowerCase(),
        url, time, fetchedAt,
        kind.extent(), kind.refreshIntervalMinutes());
  }

  /**
     NMC 静态产品：构造"YYYY/MM/DD/HHMM"形式的 URL，按分钟倒推探测最近一帧。
     URL 模板在 {@link #buildNmcProductUrl}。
     通过 HTTP HEAD 请求验证 URL 可达；HEAD 失败时回退到 GET Range 请求取前 1KB。
     */
  private WeatherProductDto probeNmcProductLatest(WeatherProductKind kind, OffsetDateTime fetchedAt) {
    // 从当前时间开始，按产品刷新粒度倒推候选时间点
    int stepMinutes = Math.max(kind.refreshIntervalMinutes(), 6);
    java.time.ZonedDateTime base = fetchedAt.atZoneSameInstant(ZoneId.of("Asia/Shanghai"));
    // 起点对齐到 stepMinutes 倍数（向下取整）
    int minute = (base.getMinute() / stepMinutes) * stepMinutes;
    java.time.ZonedDateTime cursor = base.withMinute(minute).withSecond(0).withNano(0);
    for (int i = 0; i < MAX_NMC_SLOTS; i++) {
      java.time.ZonedDateTime slot = cursor.minusMinutes(i * stepMinutes);
      String yy = String.valueOf(slot.getYear());
      String mm = String.valueOf(slot.getMonthValue());
      String dd = String.valueOf(slot.getDayOfMonth());
      String hh = String.valueOf(slot.getHour());
      String mn = String.valueOf(slot.getMinute());
      String candidate = buildNmcProductUrl(kind, yy, mm, dd, hh, mn);
      if (candidate == null) {
        throw new AppException(500, "Unknown product kind: " + kind);
      }
      if (headOk(candidate)) {
        String timestamp = NMC_TIMESTAMP_FORMAT.format(slot.toInstant());
        return WeatherProductDto.success(
            kind.kind(), kind.label(), kind.description(),
            kind.sourceKind().name().toLowerCase(),
            candidate, timestamp, fetchedAt,
            kind.extent(), kind.refreshIntervalMinutes());
      }
    }
    throw new AppException(504, "NMC product " + kind.kind() + ": no available slot in " + (MAX_NMC_SLOTS * stepMinutes) + "m");
  }

  /** HTTP HEAD 探测；HEAD 不支持时（部分 CDN）回退到带 Range 的 GET。 */
  private boolean headOk(String url) {
    try {
      HttpRequest head = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(HTTP_REQUEST_TIMEOUT)
          .method("HEAD", HttpRequest.BodyPublishers.noBody())
          .build();
      HttpResponse<Void> headResp = httpClient.send(head, BodyHandlers.discarding());
      if (headResp.statusCode() == 200) return true;
      if (headResp.statusCode() == 404) return false;
    } catch (Exception e) {
      // HEAD 抛错时回退 GET Range
    }
    try {
      HttpRequest range = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(HTTP_REQUEST_TIMEOUT)
          .header("Range", "bytes=0-1023")
          .GET()
          .build();
      HttpResponse<byte[]> resp = httpClient.send(range, BodyHandlers.ofByteArray());
      int sc = resp.statusCode();
      // 200 / 206 Partial Content 都视为 OK
      return sc == 200 || sc == 206;
    } catch (Exception e) {
      return false;
    }
  }

  /** NMC URL 模板构造；与前端 weatherService.js 的 buildNmcProductUrl 字段对齐。 */
  private String buildNmcProductUrl(WeatherProductKind kind, String yy, String mm, String dd, String hh, String mn) {
    return switch (kind) {
      case SATELLITE, SATELLITE_DISK ->
          NMC_PRODUCT_BASE + "/product/" + yy + "/" + mm + "/" + dd + "/WXCL/medium/SEVP_NSMC_WXCL_ASC_E99_ACHN_LNO_PY_" + yy + mm + dd + hh + mn + "00000.JPG";
      case RADAR ->
          NMC_PRODUCT_BASE + "/product/" + yy + "/" + mm + "/" + dd + "/RDP/medium/SEVP_NSMC_RDP_ACHN_LNO_PY_" + yy + mm + dd + hh + mn + "00000.JPG";
      case PRECIP_24H ->
          NMC_PRODUCT_BASE + "/product/" + yy + "/" + mm + "/" + dd + "/RAIN24/medium/SEVP_NSMC_RAIN24_ACHN_LNO_PY_" + yy + mm + dd + hh + "00000000.jpg";
      case SNOW_24H ->
          NMC_PRODUCT_BASE + "/product/" + yy + "/" + mm + "/" + dd + "/SDP/medium/SEVP_NSMC_SDP_ACHN_LNO_PY_" + yy + mm + dd + "1200000000.JPG";
      case DROUGHT ->
          NMC_PRODUCT_BASE + "/product/" + yy + "/" + mm + "/" + dd + "/AGRI_DROUGHT/medium/SEVP_NSMC_AGRI_DROUGHT_ACHN_LNO_PY_" + yy + mm + dd + "1200000000.JPG";
    };
  }

  /** 检测服务是否启用代理模式（{@code /api/weather/image/{kind}} 路由开关）。 */
  public boolean proxyEnabled() {
    return proxyEnabled;
  }

  /** 把官方 URL 路径里的文件名解析出来（供日志/调试）。 */
  @SuppressWarnings("unused")
  private static String extractFilename(String url) {
    if (url == null) return "";
    Matcher m = Pattern.compile(".*/([^/?]+)$").matcher(url);
    return m.matches() ? m.group(1) : url;
  }

  // ============================================================
  //  内部类型
  // ============================================================

  /** 缓存项：保存最新一次探测结果与时间戳。 */
  private static final class CacheEntry {
    final WeatherProductDto value;
    final long storedAtMs;

    CacheEntry(WeatherProductDto value, long storedAtMs) {
      this.value = value;
      this.storedAtMs = storedAtMs;
    }

    boolean isExpired(long ttlSeconds) {
      return (System.currentTimeMillis() - storedAtMs) > TimeUnit.SECONDS.toMillis(ttlSeconds);
    }
  }
}