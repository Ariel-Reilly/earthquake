package cn.disasterguard.ai.service;

import cn.disasterguard.ai.AiController;
import cn.disasterguard.ai.dto.DisasterAssessmentRequestDto;
import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.DeviceMetricRepository;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 综合分析因子聚合器 v4.0 —— 多源数据融合体系。
 *
 * <p>AI 智能巡检监测自然灾害需要多源数据融合，本系统整合以下六类核心数据：
 *
 * <h2>一、核心遥感影像数据（用于识别地表灾害特征）</h2>
 * <table border="1" cellpadding="4">
 *   <tr><th>数据名称</th><th>传感器/卫星</th><th>空间分辨率</th><th>时间分辨率</th><th>用途</th><th>推荐接入方式</th></tr>
 *   <tr><td>Sentinel-1 SAR</td><td>C波段雷达</td><td>10 m（GRD）</td><td>6天（双星）</td><td>洪水、滑坡、地表形变（InSAR）、地震同震位移</td><td>Copernicus Data Space API / GEE</td></tr>
 *   <tr><td>Sentinel-2 多光谱</td><td>MSI</td><td>10/20/60 m</td><td>5天（双星）</td><td>植被指数、水体提取、火灾迹地、干旱监测</td><td>Copernicus Data Space / Sentinel Hub</td></tr>
 *   <tr><td>Landsat 8/9</td><td>OLI/TIRS</td><td>15/30/100 m</td><td>16天</td><td>长期土地覆盖变化、历史对比</td><td>USGS EarthExplorer / GEE</td></tr>
 *   <tr><td>MODIS / VIIRS</td><td>中分辨率</td><td>250/500/1000 m</td><td>每日多次</td><td>大范围火点、气溶胶、洪水快速初判</td><td>NASA GIBS / FIRMS API / GEE</td></tr>
 *   <tr><td>高分辨率商业（可选）</td><td>Maxar/Planet</td><td><1 m</td><td>按需</td><td>建筑物损毁评估、精细滑坡边界</td><td>Planet API（学生可申请试用）</td></tr>
 * </table>
 *
 * <h2>二、气象与水文数据（用于触发条件分析和预警）</h2>
 * <table border="1" cellpadding="4">
 *   <tr><th>数据名称</th><th>内容</th><th>时空分辨率</th><th>用途</th><th>推荐接入来源</th></tr>
 *   <tr><td>ERA5 再分析</td><td>降雨、温度、风速、湿度、气压等</td><td>0.25° 逐小时</td><td>降雨阈值分析、干旱指数、台风路径环境场</td><td>ECMWF CDS API（免费注册）</td></tr>
 *   <tr><td>GPM IMERG</td><td>全球卫星降雨</td><td>0.1° 每30分钟</td><td>实时降雨监测、滑坡/洪水降雨触发</td><td>NASA GES DISC / EarthData API</td></tr>
 *   <tr><td>SMAP 土壤湿度</td><td>雷达/辐射计土壤水分</td><td>9 km / 3 km</td><td>干旱监测、滑坡前期土壤饱和度</td><td>NSIDC / EarthData</td></tr>
 *   <tr><td>河流水位（可选）</td><td>水文站实测</td><td>站点级</td><td>洪水演进验证</td><td>国家水文部门公开数据或 GRDC</td></tr>
 * </table>
 *
 * <h2>三、地形与地质数据（用于易发性评估和特征提取）</h2>
 * <table border="1" cellpadding="4">
 *   <tr><th>数据名称</th><th>内容</th><th>分辨率</th><th>用途</th><th>推荐接入来源</th></tr>
 *   <tr><td>SRTM DEM</td><td>全球数字高程模型</td><td>30 m</td><td>坡度、坡向、汇流分析、地形湿度指数</td><td>NASA EarthData / GEE</td></tr>
 *   <tr><td>Copernicus DEM</td><td>全球DEM</td><td>30 m（部分地区10 m）</td><td>更高精度地形分析</td><td>Copernicus Data Space（需注册）</td></tr>
 *   <tr><td>地质图/断层数据</td><td>岩性、构造</td><td>矢量</td><td>地震、滑坡易发性因子</td><td>USGS / 各国地质调查局</td></tr>
 *   <tr><td>土壤类型</td><td>土壤物理属性</td><td>矢量/栅格</td><td>渗透性、滑坡敏感性</td><td>FAO HWSD / ISRIC SoilGrids</td></tr>
 * </table>
 *
 * <h2>四、历史灾害与承灾体数据（用于模型训练和风险评估）</h2>
 * <table border="1" cellpadding="4">
 *   <tr><th>数据名称</th><th>内容</th><th>用途</th><th>推荐接入来源</th></tr>
 *   <tr><td>EM-DAT</td><td>全球灾害事件库（类型、时间、地点、损失）</td><td>模型训练标签、风险区划</td><td>EM-DAT 官网下载 CSV/API</td></tr>
 *   <tr><td>GDACS</td><td>全球灾害警报和影响评估（地震/洪水/台风）</td><td>实时灾害事件信息，用于触发分析</td><td>GDACS RSS/API</td></tr>
 *   <tr><td>Copernicus EMS</td><td>重大灾害快速测绘产品（洪水/火灾范围）</td><td>高精度验证数据</td><td>Copernicus EMS 网站下载</td></tr>
 *   <tr><td>WorldPop / GHS</td><td>人口密度分布</td><td>风险评估、影响人口估算</td><td>WorldPop API / GHSL</td></tr>
 *   <tr><td>OpenStreetMap</td><td>建筑物、道路、土地利用</td><td>承灾体暴露度、基础设施损毁评估</td><td>OSM API / Overpass API</td></tr>
 * </table>
 *
 * <h2>五、地面传感器与实时观测数据（用于快速预警和验证）</h2>
 * <table border="1" cellpadding="4">
 *   <tr><th>数据名称</th><th>内容</th><th>用途</th><th>推荐接入方式</th></tr>
 *   <tr><td>USGS 地震目录</td><td>实时地震事件（震级、位置、深度）</td><td>地震触发报警，SAR 形变分析触发</td><td>USGS FDSN Event Web Service API</td></tr>
 *   <tr><td>全球地震台网（可选）</td><td>地震波形（加速度）</td><td>强震动预警</td><td>IRIS DMC Web Services</td></tr>
 *   <tr><td>现场 IoT 传感器（可选）</td><td>雨量计、位移计、倾斜仪、水位计</td><td>高时间分辨率前兆监测</td><td>自建或第三方 IoT 平台</td></tr>
 * </table>
 *
 * <h2>六、辅助数据（可选，用于增强分析或可视化）</h2>
 * <table border="1" cellpadding="4">
 *   <tr><th>数据名称</th><th>内容</th><th>用途</th><th>推荐接入来源</th></tr>
 *   <tr><td>NASA Black Marble</td><td>夜间灯光</td><td>断电范围评估、灾后影响</td><td>NASA LAADS / GEE</td></tr>
 *   <tr><td>社交媒体 API</td><td>微博/Twitter/X 帖文</td><td>灾情现场文字/图片，验证模型</td><td>微博开放平台 / Twitter API v2</td></tr>
 *   <tr><td>行政区划矢量</td><td>国/省/市边界</td><td>结果分区统计</td><td>Natural Earth / GADM</td></tr>
 * </table>
 *
 * <p>各维度因子均设为可选——AI 模型对缺失因子有容错处理，保证任何维度缺失时仍能返回有效结果。
 */
