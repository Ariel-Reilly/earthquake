package cn.disasterguard.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * AI /comprehensive 综合风险分析响应数据（与 ai-service Pydantic ComprehensiveResponse 对齐）。
 *
 * <p>输出形态是"多维度明细 + 推理 + 建议"的报告，比 fusion-risk 更适合给值班员看。
 *
 * @param hazardCode        灾害点编码
 * @param hazardName        灾害点名称（Java 端从 DB 补充，便于前端直接展示）
 * @param analyzedAt        分析时间（ISO 8601，Java 端填充）
 * @param overall           总体 riskScore / riskLevel / model / mock
 * @param dimensions        四维度明细：image / precipitation / geographic / device
 * @param reasoning         可解释的风险归因列表
 * @param suggestedActions  可执行建议列表
 * @param model             使用的模型名称（与 overall.model 一致）
 * @param degraded          true 表示降级结果（heuristic-dimension-fusion）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComprehensiveAnalysisResult(
    @JsonAlias("hazard_code") String hazardCode,
    String hazardName,
    String analyzedAt,
    Overall overall,
    Map<String, Object> dimensions,
    List<String> reasoning,
    @JsonAlias("suggested_actions") List<String> suggestedActions,
    String model,
    @JsonAlias("mock") boolean degraded
) {
  /**
   * 总体风险摘要。
   *
   * @param riskLevel   LOW / MEDIUM / HIGH / CRITICAL
   * @param riskScore   0-100
   * @param confidence  0.0-1.0
   * @param model       模型名
   * @param mock        是否 mock / 降级
   */
  public record Overall(
      @JsonAlias("risk_level") String riskLevel,
      @JsonAlias("risk_score") double riskScore,
      double confidence,
      String model,
      @JsonAlias("mock") boolean mock
  ) {}
}