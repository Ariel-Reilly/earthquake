package cn.disasterguard.device.dto;

import java.time.OffsetDateTime;

/**
 * 监测设备 DTO。
 *
 * <p>末尾 4 个 {@code ai*} 字段是该设备所属灾害点的 AI 综合分析结果
 * （由 {@link cn.disasterguard.ai.service.AiRiskEnricher} 按 hazardCode 注入）。
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
    // WGS-84 经度：优先设备自身坐标，缺失时回落到所属灾害点；都没有时为 null（前端地图跳过）
    Double lon,
    // WGS-84 纬度：优先设备自身坐标，缺失时回落到所属灾害点；都没有时为 null
    Double lat,
    String aiLevel,
    String aiLevelLabel,
    Double aiScore,
    OffsetDateTime aiAnalyzedAt
) {

  public DeviceDto(Long id, String deviceCode, String name, String type, String hazardCode,
      String regionCode, String state, Integer battery, String signal,
      OffsetDateTime lastReportAt, String last, String point) {
    this(id, deviceCode, name, type, hazardCode, regionCode, state, battery, signal,
        lastReportAt, last, point, null, null, null, null, null, null);
  }
}