@Service
public class AiComprehensiveAnalysisSupport {

  private static final Logger log = LoggerFactory.getLogger(AiComprehensiveAnalysisSupport.class);

  private final HazardPointRepository hazardRepository;
  private final MonitoringDeviceRepository deviceRepository;
  private final DeviceMetricRepository metricRepository;
  private final GeoContextResolver geoContextResolver;
  private final ExternalDataService externalDataService;

  public AiComprehensiveAnalysisSupport(HazardPointRepository hazardRepository,
                                        MonitoringDeviceRepository deviceRepository,
                                        DeviceMetricRepository metricRepository,
                                        GeoContextResolver geoContextResolver,
                                        ExternalDataService externalDataService) {
    this.hazardRepository = hazardRepository;
    this.deviceRepository = deviceRepository;
    this.metricRepository = metricRepository;
    this.geoContextResolver = geoContextResolver;
    this.externalDataService = externalDataService;
  }

  private static final java.util.Set<String> SUPPORTED_TYPES = java.util.Set.of(
      "滑坡", "崩塌", "泥石流", "地裂缝", "地面沉降", "塌陷");

  private static final Map<String, String> LAND_USE_MAP = java.util.Map.ofEntries(
      java.util.Map.entry("urban",     "urban"),
      java.util.Map.entry("cropland", "cropland"),
      java.util.Map.entry("forest",   "forest"),
      java.util.Map.entry("water",    "water"),
      java.util.Map.entry("barren",   "barren")
  );

