package cn.disasterguard.warning.dto;

import java.time.OffsetDateTime;

/**
 * 预警事件 DTO。
 *
 * <p>末尾 4 个 {@code ai*} 字段由 {@link cn.disasterguard.ai.service.AiRiskEnricher} 按
 * hazardCode 注入 —— 表示"该预警所属灾害点当前的 AI 综合风险"。
 * 用途：值班员处置预警时能一眼看到 AI 研判结论，判断这条预警是否仍具备现实风险
 * （AI 分已回落说明风险已缓解，可优先关闭）。
 */
public record WarningDto(
    Long id,
    String warningCode,
    String level,
    String cssClass,
    String title,
    String desc,
    String time,
    String point,
    String status,
    String hazardCode,
    String ruleCode,
    String triggerValue,
    OffsetDateTime triggeredAt,
    String closeReason,
    String closedBy,
    OffsetDateTime confirmedAt,
    OffsetDateTime dispatchedAt,
    OffsetDateTime closedAt,
    // ---- 所属灾害点的 AI 综合分析 ----
    String aiLevel,
    String aiLevelLabel,
    Double aiScore,
    OffsetDateTime aiAnalyzedAt
) {

  public WarningDto(Long id, String warningCode, String level, String cssClass, String title,
      String desc, String time, String point, String status, String hazardCode, String ruleCode,
      String triggerValue, OffsetDateTime triggeredAt, String closeReason, String closedBy,
      OffsetDateTime confirmedAt, OffsetDateTime dispatchedAt, OffsetDateTime closedAt) {
    this(id, warningCode, level, cssClass, title, desc, time, point, status, hazardCode,
        ruleCode, triggerValue, triggeredAt, closeReason, closedBy, confirmedAt,
        dispatchedAt, closedAt, null, null, null, null);
  }
}
