package cn.disasterguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部数据源 API 配置。对应配置前缀 {@code disasterguard.external-data.*}。
 *
 * <p>所有数据源均为公开免费 API，无需任何账号/OAuth：
 * <ul>
 *   <li>Open-Meteo：ERA5 气象 + ERA5-Land 土壤湿度</li>
 *   <li>USGS FDSN：实时地震目录</li>
 *   <li>Open-Elevation：SRTM 30m 海拔</li>
 *   <li>GDACS：全球灾害警报</li>
 *   <li>OSM Overpass：承灾体暴露度（土地利用/路网/建筑物）</li>
 *   <li>NASA Giovanni：GPM IMERG / SMAP</li>
 *   <li>Copernicus Data Space STAC：Sentinel-2 影像元数据</li>
 * </ul>
 *
 * <pre>
 *   enabled            = true                                # 是否启用外部数据源
 *   timeoutSeconds     = 10                                  # HTTP 超时秒数
 *   cacheTtlSeconds    = 300                                 # 缓存 TTL（5 分钟）
 *   openMeteoUrl       = https://api.open-meteo.com/v1/forecast
 *   usgsUrl            = https://earthquake.usgs.gov/fdsnws/event/1/query
 *   openElevationUrl   = https://api.open-elevation.com/api/v1/lookup
 *   enabledSources     = openmeteo,usgs,openelevation,gdacs,osm,gpm,smap,copernicus
 * </pre>
 */
@ConfigurationProperties(prefix = "disasterguard.external-data")
public record ExternalDataProperties(
    boolean enabled,
    int timeoutSeconds,
    long cacheTtlSeconds,
    String openMeteoUrl,
    String usgsUrl,
    String openElevationUrl,
    String enabledSources
) {

  /** 默认配置：全部启用。 */
  public static ExternalDataProperties defaults() {
    return new ExternalDataProperties(
        true,
        10,
        300L,
        "https://api.open-meteo.com/v1/forecast",
        "https://earthquake.usgs.gov/fdsnws/event/1/query",
        "https://api.open-elevation.com/api/v1/lookup",
        "openmeteo,usgs,openelevation,gdacs,osm,gpm,smap,copernicus"
    );
  }

  /** 检查指定数据源是否启用。 */
  public boolean isSourceEnabled(String source) {
    if (!enabled) return false;
    if (enabledSources == null || enabledSources.isBlank()) return true;
    for (String s : enabledSources.split(",")) {
      if (s.trim().equalsIgnoreCase(source)) return true;
    }
    return false;
  }
}