  /**
   * 构造灾害评估请求（hazard 元数据 + 因子集合）。
   */
  public DisasterAssessmentRequestDto buildDisasterAssessmentRequest(String hazardCode) {
    HazardPoint hazard = hazardRepository.findByHazardCode(hazardCode);
    Map<String, Object> factors = aggregateFactors(hazardCode);
    String hazardType = hazard != null && SUPPORTED_TYPES.contains(hazard.getHazardType())
        ? hazard.getHazardType()
        : null;
    DisasterAssessmentRequestDto.HazardMeta meta = new DisasterAssessmentRequestDto.HazardMeta(
        hazardCode, hazardType);
    return new DisasterAssessmentRequestDto(meta, factors);
  }

  /**
   * 聚合一个 hazard 的全部七类因子。
   *
   * @return 不为 null 的因子表（key 全部小写命名，与 ai-service pydantic 模型对齐）
   */
  public Map<String, Object> aggregateFactors(String hazardCode) {
    Map<String, Object> factors = new LinkedHashMap<>();
    HazardPoint hazard = hazardRepository.findByHazardCode(hazardCode);

    // 提前取出经纬度，供多个数据源复用
    Double lat = hazard != null ? hazard.getLatitude() : null;
    Double lon = hazard != null ? hazard.getLongitude() : null;

    // [3] 地形地质 —— 易发性判断
    //     数据来源：
    //     ① Open-Elevation API（SRTM 30m DEM，免费无 key）—— 真实海拔优先
    //     ② GeoContextResolver（region_code 查表）—— 坡度/坡向/地质/距水/断层等表查
    //     ③ Copernicus DEM / SRTM（需注册/付费）—— 更高精度地形分析（预留）
    //     包含：海拔/坡度/坡向/曲率/土壤类型/植被覆盖/距水距离/地质岩性/断层分布
    //     用于评估灾害易发性，如坡度>30°易滑坡、断层附近地震风险高等
    // 先用 Open-Elevation（SRTM DEM）获取真实海拔
    Double elevationM = null;
    if (lat != null && lon != null) {
      var elevResult = externalDataService.getElevation(lat, lon);
      if (elevResult != null && elevResult.elevationM() > 0) {
        elevationM = elevResult.elevationM();
        log.debug("[AiComprehensive] Open-Elevation SRTM 海拔 lat={} lon={} h={}m", lat, lon, elevationM);
      }
    }
    // GeoContextResolver 提供坡度/坡向/地质等（海拔用真实值覆盖）
    GeoContextResolver.GeoContext geo = geoContextResolver.resolve(
        hazard != null ? hazard.getRegionCode() : null,
        hazard != null ? hazard.getHazardType() : null,
        lon, lat
    );
    factors.putAll(geo.toFactorMap());
    // 真实海拔覆盖查表值
    if (elevationM != null) {
      factors.put("elevation_m", elevationM);
    }
    // site_grade 从 riskLevel 映射
    factors.put("site_grade", AiController.riskLevelToGrade(hazard != null ? hazard.getRiskLevel() : null));
    // 扩展地形地质字段
    factors.put("aspect_deg", geo.aspectDeg());
    factors.put("curvature", geo.curvature());
    factors.put("fault_distance_m", geo.faultDistanceM());

    // [4] 实时传感器/IoT —— 近实时动态
    //     数据来源：DB monitoring_device_metrics 表（地震仪/GPS 位移/倾斜计/裂缝计/水位计/雨量站）
    //     多设备同一指标取最大值，实时捕捉前兆信号（如地震 P 波、边坡位移加速）
    //     用于秒级预警响应和动态风险评估
    List<MonitoringDevice> devices = deviceRepository.findByHazardCode(hazardCode);
    if (!devices.isEmpty()) {
      List<String> deviceCodes = devices.stream().map(MonitoringDevice::getDeviceCode).toList();
      var latestMetrics = metricRepository.findLatestByDeviceCodes(deviceCodes);
      Map<String, Double> maxByMetric = new HashMap<>();
      for (var m : latestMetrics) {
        maxByMetric.merge(m.getMetricCode(), m.getValue(), Math::max);
      }

      // 位移类
      if (maxByMetric.containsKey("disp_rate")) {
        factors.put("displacement_rate", maxByMetric.get("disp_rate"));
      }
      if (maxByMetric.containsKey("disp_total")) {
        factors.put("displacement_total", maxByMetric.get("disp_total"));
      }
      // 裂缝类
      if (maxByMetric.containsKey("crack_width")) {
        factors.put("crack_width", maxByMetric.get("crack_width"));
      }
      // 雨量类（聚合为 24h / 7d / 30d）
      if (maxByMetric.containsKey("rain_1h")) {
        Double rain24h = metricRepository.sumByHazardRecentHours(hazardCode, "rain_1h", 24);
        Double rain7d  = metricRepository.sumByHazardRecentHours(hazardCode, "rain_1h", 24 * 7);
        Double rain30d = metricRepository.sumByHazardRecentHours(hazardCode, "rain_1h", 24 * 30);
        factors.put("rainfall_24h", rain24h != null ? rain24h : maxByMetric.get("rain_1h"));
        factors.put("rainfall_7d",  rain7d  != null ? rain7d  : 0.0);
        factors.put("rainfall_30d", rain30d != null ? rain30d : 0.0);
      }
      // 传感器扩展字段
      if (maxByMetric.containsKey("tilt_angle")) {
        factors.put("tilt_angle", maxByMetric.get("tilt_angle"));
      }
      if (maxByMetric.containsKey("water_level")) {
        factors.put("water_level", maxByMetric.get("water_level"));
      }
    }

    // [2] 气象水文 —— 触发条件
    //     数据来源：
    //     ① DB 雨量站 rain_1h 聚合（DB 有数据时优先，实时性最高）
    //     ② Open-Meteo API（ERA5 再分析 + ERA5-Land 土壤湿度，0.25°/h，免费无 key）
    //     ③ NASA GPM IMERG（0.1°/30min，卫星降雨，需 EarthData 账号）
    //     包含：温度/湿度/降雨/气压/土壤含水率（ERA5-Land 真实数据）/风速
    if (!factors.containsKey("rainfall_24h") && lat != null && lon != null) {
      var meteo = externalDataService.getMeteorology(lat, lon);
      if (meteo != null) {
        factors.put("temperature", meteo.temperature());
        factors.put("humidity", meteo.humidity());
        factors.put("rainfall_24h", meteo.rainfall24hSum());
        // ERA5-Land 土壤湿度（0-1cm + 3-9cm 真实观测，不是湿度推算）
        factors.put("soil_moisture", meteo.soilMoisture());
        if (!factors.containsKey("rainfall_7d") && lat != null && lon != null) {
          // 接入真实 NASA GPM IMERG 24h 降雨
          var gpm = externalDataService.getGpmRainfall(lat, lon);
          factors.put("rainfall_7d", gpm != null && gpm.precipitationMm() > 0 ? gpm.precipitationMm() : meteo.rainfall24hSum() * 3.0);
        }
        if (!factors.containsKey("soil_moisture_smap") && lat != null && lon != null) {
          // 接入真实 NASA SMAP 土壤湿度作为对比维度
          var smap = externalDataService.getSmapSoilMoisture(lat, lon);
          if (smap != null) {
            factors.put("soil_moisture_smap", smap.soilMoisture3to9cm());
          }
        }
        if (!factors.containsKey("wind_speed")) {
          factors.put("wind_speed", meteo.windSpeed());
        }
        log.debug("[AiComprehensive] 气象 lat={} lon={} temp={}°C humidity={}% rain24h={}mm soil={}",
            lat, lon, meteo.temperature(), (int)(meteo.humidity()*100),
            meteo.rainfall24hSum(), String.format("%.4f", meteo.soilMoisture()));
      }
    }

    // [4] 实时传感器/IoT —— 近实时动态（续）
    //     USGS FDSN 地震目录（最近 500km 内 M2+ 地震，用于地震触发报警）
    //     若无关联设备且无 DB 数据，降级到 USGS 实时地震目录
    if (!factors.containsKey("earthquake_mag") && lat != null && lon != null) {
      var quake = externalDataService.getEarthquake(lat, lon, 500.0, 2.0);
      if (quake != null && quake.hasEvent()) {
        factors.put("earthquake_mag", quake.magnitude());
        log.debug("[AiComprehensive] USGS 地震 lat={} lon={} M{} 距{}km 深度{}km",
            lat, lon, quake.magnitude(), (int)quake.distanceKm(), (int)quake.depthKm());
      }
    }

    // [1] 遥感影像（光学+雷达）—— 空间基础
    //     数据来源：
    //     ① 巡检记录（last_inspection_at）—— 优先，真实现场照片
    //     ② Copernicus Data Space（Sentinel-2 L2A 元数据，免费注册）—— 判断最近卫星影像时间
    //     ③ 卫星影像可用时，image_anomaly_score 基于距上次影像天数计算（越久越高）
    //     包含：image_anomaly_score / image_source（Sentinel-1/Sentinel-2/Landsat/巡检相机）
    double imageAnomaly = 0.0;
    String imageSource = "unknown";
    String imageTimestamp = null;

    if (hazard != null) {
      // ① 优先用本地巡检记录（真实现场照片）
      if (hazard.getLastInspectionAt() != null) {
        imageTimestamp = hazard.getLastInspectionAt().toString();
        long days = Duration.between(hazard.getLastInspectionAt(), OffsetDateTime.now(ZoneOffset.UTC)).toDays();
        imageAnomaly = imageAnomalyByDays(days);
        imageSource = hazard.getLastInspectionDeviceType() != null
            ? hazard.getLastInspectionDeviceType() : "inspection_camera";
        log.debug("[AiComprehensive] 巡检影像 hazard={} days={} source={}", hazardCode, days, imageSource);
      }

      // ② Copernicus Data Space（无本地巡检时）：查 Sentinel-2 最近影像
      if (lat != null && lon != null && imageSource.equals("unknown")) {
        var copInfo = externalDataService.getCopernicusImage(lat, lon);
        if (copInfo != null && copInfo.date() != null) {
          try {
            OffsetDateTime satTime = OffsetDateTime.parse(copInfo.date().replace(" ", "T"));
            long satDays = Duration.between(satTime, OffsetDateTime.now(ZoneOffset.UTC)).toDays();
            // 卫星影像云量高时降低置信度
            double cloudPenalty = copInfo.cloudCoverPct() > 50 ? 0.7 : 1.0;
            imageAnomaly = imageAnomalyByDays(satDays) * cloudPenalty;
            imageSource = "copernicus_sentinel2";
            imageTimestamp = copInfo.date();
            log.debug("[AiComprehensive] Copernicus 卫星影像 hazard={} days={} cloud={}%",
                hazardCode, satDays, (int)copInfo.cloudCoverPct());
          } catch (Exception e) {
            log.debug("[AiComprehensive] Copernicus 日期解析失败 hazard={}", hazardCode);
          }
        }
      }

      // ③ 无任何影像数据时退化为中等风险
      if (imageSource.equals("unknown")) {
        imageAnomaly = 20.0;
        imageSource = "none";
      }
    }

    factors.put("image_anomaly_score", imageAnomaly);
    factors.put("image_anomaly_count", 1.0);  // 有影像 = 1
    factors.put("image_source", imageSource);

    // [5] 历史灾情与承灾体 —— 训练验证
    //     数据来源：
    //     ① DB hazard_point 表历史统计字段（historicalCount / historicalMaxScore）
    //     ② OSM Overpass API（真实承灾体：土地利用/建筑物数量/路网密度）
    //     用于训练 AI 模型、生成灾害风险区划、评估潜在影响范围
    int historicalCount = hazard != null ? hazard.getHistoricalCount() : 0;
    double historicalMaxScore = hazard != null ? hazard.getHistoricalMaxScore() : 0.0;
    long exposurePop = hazard != null ? hazard.getExposurePopulation() : 0L;
    int exposureBuildings = hazard != null ? hazard.getExposureBuildings() : 0;
    factors.put("historical_count", (double) historicalCount);
    factors.put("historical_max_score", historicalMaxScore);

    // OSM 真实承灾体数据（Overpass API）
    String landUseType = "unknown";
    double infrastructureScore = 0.5;  // 路网密度 0-1
    double roadAccessScore = 0.5;
    if (lat != null && lon != null) {
      var osm = externalDataService.getOsmData(lat, lon, 500.0);
      if (osm != null) {
        landUseType = osm.landUseType();
        // 路网密度（km/km²）→ 0-1 归一化（城市 > 5 km/km²，农村 > 2，山区 < 1）
        double roadDensityNorm = clamp(osm.roadDensityKmPerSqKm() / 5.0, 0.0, 1.0);
        infrastructureScore = roadDensityNorm;
        roadAccessScore = roadDensityNorm;
        // 建筑物数量反映暴露度
        if (osm.buildingCount() > 0) {
          exposureBuildings = Math.max(exposureBuildings, osm.buildingCount());
        }
        log.debug("[AiComprehensive] OSM 承灾体 lat={} lon={} landuse={} roads={} buildings={}",
            lat, lon, landUseType, osm.roadCount(), osm.buildingCount());
      }
    }
    factors.put("exposure_population", (double) exposurePop);
    factors.put("exposure_buildings", (double) exposureBuildings);
    factors.put("land_use_type", landUseType);  // 真实 OSM 土地利用类型

    // [6] 舆情与辅助数据 —— 可选增强因子
    //     数据来源：
    //     ① GDACS RSS（真实灾害警报：地震/洪水/台风，alertlevel → social_alert_level）
    //     ② NASA Black Marble VNP46A2（真实夜间灯光，需 EarthData 账号）
    //     ③ OSM（路网/电力/可达性，已在上面接入）
    //     用于补充近实时现场情报，优化影响评估

    // GDACS 真实灾害事件（无 GDACS 账号时用 0）
    double socialAlertLevel = 0.0;
    double socialEmergencyLevel = 0.0;
    if (lat != null && lon != null) {
      var gdacsEvents = externalDataService.getGdacsEvents(lat, lon, 500.0);
      if (gdacsEvents != null && !gdacsEvents.isEmpty()) {
        // 取最近灾害的 alertScore（1-5）
        for (var ev : gdacsEvents) {
          if (ev.alertScore() > socialAlertLevel) {
            socialAlertLevel = ev.alertScore();
          }
          // GDACS alertlevel: 1=Green, 2=Yellow, 3=Orange, 4=Red
          if (ev.alertScore() >= 3) {
            socialEmergencyLevel = 1.0;  // 橙色/红色预警 → 应急级别高
          }
        }
        log.debug("[AiComprehensive] GDACS 灾害事件 lat={} lon={} alert={} 事件数={}",
            lat, lon, (int)socialAlertLevel, gdacsEvents.size());
      }
    }
    factors.put("social_alert_level", socialAlertLevel);
    factors.put("social_post_count", 0.0);   // 社交媒体需认证 API（微博/Twitter），保持 0
    factors.put("social_sentiment_score", 0.0);
    factors.put("social_emergency_level", socialEmergencyLevel);

    // 夜间灯光（NASA Black Marble 无公开 JSON endpoint，统一用 OSM 路网密度推算）
    double ntlValue = infrastructureScore * 63.0;
    factors.put("nighttime_light_index", ntlValue);
    factors.put("infrastructure_score", infrastructureScore);  // OSM 真实路网密度
    factors.put("road_access_score", roadAccessScore);          // OSM 道路可达性

    // 全局兜底（确保 AI 服务在任何因子缺失时都能返回结果）
    if (!factors.containsKey("rainfall_24h")) factors.put("rainfall_24h", 0.0);
    if (!factors.containsKey("rainfall_7d"))  factors.put("rainfall_7d",  0.0);
    if (!factors.containsKey("rainfall_30d")) factors.put("rainfall_30d", 0.0);
    if (!factors.containsKey("displacement_rate"))  factors.put("displacement_rate", 0.0);
    if (!factors.containsKey("displacement_total")) factors.put("displacement_total", 0.0);
    if (!factors.containsKey("crack_width"))       factors.put("crack_width", 0.0);
    if (!factors.containsKey("earthquake_mag"))   factors.put("earthquake_mag", 0.0);
    if (!factors.containsKey("tilt_angle"))       factors.put("tilt_angle", 0.0);
    if (!factors.containsKey("water_level"))      factors.put("water_level", 0.0);
    if (!factors.containsKey("temperature"))       factors.put("temperature", 20.0);
    if (!factors.containsKey("humidity"))         factors.put("humidity", 0.5);
    if (!factors.containsKey("soil_moisture"))     factors.put("soil_moisture", 0.3);
    if (!factors.containsKey("aspect_deg"))        factors.put("aspect_deg", 180.0);
    if (!factors.containsKey("curvature"))         factors.put("curvature", 0.0);
    if (!factors.containsKey("fault_distance_m"))  factors.put("fault_distance_m", 99999.0);

    log.debug("AiComprehensiveAnalysisSupport 因子聚合 hazard={}, 因子数={}", hazardCode, factors.size());
    return factors;
  }

