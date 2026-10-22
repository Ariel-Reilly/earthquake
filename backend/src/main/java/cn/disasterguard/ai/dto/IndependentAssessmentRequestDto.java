package cn.disasterguard.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * v1.7.0：全国独立测算请求 DTO。
 *
 * <p>不依赖接入设备/已知灾害点档案，只需传入坐标 + 可选天气/地理因子。
 * 用于运维/值班员在全国任意位置快速评估风险，或对接外部系统（气象预警等）。
 *
 * <p>POST /api/ai/disaster-assessment/independent
 */
public record IndependentAssessmentRequestDto(
    @Valid @NotEmpty(message = "points 不能为空")
    List<AssessmentPoint> points,

    @JsonAlias("min_risk_level")
    String minRiskLevel,

    @JsonAlias("mock_weather")
    Boolean mockWeather
) {

  /**
   * 独立测算的单个监控点（经纬度必填，其余可选）。
   *
   * <p>天气/地理因子全部可选：
   * <ul>
   *   <li>全缺时自动用季节性模拟值（mock_weather=true）填充，保证 AI 始终能输出结论；</li>
   *   <li>传入真实数据时 AI 评分更准确。</li>
   * </ul>
   *
   * @param lon            WGS-84 经度（73-135°）
   * @param lat            WGS-84 纬度（18-54°）
   * @param regionCode     行政区划代码（可选，用于自适应归一化区域标识）
   * @param regionName     行政区划名称（可选，仅用于响应报告展示）
   * @param rainfall24h    过去 24h 累计降雨量（mm）
   * @param rainfall7d     过去 7d 累计降雨量（mm）
   * @param temperature    当前气温（℃）
   * @param humidity       相对湿度（0.0-1.0）
   * @param elevationM     海拔（m）
   * @param slopeDeg       地面坡度（°）
   * @param soilGrade      土壤易蚀性等级（1-5）
   * @param vegetationCover 植被覆盖度（0.0-1.0）
   * @param distanceToWaterM 距最近水体距离（m）
   * @param geologyGrade   地质条件等级（1-5）
   * @param geologyLabel   地质岩性标签（如「碳酸盐岩」）
   */
  public record AssessmentPoint(
      @DecimalMin(value = "73.0", message = "lon 最小 73")
      @DecimalMax(value = "135.0", message = "lon 最大 135")
      double lon,

      @DecimalMin(value = "18.0", message = "lat 最小 18")
      @DecimalMax(value = "54.0", message = "lat 最大 54")
      double lat,

      String regionCode,
      String regionName,

      @JsonAlias("rainfall_24h")
      Double rainfall24h,

      @JsonAlias("rainfall_7d")
      Double rainfall7d,

      Double temperature,
      Double humidity,

      @JsonAlias("elevation_m")
      Double elevationM,

      @JsonAlias("slope_deg")
      Double slopeDeg,

      @JsonAlias("soil_grade")
      Double soilGrade,

      @JsonAlias("vegetation_cover")
      Double vegetationCover,

      @JsonAlias("distance_to_water_m")
      Double distanceToWaterM,

      @JsonAlias("geology_grade")
      Double geologyGrade,

      @JsonAlias("geology_label")
      String geologyLabel
  ) {}
}
