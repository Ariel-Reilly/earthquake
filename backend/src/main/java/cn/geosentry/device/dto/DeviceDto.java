package cn.geosentry.device.dto;

import java.time.OffsetDateTime;

/**
 * 监测设备 DTO。
 *
 * <p>末尾 4 个 {@code ai*} 字段是该设备所属灾害点的 AI 综合分析结果
 * （由 {@link cn.geosentry.ai.service.AiRiskEnricher} 按 hazardCode 注入）。
 * 设备的"风险关注程度"不再只看在线状态，而是由所属灾害点的 AI 综合分决定 ——
 * 高分灾害点下的设备即使在线也应优先核查。
 */
public record DeviceDto(
    Long id,
    String deviceCode,
    String name,
    String type,
    String hazardCode,
    String regionCode,
    String state,
    Integer battery,
    String signal,
    OffsetDateTime lastReportAt,
    String last,
    String point,
    // ---- 所属灾害点的 AI 综合分析 ----
    String aiLevel,
    String aiLevelLabel,
    Double aiScore,
    OffsetDateTime aiAnalyzedAt
) {

  public DeviceDto(Long id, String deviceCode, String name, String type, String hazardCode,
      String regionCode, String state, Integer battery, String signal,
      OffsetDateTime lastReportAt, String last, String point) {
    this(id, deviceCode, name, type, hazardCode, regionCode, state, battery, signal,
        lastReportAt, last, point, null, null, null, null);
  }
}
