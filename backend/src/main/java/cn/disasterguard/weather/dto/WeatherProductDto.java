package cn.disasterguard.weather.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 单个气象产品的最新帧元数据。
 *
 * <p>字段设计原则：
 * <ul>
 *   <li>{@code ok} 标志拉取是否成功，失败时 {@code url} 为空但 {@code error} 有人话说明</li>
 *   <li>{@code timestamp} 取自数据源官方时间（YYYY-MM-DD HH:mm），不是服务器时间</li>
 *   <li>{@code extent} 用 6 个 double 表示外接矩形（比数组更友好）</li>
 * </ul>
 */
public record WeatherProductDto(
    String kind,
    String label,
    String description,
    String source,
    boolean ok,
    String url,
    String timestamp,
    OffsetDateTime fetchedAt,
    double minLon,
    double minLat,
    double maxLon,
    double maxLat,
    int refreshIntervalMinutes,
    String error
) {
  /** 成功结果工厂。 */
  public static WeatherProductDto success(String kind, String label, String description,
      String source, String url, String timestamp, OffsetDateTime fetchedAt,
      double[] extent, int refreshIntervalMinutes) {
    return new WeatherProductDto(
        kind, label, description, source,
        true, url, timestamp, fetchedAt,
        extent[0], extent[1], extent[2], extent[3],
        refreshIntervalMinutes,
        null
    );
  }

  /** 失败结果工厂（保留元信息便于 UI 显示）。 */
  public static WeatherProductDto failure(String kind, String label, String description,
      String source, OffsetDateTime fetchedAt, double[] extent,
      int refreshIntervalMinutes, String error) {
    return new WeatherProductDto(
        kind, label, description, source,
        false, null, null, fetchedAt,
        extent[0], extent[1], extent[2], extent[3],
        refreshIntervalMinutes,
        error
    );
  }

  /** 一次性返回多个产品的响应封装。 */
  public record WeatherProductsResponse(
      List<WeatherProductDto> products,
      OffsetDateTime fetchedAt,
      int total,
      int available
  ) {}
}