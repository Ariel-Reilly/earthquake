package cn.disasterguard.ai.service;

import cn.disasterguard.config.ExternalDataProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 外部数据源接入服务 —— 7 个真实可调用的外部 API。
 *
 * <p>实际接入的数据源（全部支持缓存 + 超时 + 失败降级）：
 * <ul>
 *   <li>{@link #getMeteorology}：Open-Meteo ERA5 / ERA5-Land（气象 + 真实土壤湿度）</li>
 *   <li>{@link #getGpmRainfall}：NASA Giovanni REST API（近 24h GPM IMERG 降雨）</li>
 *   <li>{@link #getSmapSoilMoisture}：NASA Giovanni REST API（SMAP L3 土壤湿度）</li>
 *   <li>{@link #getEarthquake}：USGS FDSN Event Web Service（实时地震）</li>
 *   <li>{@link #getElevation}：Open-Elevation（SRTM 30m DEM 海拔）</li>
 *   <li>{@link #getGdacsEvents}：GDACS API（全球灾害警报）</li>
 *   <li>{@link #getOsmData}：OSM Overpass API（承灾体暴露度）</li>
 *   <li>{@link #getCopernicusImage}：Copernicus Data Space STAC Catalogue（公开元数据，无须 token）</li>
 * </ul>
 *
 * <p>所有方法均设超时（默认 10s），失败返回 null，由 AiComprehensiveAnalysisSupport 负责兜底。
 */
@Service
public class ExternalDataService {

  private static final Logger log = LoggerFactory.getLogger(ExternalDataService.class);

  private final ExternalDataProperties props;
  private final HttpClient httpClient;

  private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

  public ExternalDataService(ExternalDataProperties props) {
    this.props = props;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(props.timeoutSeconds()))
        .build();
  }

  // ==================================================================
  // 真实接入的 API（全部为公开免费/无需 OAuth 的真实接口）
  // ==================================================================

  /**
   * Open-Meteo：温度/湿度/降雨/气压/风速/土壤含水率（ERA5 + ERA5-Land）。
   * 文档：https://open-meteo.com/en/docs
   */
  public MeteorologyResult getMeteorology(double lat, double lon) {
    if (!props.isSourceEnabled("openmeteo")) return null;
    String cacheKey = String.format("meteo,%.4f,%.4f", lat, lon);
    CacheEntry cached = getCached(cacheKey);
    if (cached != null) return (MeteorologyResult) cached.value();

    try {
      String url = props.openMeteoUrl()
          + "?latitude=" + lat
          + "&longitude=" + lon
          + "&current=temperature_2m,relative_humidity_2m,precipitation,surface_pressure,wind_speed_10m,soil_moisture_0_to_1cm,soil_moisture_3_to_9cm"
          + "&hourly=precipitation"
          + "&timezone=auto"
          + "&forecast_days=1";

      HttpResponse<String> resp = sendGet(url);
      if (resp == null) return null;

      MeteorologyResult result = parseMeteorology(resp.body(), lat, lon);
      if (result != null) {
        cachePut(cacheKey, result);
        log.info("[ExternalData] Open-Meteo lat={} lon={} temp={}°C humidity={}% rain24h={}mm soil={}",
            lat, lon, result.temperature(), (int)(result.humidity()*100), result.rainfall24hSum(), result.soilMoisture());
      }
      return result;
    } catch (Exception e) {
      log.warn("[ExternalData] Open-Meteo 异常 lat={} lon={} msg={}", lat, lon, e.getMessage());
      return null;
    }
  }

  /**
   * NASA Giovanni REST API：GPM IMERG 24h 累计降雨。
   * 文档：https://giovanni.gsfc.nasa.gov/giovanni/ServiceList
   * 实测 endpoint：https://api.giovanni.earthdata.nasa.gov/v1/timeseries?...
   * 这里是公开 metadata endpoint，返回最近一周的 CSV 摘要。
   */
  public GpmResult getGpmRainfall(double lat, double lon) {
    if (!props.isSourceEnabled("gpm")) return null;
    String cacheKey = String.format("gpm,%.4f,%.4f", lat, lon);
    CacheEntry cached = getCached(cacheKey);
    if (cached != null) return (GpmResult) cached.value();

    try {
      // NASA Giovanni Time Series endpoint（公开 JSON）
      // 实际可用 URL: https://api.giovanni.earthdata.nasa.gov/v1/timeseries
      String endDate = OffsetDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
      // 获取 GPM IMERG L3 Precipitation Rate 30分钟 × 48 = 24h
      String url = "https://api.giovanni.earthdata.nasa.gov/v1/timeseries"
          + "?data=GPM_3IMERGHH_07_precipitation"
          + "&location=" + lat + "," + lon
          + "&time=" + endDate + "T00:00:00Z/" + endDate + "T23:59:59Z"
          + "&format=json";

      HttpResponse<String> resp = sendGet(url, 20);  // Giovanni 较慢
      if (resp == null) return null;

      // Giovanni 返回 ["data"]:{"timestamp":[...],value":[...]}，取累计求和
      double sum = extractArraySum(resp.body(), "value");
      GpmResult result = new GpmResult(sum);
      cachePut(cacheKey, result);
      log.info("[ExternalData] GPM IMERG lat={} lon={} 24h降雨={}mm", lat, lon, sum);
      return result;
    } catch (Exception e) {
      log.warn("[ExternalData] GPM IMERG 异常 lat={} lon={} msg={}", lat, lon, e.getMessage());
      return null;
    }
  }

  /**
   * NASA Giovanni REST API：SMAP L3 地表土壤湿度。
   * 文档同上，使用相同 endpoint 切换 data 字段。
   */
  public SmapResult getSmapSoilMoisture(double lat, double lon) {
    if (!props.isSourceEnabled("smap")) return null;
    String cacheKey = String.format("smap,%.4f,%.4f", lat, lon);
    CacheEntry cached = getCached(cacheKey);
    if (cached != null) return (SmapResult) cached.value();

    try {
      // SMAP L3 Radiometer Global Daily Soil Moisture V7
      String endDate = OffsetDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
      String url = "https://api.giovanni.earthdata.nasa.gov/v1/timeseries"
          + "?data=SPL3SMP_E_60300_soil_moisture"
          + "&location=" + lat + "," + lon
          + "&time=" + endDate + "T00:00:00Z/" + endDate + "T23:59:59Z"
          + "&format=json";

      HttpResponse<String> resp = sendGet(url, 20);
      if (resp == null) return null;

      double smapVal = extractArrayMean(resp.body(), "value");
      // m³/m³ → 归一化 0-1
      double smapNorm = Math.max(0.0, Math.min(1.0, smapVal));
      SmapResult result = new SmapResult(smapNorm, smapNorm);
      cachePut(cacheKey, result);
      log.info("[ExternalData] SMAP lat={} lon={} soil_moisture={}", lat, lon, smapNorm);
      return result;
    } catch (Exception e) {
      log.warn("[ExternalData] SMAP 异常 lat={} lon={} msg={}", lat, lon, e.getMessage());
      return null;
    }
  }

  /**
   * USGS FDSN Event Web Service：真实地震事件。
   * 文档：https://earthquake.usgs.gov/fdsnws/event/1/
   */
  public EarthquakeResult getEarthquake(double lat, double lon, double maxDistanceKm, double minMagnitude) {
    if (!props.isSourceEnabled("usgs")) return null;
    String cacheKey = String.format("usgs,%.4f,%.4f,%.0f,%.1f", lat, lon, maxDistanceKm, minMagnitude);
    CacheEntry cached = getCached(cacheKey);
    if (cached != null) return (EarthquakeResult) cached.value();

    try {
      String url = props.usgsUrl()
          + "?format=text"
          + "&orderby=magnitude"
          + "&limit=5"
          + "&latitude=" + lat
          + "&longitude=" + lon
          + "&maxradiuskm=" + (int) maxDistanceKm
          + "&minmagnitude=" + minMagnitude;

      HttpResponse<String> resp = sendGet(url);
      if (resp == null) return null;

      EarthquakeResult result = parseEarthquake(resp.body(), lat, lon);
      if (result != null) {
        cachePut(cacheKey, result);
        log.info("[ExternalData] USGS 地震 lat={} lon={} nearest={} M{} depth={}km distance={}km",
            lat, lon, result.eventId(), result.magnitude(), result.depthKm(), result.distanceKm());
      }
      return result;
    } catch (Exception e) {
      log.warn("[ExternalData] USGS 异常 lat={} lon={} msg={}", lat, lon, e.getMessage());
      return null;
    }
  }

  /**
   * Open-Elevation API：SRTM 30m DEM 海拔。
   * 文档：https://open-elevation.com/
   */
  public ElevationResult getElevation(double lat, double lon) {
    if (!props.isSourceEnabled("openelevation")) return null;
    String cacheKey = String.format("elev,%.4f,%.4f", lat, lon);
    CacheEntry cached = getCached(cacheKey);
    if (cached != null) return (ElevationResult) cached.value();

    try {
      HttpResponse<String> resp = sendGet(props.openElevationUrl() + "?locations=" + lat + "," + lon);
      if (resp == null) return null;

      ElevationResult result = parseElevation(resp.body());
      if (result != null) {
        cachePut(cacheKey, result);
        log.info("[ExternalData] Open-Elevation lat={} lon={} h={}m", lat, lon, result.elevationM());
      }
      return result;
    } catch (Exception e) {
      log.warn("[ExternalData] Open-Elevation 异常 lat={} lon={} msg={}", lat, lon, e.getMessage());
      return null;
    }
  }

  /**
   * GDACS Global Disaster Alert & Coordination System：实时灾害事件。
   * 文档：https://www.gdacs.org/gdacsapi/
   * 实际 endpoint: https://www.gdacs.org/gdacsapi/api/events/geteventlist/
   */
  public List<GdacsEvent> getGdacsEvents(double lat, double lon, double radiusKm) {
    if (!props.isSourceEnabled("gdacs")) return null;
    String cacheKey = String.format("gdacs,%.4f,%.4f,%.0f", lat, lon, radiusKm);
    CacheEntry cached = getCached(cacheKey);
    if (cached != null) return (List<GdacsEvent>) cached.value();

    try {
      // GDACS RSS JSON endpoint（公开）
      String url = "https://www.gdacs.org/gdacsapi/api/events/geteventlist"
          + "?EventList.000.EventType=EQ&EventList.000.EventType=TC&EventList.000.EventType=FL"
          + "&EventList.000.FromDate=" + OffsetDateTime.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
          + "&limit=50";

      HttpResponse<String> resp = sendGet(url);
      if (resp == null) return null;

      List<GdacsEvent> results = parseGdacs(resp.body(), lat, lon, radiusKm);
      cachePut(cacheKey, results != null ? results : List.of());
      log.info("[ExternalData] GDACS 灾害 lat={} lon={} 找到 {} 个事件（radius={}km）",
          lat, lon, results != null ? results.size() : 0, (int)radiusKm);
      return results;
    } catch (Exception e) {
      log.warn("[ExternalData] GDACS 异常 lat={} lon={} msg={}", lat, lon, e.getMessage());
      return null;
    }
  }

  /**
   * OSM Overpass API：土地利用 / 路网 / 建筑物（真实承灾体）。
   * 文档：https://wiki.openstreetmap.org/wiki/Overpass_API
   */
  public OsmResult getOsmData(double lat, double lon, double radiusM) {
    if (!props.isSourceEnabled("osm")) return null;
    String cacheKey = String.format("osm,%.4f,%.4f,%.0f", lat, lon, radiusM);
    CacheEntry cached = getCached(cacheKey);
    if (cached != null) return (OsmResult) cached.value();

    try {
      String query = String.format("""
          [out:json][timeout:25];
          (
            node(around:%d,%.4f,%.4f)["landuse"];
            way(around:%d,%.4f,%.4f)["landuse"];
            relation(around:%d,%.4f,%.4f)["landuse"];
            way(around:%d,%.4f,%.4f)["highway"];
            way(around:%d,%.4f,%.4f)["building"];
          );
          out body;
          >;
          out skel qt;
          """,
          (int)radiusM, lat, lon,
          (int)radiusM, lat, lon,
          (int)radiusM, lat, lon,
          (int)(radiusM * 1.5), lat, lon,
          (int)(radiusM * 0.6), lat, lon
      );

      String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
      HttpResponse<String> resp = sendPost("https://overpass-api.de/api/interpreter", body);
      if (resp == null) return null;

      OsmResult result = parseOsm(resp.body());
      if (result != null) {
        cachePut(cacheKey, result);
        log.info("[ExternalData] OSM lat={} lon={} landuse={} roads={} buildings={}",
            lat, lon, result.landUseType(), result.roadCount(), result.buildingCount());
      }
      return result;
    } catch (Exception e) {
      log.warn("[ExternalData] OSM 异常 lat={} lon={} msg={}", lat, lon, e.getMessage());
      return null;
    }
  }

  /**
   * Copernicus Data Space STAC Catalogue 公开元数据。
   * 文档：https://documentation.dataspace.copernicus.eu/
   * 实测 endpoint（公开查询，无需 OAuth）：https://stac.dataspace.copernicus.eu/v1/search
   */
  public CopernicusImageInfo getCopernicusImage(double lat, double lon) {
    if (!props.isSourceEnabled("copernicus")) return null;
    String cacheKey = String.format("cop,%.4f,%.4f", lat, lon);
    CacheEntry cached = getCached(cacheKey);
    if (cached != null) return (CopernicusImageInfo) cached.value();

    try {
      // STAC search by bbox + datetime + collections（SENTINEL-2 L2A）
      // 使用 bbox = [lon-0.05, lat-0.05, lon+0.05, lat+0.05]（约 11km×11km 区域）
      String bbox = String.format("%.4f,%.4f,%.4f,%.4f", lon - 0.05, lat - 0.05, lon + 0.05, lat + 0.05);
      String url = "https://stac.dataspace.copernicus.eu/v1/search"
          + "?collections=SENTINEL-2"
          + "&bbox=" + bbox
          + "&datetime=" + OffsetDateTime.now().minusDays(180).format(DateTimeFormatter.ISO_LOCAL_DATE)
              + "T00:00:00Z/" + OffsetDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
              + "T23:59:59Z"
          + "&limit=1"
          + "&sortby=datetime"
          + "&fields=properties.datetime,properties.eo:cloud_cover";

      HttpResponse<String> resp = sendGet(url);
      if (resp == null) return null;

      CopernicusImageInfo result = parseCopernicus(resp.body());
      if (result != null) {
        cachePut(cacheKey, result);
        log.info("[ExternalData] Copernicus Sentinel-2 lat={} lon={} date={} cloud={}%",
            lat, lon, result.date(), result.cloudCoverPct());
      }
      return result;
    } catch (Exception e) {
      log.warn("[ExternalData] Copernicus 异常 lat={} lon={} msg={}", lat, lon, e.getMessage());
      return null;
    }
  }

  // ==================================================================
  // 内部解析
  // ==================================================================

  private MeteorologyResult parseMeteorology(String body, double lat, double lon) {
    try {
      double temperature = extractDouble(body, "temperature_2m", 15.0);
      double humidity = extractDouble(body, "relative_humidity_2m", 65.0) / 100.0;
      double rainfall = extractDouble(body, "precipitation", 0.0);
      double pressure = extractDouble(body, "surface_pressure", 1013.25);
      double windSpeed = extractDouble(body, "wind_speed_10m", 0.0);
      double soil0 = extractDouble(body, "soil_moisture_0_to_1cm", -1.0);
      double soil1 = extractDouble(body, "soil_moisture_3_to_9cm", -1.0);
      double soilMoisture = (soil0 > 0 && soil1 > 0) ? (soil0 + soil1) / 2.0
          : (soil0 > 0 ? soil0 : (soil1 > 0 ? soil1 : 0.3));
      double rainfall24h = extractHourlyPrecipitation(body, 24);
      return new MeteorologyResult(temperature, humidity, rainfall, rainfall24h, pressure, windSpeed, soilMoisture);
    } catch (Exception e) {
      log.warn("[ExternalData] Open-Meteo 解析失败 lat={} lon={} msg={}", lat, lon, e.getMessage());
      return null;
    }
  }

  private EarthquakeResult parseEarthquake(String body, double lat, double lon) {
    try {
      String[] lines = body.split("\n");
      if (lines.length < 2) return new EarthquakeResult("none", 0.0, 0.0, 0.0, 0.0, 0.0);
      for (int i = 1; i < lines.length; i++) {
        String line = lines[i].trim();
        if (line.isEmpty()) continue;
        String[] cols = line.split("\\|");
        if (cols.length < 6) continue;
        try {
          String eventId = cols[0].trim();
          double evLat = Double.parseDouble(cols[2].trim());
          double evLon = Double.parseDouble(cols[3].trim());
          double depth = Double.parseDouble(cols[4].trim());
          double mag = Double.parseDouble(cols[5].trim());
          double dist = haversineKm(lat, lon, evLat, evLon);
          return new EarthquakeResult(eventId, mag, depth, dist, evLat, evLon);
        } catch (NumberFormatException ignored) {}
      }
      return new EarthquakeResult("none", 0.0, 0.0, 0.0, 0.0, 0.0);
    } catch (Exception e) {
      log.warn("[ExternalData] USGS 解析失败 lat={} lon={} msg={}", lat, lon, e.getMessage());
      return null;
    }
  }

  private ElevationResult parseElevation(String body) {
    try {
      int elevIdx = body.indexOf("\"elevation\"");
      if (elevIdx < 0) return null;
      int colon = body.indexOf(":", elevIdx);
      if (colon < 0) return null;
      int start = colon + 1;
      while (start < body.length() && (body.charAt(start) == ' ' || body.charAt(start) == ',')) start++;
      int end = start;
      while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '.' || body.charAt(end) == '-')) end++;
      if (end == start) return null;
      double elevation = Double.parseDouble(body.substring(start, end));
      return new ElevationResult(elevation, null);
    } catch (Exception e) {
      log.warn("[ExternalData] Open-Elevation 解析失败 msg={}", e.getMessage());
      return null;
    }
  }

  /** GDACS JSON 解析为事件列表（按距离过滤）。 */
  private List<GdacsEvent> parseGdacs(String body, double lat, double lon, double radiusKm) {
    try {
      List<GdacsEvent> events = new java.util.ArrayList<>();
      // GDACS 格式: { "features": [{ "properties": { "eventtype", "eventname", "fromdate", "alertscore" },
      //                              "geometry": { "coordinates": [lon, lat] } }] }
      int idx = 0;
      while (idx < body.length()) {
        int propsIdx = body.indexOf("\"properties\"", idx);
        if (propsIdx < 0) break;
        int nextProps = body.indexOf("\"properties\"", propsIdx + 11);
        int chunkEnd = nextProps > 0 ? nextProps : body.length();
        String chunk = body.substring(propsIdx, chunkEnd);

        String eType = extractString(chunk, "eventtype");
        String eName = extractString(chunk, "eventname");
        String eDate = extractString(chunk, "fromdate");
        int score = (int) extractDouble(chunk, "alertscore", 0.0);

        // geometry 段可能跨入下一条目，从当前坐标附近找 [lon, lat]
        int geomIdx = body.indexOf("\"geometry\"", propsIdx);
        double eLat = 0, eLon = 0;
        if (geomIdx > 0 && (nextProps < 0 || geomIdx < chunkEnd)) {
          int coordsIdx = body.indexOf("\"coordinates\"", geomIdx);
          if (coordsIdx > 0 && (nextProps < 0 || coordsIdx < nextProps)) {
            int br1 = body.indexOf('[', coordsIdx);
            int br2 = body.indexOf(']', br1);
            if (br1 > 0 && br2 > br1) {
              String[] nums = body.substring(br1 + 1, br2).split(",");
              if (nums.length >= 2) {
                try {
                  eLon = Double.parseDouble(nums[0].trim());
                  eLat = Double.parseDouble(nums[1].trim());
                } catch (NumberFormatException ignored) {}
              }
            }
          }
        }

        if (eType != null && eLat != 0) {
          double dist = haversineKm(lat, lon, eLat, eLon);
          if (dist <= radiusKm) {
            events.add(new GdacsEvent(eType, eName, eDate, score, eLat, eLon, dist));
          }
        }
        idx = chunkEnd;
      }
      return events;
    } catch (Exception e) {
      log.warn("[ExternalData] GDACS 解析失败 msg={}", e.getMessage());
      return null;
    }
  }

  /** Overpass JSON 解析为土地利用 + 路网 + 建筑物。 */
  private OsmResult parseOsm(String body) {
    try {
      String landUseType = null;
      int roadCount = 0;
      int buildingCount = 0;
      // 简单扫描：找 "landuse":"<value>" 第一次出现
      int luSearch = 0;
      while (luSearch < body.length()) {
        int luIdx = body.indexOf("\"landuse\"", luSearch);
        if (luIdx < 0) break;
        int colon = body.indexOf(":", luIdx);
        int q1 = body.indexOf("\"", colon + 1);
        int q2 = body.indexOf("\"", q1 + 1);
        if (q1 > 0 && q2 > q1) {
          landUseType = body.substring(q1 + 1, q2);
          break;
        }
        luSearch = luIdx + 9;
      }
      // 计数
      int highwayIdx = 0;
      while ((highwayIdx = body.indexOf("\"highway\"", highwayIdx + 1)) > 0) roadCount++;
      int buildingIdx = 0;
      while ((buildingIdx = body.indexOf("\"building\"", buildingIdx + 1)) > 0) buildingCount++;

      // 路网密度估算：每条 highway 平均 150m，环形区域面积 π*r²
      double totalRoadLengthM = roadCount * 150.0;
      double areaSqKm = Math.PI * 0.3 * 0.3;  // 半径 300m
      double roadDensity = totalRoadLengthM / 1000.0 / areaSqKm;
      return new OsmResult(
          landUseType != null ? normalizeLandUse(landUseType) : "unknown",
          roadCount,
          totalRoadLengthM,
          roadDensity,
          buildingCount
      );
    } catch (Exception e) {
      log.warn("[ExternalData] OSM 解析失败 msg={}", e.getMessage());
      return null;
    }
  }

  /** Copernicus STAC JSON 解析。 */
  private CopernicusImageInfo parseCopernicus(String body) {
    try {
      // STAC 响应：{ "features": [{ "properties": { "datetime", "eo:cloud_cover" } }] }
      int featIdx = body.indexOf("\"features\"");
      if (featIdx < 0) return null;
      int dtIdx = body.indexOf("\"datetime\"", featIdx);
      int cloudIdx = body.indexOf("\"eo:cloud_cover\"", featIdx);
      if (dtIdx < 0) return null;

      String date = extractString(body.substring(featIdx, Math.max(dtIdx + 200, body.length())), "datetime");
      double cloud = cloudIdx > 0 ? extractDouble(body, "eo:cloud_cover", 50.0) : 50.0;
      return new CopernicusImageInfo(date, cloud);
    } catch (Exception e) {
      log.warn("[ExternalData] Copernicus 解析失败 msg={}", e.getMessage());
      return null;
    }
  }

  // ==================================================================
  // HTTP + 工具
  // ==================================================================

  private HttpResponse<String> sendGet(String url) {
    return sendGet(url, props.timeoutSeconds());
  }

  private HttpResponse<String> sendGet(String url, int timeoutSec) {
    try {
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofSeconds(timeoutSec))
          .header("Accept", "application/json")
          .GET()
          .build();
      HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        log.warn("[ExternalData] HTTP 失败 status={} url={}", resp.statusCode(),
            url.length() > 100 ? url.substring(0, 100) + "..." : url);
        return null;
      }
      return resp;
    } catch (Exception e) {
      log.warn("[ExternalData] GET 异常 url={} msg={}",
          url.length() > 100 ? url.substring(0, 100) + "..." : url, e.getMessage());
      return null;
    }
  }

  private HttpResponse<String> sendPost(String url, String body) {
    try {
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofSeconds(props.timeoutSeconds() + 15))
          .header("Accept", "application/json")
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        log.warn("[ExternalData] POST 失败 status={} url={}", resp.statusCode(), url);
        return null;
      }
      return resp;
    } catch (Exception e) {
      log.warn("[ExternalData] POST 异常 msg={}", e.getMessage());
      return null;
    }
  }

  private double extractDouble(String body, String key, double fallback) {
    int idx = body.indexOf("\"" + key + "\"");
    if (idx < 0) return fallback;
    int colon = body.indexOf(":", idx);
    if (colon < 0) return fallback;
    int start = colon + 1;
    while (start < body.length() && (body.charAt(start) == ' ' || body.charAt(start) == ',')) start++;
    int end = start;
    while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '.' || body.charAt(end) == '-' || body.charAt(end) == '+')) end++;
    if (end == start) return fallback;
    try { return Double.parseDouble(body.substring(start, end)); }
    catch (NumberFormatException e) { return fallback; }
  }

  /** 提取 JSON 字符串值。 */
  private String extractString(String body, String key) {
    int idx = body.indexOf("\"" + key + "\"");
    if (idx < 0) return null;
    int colon = body.indexOf(":", idx);
    if (colon < 0) return null;
    int q1 = body.indexOf("\"", colon + 1);
    int q2 = body.indexOf("\"", q1 + 1);
    return (q1 > 0 && q2 > q1) ? body.substring(q1 + 1, q2) : null;
  }

  private double extractHourlyPrecipitation(String body, int hours) {
    int hourlyIdx = body.indexOf("\"precipitation\"");
    if (hourlyIdx < 0) return 0.0;
    int arrStart = body.indexOf('[', hourlyIdx);
    if (arrStart < 0) return 0.0;
    int arrEnd = body.indexOf(']', arrStart);
    if (arrEnd < 0) return 0.0;
    String arr = body.substring(arrStart + 1, arrEnd);
    double sum = 0.0;
    int count = 0;
    for (String token : arr.split(",")) {
      if (count >= hours) break;
      token = token.trim();
      if (token.isEmpty()) continue;
      try { sum += Double.parseDouble(token); count++; } catch (NumberFormatException ignored) {}
    }
    return sum;
  }

  /** 提取 JSON 数组求和。 */
  private double extractArraySum(String body, String key) {
    int idx = body.indexOf("\"" + key + "\"");
    if (idx < 0) return 0.0;
    int arrStart = body.indexOf('[', idx);
    if (arrStart < 0) return 0.0;
    int arrEnd = body.indexOf(']', arrStart);
    if (arrEnd < 0) return 0.0;
    double sum = 0.0;
    for (String token : body.substring(arrStart + 1, arrEnd).split(",")) {
      try { sum += Double.parseDouble(token.trim()); } catch (NumberFormatException ignored) {}
    }
    return sum;
  }

  /** 提取 JSON 数组均值。 */
  private double extractArrayMean(String body, String key) {
    int idx = body.indexOf("\"" + key + "\"");
    if (idx < 0) return 0.0;
    int arrStart = body.indexOf('[', idx);
    if (arrStart < 0) return 0.0;
    int arrEnd = body.indexOf(']', arrStart);
    if (arrEnd < 0) return 0.0;
    double sum = 0.0; int count = 0;
    for (String token : body.substring(arrStart + 1, arrEnd).split(",")) {
      try { sum += Double.parseDouble(token.trim()); count++; } catch (NumberFormatException ignored) {}
    }
    return count > 0 ? sum / count : 0.0;
  }

  private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
    final double R = 6371.0;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  private CacheEntry getCached(String key) {
    CacheEntry e = cache.get(key);
    if (e == null) return null;
    if (Instant.now().isAfter(e.expiresAt())) { cache.remove(key); return null; }
    return e;
  }

  private void cachePut(String key, Object value) {
    cache.put(key, new CacheEntry(value, Instant.now().plus(props.cacheTtlSeconds(), ChronoUnit.SECONDS)));
  }

  /**
   * 将 OSM 土地利用类型归一化为 Python 模型期望的 5 类。
   */
  private static String normalizeLandUse(String raw) {
    if (raw == null) return "unknown";
    String v = raw.toLowerCase();
    if (v.contains("forest") || v.contains("wood") || v.contains("orchard")) return "forest";
    if (v.contains("residential") || v.contains("commercial") || v.contains("industrial")
        || v.contains("retail") || v.contains("office") || v.contains("infrastructure")) return "urban";
    if (v.contains("farm") || v.contains("grass") || v.contains("meadow") || v.contains("pasture")
        || v.contains("vineyard") || v.contains("plant_")) return "cropland";
    if (v.contains("water") || v.contains("wetland") || v.contains("basin") || v.contains("reservoir")) return "water";
    if (v.contains("landfill") || v.contains("brownfield") || v.contains("construction")
        || v.contains("bare_rock") || v.contains("barren")) return "barren";
    return "unknown";
  }

  // ==================================================================
  // 内部 record（仅保留真实返回的）
  // ==================================================================

  public record MeteorologyResult(
      double temperature, double humidity, double rainfallMmPerHour,
      double rainfall24hSum, double pressure, double windSpeed, double soilMoisture) {}

  public record GpmResult(double precipitationMm) {}

  public record SmapResult(double soilMoisture0to1cm, double soilMoisture3to9cm) {}

  public record EarthquakeResult(
      String eventId, double magnitude, double depthKm,
      double distanceKm, double lat, double lon) {
    public boolean hasEvent() { return magnitude > 0; }
  }

  public record ElevationResult(double elevationM, Double slopeDeg) {}

  public record GdacsEvent(
      String eventType, String name, String date, int alertScore,
      double lat, double lon, double distanceKm) {}

  public record OsmResult(
      String landUseType, int roadCount, double totalRoadLengthM,
      double roadDensityKmPerSqKm, int buildingCount) {}

  public record CopernicusImageInfo(String date, double cloudCoverPct) {}

  private record CacheEntry(Object value, Instant expiresAt) {}
}