  /**
   * 根据距上次影像天数计算 image_anomaly_score。
   * 阶跃式评分（与原逻辑一致，但封装为独立方法便于复用）：
   *   ≤7天:  0分（影像新鲜）
   *   ≤30天: 15分
   *   ≤90天: 35分
   *   >90天: 60分（影像陈旧）
   */
  private static double imageAnomalyByDays(long days) {
    if (days <= 7) return 0.0;
    if (days <= 30) return 15.0;
    if (days <= 90) return 35.0;
    return 60.0;
  }

  /** 将值限制在 [min, max] 范围内。 */
  private static double clamp(double value, double min, double max) {
    if (value < min) return min;
    if (value > max) return max;
    return value;
  }

  /**
   * 专门为遥感影像维度构造因子集（用于 /api/ai/comprehensive 的独立遥感分析）。
   * 返回 {image_anomaly_score, image_anomaly_count, image_source, image_confidence, image_timestamp}。
   */
  public Map<String, Object> aggregateRemoteSensingFactors(String hazardCode) {
    Map<String, Object> factors = new LinkedHashMap<>();
    HazardPoint hazard = hazardRepository.findByHazardCode(hazardCode);
    Double lat = hazard != null ? hazard.getLatitude() : null;
    Double lon = hazard != null ? hazard.getLongitude() : null;

    double imageAnomaly = 0.0;
    String imageSource = "unknown";
    String imageTimestamp = null;
    double confidence = 0.5;  // 默认低置信度

    if (hazard != null) {
      // ① 优先本地巡检记录
      if (hazard.getLastInspectionAt() != null) {
        long days = Duration.between(
            hazard.getLastInspectionAt(), OffsetDateTime.now(ZoneOffset.UTC)
        ).toDays();
        imageAnomaly = imageAnomalyByDays(days);
        imageSource = hazard.getLastInspectionDeviceType() != null
            ? hazard.getLastInspectionDeviceType() : "inspection_camera";
        imageTimestamp = hazard.getLastInspectionAt().toString();
        confidence = 0.8;  // 现场巡检高置信度
      }
      // ② Copernicus 卫星影像（无本地巡检时）
      else if (lat != null && lon != null) {
        var copInfo = externalDataService.getCopernicusImage(lat, lon);
        if (copInfo != null && copInfo.date() != null) {
          try {
            OffsetDateTime satTime = OffsetDateTime.parse(copInfo.date().replace(" ", "T"));
            long satDays = Duration.between(satTime, OffsetDateTime.now(ZoneOffset.UTC)).toDays();
            imageAnomaly = imageAnomalyByDays(satDays);
            imageSource = "copernicus_sentinel2";
            imageTimestamp = copInfo.date();
            confidence = copInfo.cloudCoverPct() < 30 ? 0.7 : 0.5;  // 云量 < 30% → 较高置信度
          } catch (Exception e) {
            // 日期解析失败，降级
            imageAnomaly = 20.0;
            imageSource = "copernicus_sentinel2";
          }
        }
      }
      // ③ 无任何影像
      if (imageSource.equals("unknown")) {
        imageAnomaly = 20.0;
        imageSource = "none";
        confidence = 0.3;
      }
    }

    factors.put("image_anomaly_score", imageAnomaly);
    factors.put("image_anomaly_count", 1.0);
    factors.put("image_source", imageSource);
    factors.put("image_confidence", confidence);
    factors.put("image_timestamp", imageTimestamp != null ? imageTimestamp : "");

    return factors;
  }

