package cn.geosentry.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;
import java.util.Map;

/**
 * Python FastAPI 端 /api/ai/disaster-assessment/score 的请求 DTO。
 *
 * <p>两个块：
 * <ol>
 *   <li>{@link HazardMeta} —— 灾害点基础元数据（hazardCode + 已标注的 hazardType）；</li>
 *   <li>{@code factors} —— 评估因子（地理 + 天气 + 可选设备）。
 *       设备因子（displacement_* / crack_width / earthquake_mag / site_grade）允许缺省，
 *       缺省时 Python 端会自动把权重分给 geographic + precipitation 并标 device_available=false。</li>
 * </ol>
 */
public record DisasterAssessmentRequestDto(
    HazardMeta hazard,
    @JsonAlias("factors") Map<String, Object> factors
) {

  /**
   * 灾害点基础元数据。
   *
   * @param hazardCode  灾害点编码（必填）
   * @param hazardType  hazard_point.hazard_type（可选）：滑坡 / 崩塌 / 泥石流 / 地裂缝 / 地面沉降 / 塌陷
   */
  public record HazardMeta(
      @JsonAlias("hazard_code") String hazardCode,
      @JsonAlias("hazard_type") String hazardType
  ) {}
}
