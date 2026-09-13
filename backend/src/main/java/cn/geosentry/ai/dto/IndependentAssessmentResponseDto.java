package cn.geosentry.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * v1.7.0：全国独立测算响应 DTO。
 *
 * <p>来自 Python /api/ai/disaster-assessment/independent。
 * 每个点返回完整的七维度评估结论（riskLevel / concernLabel / disasterType / reasoning / suggestedActions）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IndependentAssessmentResponseDto(
    int totalPoints,
    int detectedCount,
    List<IndependentResultDto> results,
    @JsonAlias("execution_ms") int executionMs
) {

  /**
   * 单个监控点的独立测算结果。
   *
   * <p>七维度核心输出：
   * <ul>
   *   <li>riskLevel —— LOW / MEDIUM / HIGH / CRITICAL</li>
   *   <li>concernLabel —— 稳定 / 关注 / 预警 / 异常严重</li>
   *   <li>disasterType —— 滑坡 / 崩塌 / 泥石流 / 地裂缝 / 地面沉降 / 塌陷 / 待定</li>
   *   <li>reasoning —— AI 推理链（为什么是这个分）</li>
   *   <li>suggestedActions —— 建议动作</li>
   * </ul>
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record IndependentResultDto(
      double lon,
      double lat,
      String regionCode,
      String regionName,
      @JsonAlias("risk_level") String riskLevel,
      @JsonAlias("risk_score") double riskScore,
      @JsonAlias("concern_label") String concernLabel,
      @JsonAlias("disaster_type") String disasterType,
      double confidence,
      @JsonAlias("geographic_score") double geographicScore,
      @JsonAlias("precipitation_score") double precipitationScore,
      @JsonAlias("device_available") boolean deviceAvailable,
      List<String> reasoning,
      @JsonAlias("suggested_actions") List<String> suggestedActions,
      String model,
      @JsonAlias("mock_weather_used") boolean mockWeatherUsed
  ) {}
}