  /**
   * 专门为历史灾情维度构造因子集。
   */
  public Map<String, Object> aggregateHistoricalFactors(String hazardCode) {
    Map<String, Object> factors = new LinkedHashMap<>();
    HazardPoint hazard = hazardRepository.findByHazardCode(hazardCode);

    int historicalCount = hazard != null ? hazard.getHistoricalCount() : 0;
    double historicalMaxScore = hazard != null ? hazard.getHistoricalMaxScore() : 0.0;
    long exposurePop = hazard != null ? hazard.getExposurePopulation() : 0L;
    int exposureBuildings = hazard != null ? hazard.getExposureBuildings() : 0;
    String landUseType = "unknown";
    Double lat = hazard != null ? hazard.getLatitude() : null;
    Double lon = hazard != null ? hazard.getLongitude() : null;

    // OSM 真实土地利用和承灾体数据
    if (lat != null && lon != null) {
      var osm = externalDataService.getOsmData(lat, lon, 500.0);
      if (osm != null) {
        landUseType = osm.landUseType();
        exposureBuildings = Math.max(exposureBuildings, osm.buildingCount());
      }
    }

    factors.put("historical_count", (double) historicalCount);
    factors.put("historical_max_score", historicalMaxScore);
    factors.put("exposure_population", (double) exposurePop);
    factors.put("exposure_buildings", (double) exposureBuildings);
    factors.put("land_use_type", landUseType);

    return factors;
  }

