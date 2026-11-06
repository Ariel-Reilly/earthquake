package cn.disasterguard.ai.service;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 地理上下文解析 v2.0：根据 hazard_point.region_code 或 hazard_type 推断当地地理情况。
 *
 * <p>输入是 region_code（如 "510322" = 龙川县行政区划代码）、hazardType（滑坡/崩塌/泥石流/地面塌陷）、
 * 经纬度，输出"当地的各种地理情况"九个因子：
 *   elevation_m / slope_deg / aspect_deg / curvature / soil_grade / vegetation_cover /
 *   distance_to_water_m / geology_grade / fault_distance_m
 *
 * <p>当前实现采用静态查表 + 默认值兜底；未来可接入：
 *   - 气象局 DEM（30m/90m 分辨率）→ 替代静态海拔/坡度
 *   - 国土地质调查局 1:50 万地质图 → 替代静态地质等级
 *   - 水文地质图（距水距离）
 *   - MODIS NDVI 植被指数（250m / 16d）
 *   - 活动断层数据集（1:100 万）
 *
 * <p>真实数据接入后，只需替换 {@link #resolve(String, String, Double, Double)} 实现，
 * 上层 AiController 不需要改动。
 */
@Service
public class GeoContextResolver {

  /** 单个灾害点的地理上下文快照。 */
  public record GeoContext(
      double elevationM,
      double slopeDeg,
      double aspectDeg,
      double curvature,
      double soilGrade,
      double vegetationCover,
      double distanceToWaterM,
      double geologyGrade,
      String geologyLabel,
      double faultDistanceM
  ) {
    /** 转成 Map，便于拼装到 AI 因子字典。 */
    public Map<String, Double> toFactorMap() {
      Map<String, Double> m = new HashMap<>();
      m.put("elevation_m", elevationM);
      m.put("slope_deg", slopeDeg);
      m.put("soil_grade", soilGrade);
      m.put("vegetation_cover", vegetationCover);
      m.put("distance_to_water_m", distanceToWaterM);
      m.put("geology_grade", geologyGrade);
      return m;
    }
  }

  /**
   * 默认地理上下文：用于 region_code 不在已知表里的情况。
   * 取值为川东山区典型值：海拔 600m、坡度 25°、坡向 180°、中性曲率、土壤易蚀性中等、
   * 植被 50%、距河 800m、沉积岩、断层 5000m。
   */
  private static final GeoContext DEFAULT_CONTEXT = new GeoContext(
      600.0,   // elevationM
      25.0,    // slopeDeg
      180.0,   // aspectDeg（南坡）
      0.0,     // curvature（平面）
      3.0,     // soilGrade (中等)
      0.50,    // vegetationCover
      800.0,   // distanceToWaterM
      2.0,     // geologyGrade (沉积岩)
      "沉积岩",
      5000.0   // faultDistanceM
  );

  /**
   * region_code → GeoContext 静态查表。
   * 优先以行政区划代码（510322 龙川县、510322106 龙安镇）为 key；
   * 没有精确命中时按 hazardType 退化。
   *
   * <p>数据参考川东山区 + 川南丘陵的典型值；后续接真实 DEM 后替换为数据库查询。
   */
  private static final Map<String, GeoContext> REGION_TABLE = buildRegionTable();

  private static Map<String, GeoContext> buildRegionTable() {
    Map<String, GeoContext> t = new HashMap<>();
    // 龙川县（510322）- 川东平行岭谷，丘陵为主，部分中低山
    t.put("510322", new GeoContext(
        480.0,   // elevationM
        22.0,    // slopeDeg
        165.0,   // aspectDeg（东南坡）
        0.0,     // curvature
        3.0,     // soilGrade
        0.55,    // vegetationCover
        650.0,   // distanceToWaterM
        2.0,     // geologyGrade（沉积岩：侏罗系砂泥岩互层）
        "沉积岩（砂泥岩互层）",
        3500.0   // faultDistanceM
    ));
    // 龙安镇（510322106）- 龙川县下辖，山地为主，地质灾害高发
    t.put("510322106", new GeoContext(
        750.0,   // elevationM
        32.0,    // slopeDeg
        90.0,    // aspectDeg（东坡）
        0.05,    // curvature（微凸）
        3.5,     // soilGrade
        0.45,    // vegetationCover
        320.0,   // distanceToWaterM
        2.5,     // geologyGrade
        "沉积岩（砂泥岩互层）",
        2000.0   // faultDistanceM（更近活动断层）
    ));
    // 通用四川盆地丘陵区
    t.put("510000", new GeoContext(
        500.0, 20.0, 180.0, 0.0, 3.0, 0.50, 800.0, 2.0, "沉积岩（红层）", 6000.0
    ));
    // 川西高原（高海拔 + 陡坡 + 近断层）
    t.put("513300", new GeoContext(
        2400.0, 38.0, 45.0, 0.1, 4.0, 0.40, 500.0, 3.0, "变质岩", 800.0
    ));
    return t;
  }

  /**
   * 按 hazardType 微调默认值（让滑坡/崩塌的 slope 偏高，泥石流的 distance_to_water 偏近）。
   */
  private static GeoContext adjustByHazardType(GeoContext base, String hazardType) {
    if (hazardType == null || hazardType.isBlank()) return base;
    double slope = base.slopeDeg();
    double distance = base.distanceToWaterM();
    double geology = base.geologyGrade();
    String label = base.geologyLabel();
    if (hazardType.contains("滑坡")) {
      slope = Math.max(slope, 25.0);
    } else if (hazardType.contains("崩塌")) {
      slope = Math.max(slope, 35.0);
    } else if (hazardType.contains("泥石流")) {
      distance = Math.min(distance, 400.0);
    } else if (hazardType.contains("塌陷")) {
      geology = Math.max(geology, 3.0);
      label = "可溶性岩（碳酸盐岩）";
    }
    return new GeoContext(
        base.elevationM(), slope, base.aspectDeg(), base.curvature(),
        base.soilGrade(), base.vegetationCover(),
        distance, geology, label, base.faultDistanceM()
    );
  }

  /**
   * 解析地理上下文：先按 region_code 精确查表，没有命中按 hazardType 退化，最后用默认值。
   *
   * @param regionCode 行政区划代码（可空）
   * @param hazardType 灾害类型（滑坡/崩塌/泥石流/塌陷，可空）
   * @param longitude  经度（可空，预留扩展：用于实时查 DEM API）
   * @param latitude   纬度（可空）
   * @return GeoContext 不会为 null，至少返回 DEFAULT_CONTEXT
   */
  public GeoContext resolve(String regionCode, String hazardType, Double longitude, Double latitude) {
    GeoContext base = DEFAULT_CONTEXT;
    if (regionCode != null && !regionCode.isBlank()) {
      GeoContext hit = REGION_TABLE.get(regionCode);
      if (hit != null) base = hit;
    }
    return adjustByHazardType(base, hazardType);
  }
}