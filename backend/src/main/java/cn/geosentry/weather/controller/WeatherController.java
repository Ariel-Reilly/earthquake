package cn.geosentry.weather.controller;

import cn.geosentry.common.api.ApiResponse;
import cn.geosentry.common.exception.AppException;
import cn.geosentry.weather.WeatherProductKind;
import cn.geosentry.weather.dto.WeatherLegendDto;
import cn.geosentry.weather.dto.WeatherProductDto;
import cn.geosentry.weather.dto.WeatherProductDto.WeatherProductsResponse;
import cn.geosentry.weather.service.WeatherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 国家气象数据接入 REST API。
 *
 * <p>所有路由均为公共资源（无需 JWT），配置在 {@link cn.geosentry.config.SecurityConfig} 的白名单。
 *
 * <p>设计原则：
 * <ul>
 *   <li>全部元数据走 JSON，由前端缓存和节流</li>
 *   <li>图片源 URL 直接给前端用（NSMC / NMC 支持 GET，浏览器原生支持 image src）；同时提供
 *       <code>/image/{kind}</code> 代理接口作为 CORS 兜底（前端默认走直连，403 时回退代理）</li>
 *   <li>URL 时效由 {@link WeatherService} 缓存（默认 5 分钟）+ 后台预热保障</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/weather")
@Tag(name = "气象数据", description = "国家卫星气象中心 + 中央气象台数据接入")
public class WeatherController {

  private final WeatherService weatherService;

  public WeatherController(WeatherService weatherService) {
    this.weatherService = weatherService;
  }

  @GetMapping("/products")
  @Operation(summary = "列出全部气象产品的最新帧", description = "返回六种产品（卫星云图/全圆盘/雷达/24h 降水/24h 积雪/干旱）的最新可用元数据")
  public ApiResponse<WeatherProductsResponse> listProducts() {
    List<WeatherProductDto> products = weatherService.listProducts();
    int available = (int) products.stream().filter(WeatherProductDto::ok).count();
    WeatherProductsResponse payload = new WeatherProductsResponse(
        products,
        OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
        products.size(),
        available
    );
    return ApiResponse.success(payload);
  }

  @GetMapping("/products/latest")
  @Operation(summary = "拉取单个产品的最新帧", description = "kind 取值：satellite / satelliteDisk / radar / precip24h / snow24h / drought")
  public ApiResponse<WeatherProductDto> latest(@RequestParam("kind") String kindParam) {
    WeatherProductKind kind = WeatherProductKind.fromKind(kindParam);
    if (kind == null) {
      throw new AppException(400, "未知产品 kind: " + kindParam);
    }
    return ApiResponse.success(weatherService.findLatest(kind));
  }

  @PostMapping("/products/refresh")
  @Operation(summary = "强制刷新单个产品的最新帧", description = "忽略缓存，立即拉新一次。常用于运维手动刷新。")
  public ApiResponse<WeatherProductDto> refresh(@RequestParam("kind") String kindParam) {
    WeatherProductKind kind = WeatherProductKind.fromKind(kindParam);
    if (kind == null) {
      throw new AppException(400, "未知产品 kind: " + kindParam);
    }
    return ApiResponse.success("已刷新", weatherService.findLatestForce(kind));
  }

  @GetMapping("/legends")
  @Operation(summary = "获取全部产品的图例（颜色档位说明）")
  public ApiResponse<List<WeatherLegendDto>> legends() {
    return ApiResponse.success(weatherService.getLegends());
  }

  /**
   * 代理转发图片（CORS 兜底）。
   * 前端默认走直连（NSMC / NMC 通常允许 image 跨域），仅当浏览器拒绝时调用此接口。
   * 路径参数 kind 限定在白名单枚举内，避免被滥用为开放代理。
   */
  @GetMapping("/image/{kind}")
  public void proxyImage(@PathVariable("kind") String kindParam,
      HttpServletResponse response) throws IOException {
    if (!weatherService.proxyEnabled()) {
      throw new AppException(403, "weather proxy disabled");
    }
    WeatherProductKind kind = WeatherProductKind.fromKind(kindParam);
    if (kind == null) {
      throw new AppException(400, "未知产品 kind: " + kindParam);
    }
    WeatherProductDto product = weatherService.findLatest(kind);
    if (!product.ok() || product.url() == null) {
      throw new AppException(404, "weather product unavailable");
    }
    String lower = product.url().toLowerCase();
    MediaType mediaType = lower.endsWith(".png")
        ? MediaType.IMAGE_PNG
        : (lower.endsWith(".gif") ? MediaType.IMAGE_GIF : MediaType.IMAGE_JPEG);
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(mediaType.toString());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=300");
    response.setHeader("X-Weather-Product", kind.kind());
    response.setHeader("X-Weather-Source-Url", product.url());
    response.setHeader("X-Weather-Source-Time", product.timestamp() == null ? "" : product.timestamp());
    try (OutputStream out = response.getOutputStream()) {
      weatherService.proxyImage(kind, out);
    }
  }
}