  /**
   * 专门为辅助数据维度构造因子集（GDACS 灾害警报 + NASA 夜间灯光 + OSM 路网）。
   */
  public Map<String, Object> aggregateSupportingFactors(String hazardCode) {
    Map<String, Object> factors = new LinkedHashMap<>();
    HazardPoint hazard = hazardRepository.findByHazardCode(hazardCode);
    Double lat = hazard != null ? hazard.getLatitude() : null;
    Double lon = hazard != null ? hazard.getLongitude() : null;

    // GDACS 灾害事件
    double socialAlertLevel = 0.0;
    if (lat != null && lon != null) {
      var gdacs = externalDataService.getGdacsEvents(lat, lon, 500.0);
      if (gdacs != null) {
        for (var ev : gdacs) {
          socialAlertLevel = Math.max(socialAlertLevel, ev.alertScore());
        }
      }
    }

    // OSM 路网密度 + 夜间灯光推算
    double roadAccess = 0.5;
    if (lat != null && lon != null) {
      var osm = externalDataService.getOsmData(lat, lon, 500.0);
      if (osm != null) {
        roadAccess = clamp(osm.roadDensityKmPerSqKm() / 5.0, 0.0, 1.0);
      }
    }
    // NASA Black Marble 无公开 JSON endpoint，NTL 用路网密度推算（城市夜间灯光与路网高度相关）
    double ntl = roadAccess * 63.0;

    factors.put("social_alert_level", socialAlertLevel);
    factors.put("nighttime_light_index", ntl);
    factors.put("road_access_score", roadAccess);
    factors.put("infrastructure_score", roadAccess);
    factors.put("power_status", 1.0);

    return factors;
  }
}
