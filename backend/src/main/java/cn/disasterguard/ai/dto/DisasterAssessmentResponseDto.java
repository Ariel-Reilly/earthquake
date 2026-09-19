package cn.disasterguard.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Python FastAPI 端 /api/ai/disaster-assessment/score 的响应 DTO（与灾害评估模型对齐）。
 *
 * <p>响应三件套：
 * <ul>
 *   <li>{@code overall.riskLevel} —— LOW / MEDIUM / HIGH / CRITICAL 数值分；</li>
 *   <li>{@code overall.concern_label} —— 中文「稳定 / 关注 / 预警 / 异常严重」；</li>
 *   <li>{@code disasterType.disasterType} —— 六大灾害类型分类（滑坡 / 崩塌 / 泥石流 / 地裂缝 / 地面沉降 / 塌陷）。</li>
 * </ul>
 *
 * <p>关键设计：设备维度可选 —— {@code device_available=false} 表示本次评估仅基于地理 + 天气，
 * reasoning 中已显式说明，可前端 UI 提示"未接入监测设备"。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DisasterAssessmentResponseDto(
    @JsonAlias("hazard_code") String hazardCode,
    Overall overall,
    @JsonAlias("disaster_type") DisasterType disasterType,
    Map<String, Object> dimensions,
    List<String> reasoning,
    @JsonAlias("suggested_actions") List<String> suggestedActions,
    String model,
    Boolean mock,
    @JsonAlias("device_available") Boolean deviceAvailable
) {

  /**
   * 总体评估摘要。
   *
   * @param riskLevel      LOW / MEDIUM / HIGH / CRITICAL
   * @param riskScore      0-100
   * @param confidence     0.0-1.0
   * @param concernLabel   中文「稳定 / 关注 / 预警 / 异常严重」
   * @param model          模型名（如 heuristic-geo-weather）
   * @param mock           是否降级（heuristic 兜底或设备缺失）
   * @param deviceAvailable 是否包含设备维度数据
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Overall(
      @JsonAlias("risk_level") String riskLevel,
      @JsonAlias("risk_score") Double riskScore,
      Double confidence,
      @JsonAlias("concern_label") String concernLabel,
      String model,
      Boolean mock,
      @JsonAlias("device_available") Boolean deviceAvailable
  ) {}

  /**
   * 灾害类型分类结果。
   *
   * @param disasterType 灾害类型（中文：滑坡 / 崩塌 / 泥石流 / 地裂缝 / 地面沉降 / 塌陷 / 其他）
   * @param source        declared（档案已标）/ inferred（AI 推断）/ fallback（兜底）
   * @param confidence    分类置信度 0-1
   * @param reason        分类依据 / 解释
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DisasterType(
      @JsonAlias("disaster_type") String disasterType,
      String source,
      Double confidence,
      String reason
  ) {}
}
