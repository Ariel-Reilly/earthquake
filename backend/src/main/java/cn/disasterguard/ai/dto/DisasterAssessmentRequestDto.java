package cn.disasterguard.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
 *
 * <p>序列化方向：Java → Python（FastAPI Pydantic）。Python 端字段名固定 snake_case，
 * 通过 {@code @JsonProperty} 锁定 Java 出站 JSON 的字段名与 Python 一致，
 * 避免历史版本中 {@code @JsonAlias} 仅影响反序列化、导致出站仍输出 camelCase 的 bug。
 *
 * <p>本 DTO 不作为 {@code @RequestBody} 接收前端请求（前端走 GET），
 * 故无需保留 camelCase 入站别名。
 */
public record DisasterAssessmentRequestDto(
    @JsonProperty("hazard") HazardMeta hazard,
    @JsonProperty("factors") Map<String, Object> factors
) {

  /**
   * 灾害点基础元数据。
   *
   * @param hazardCode  灾害点编码（必填）
   * @param hazardType  hazard_point.hazard_type（可选）：滑坡 / 崩塌 / 泥石流 / 地裂缝 / 地面沉降 / 塌陷
   */
  public record HazardMeta(
      @JsonProperty("hazard_code") String hazardCode,
      @JsonProperty("hazard_type") String hazardType
  ) {}
}
