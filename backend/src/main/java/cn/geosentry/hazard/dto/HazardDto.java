package cn.geosentry.hazard.dto;

import java.time.OffsetDateTime;

/**
 * v1.6.0：灾害点对外 DTO。
 *
 * <p>字段顺序遵循"基础信息 → v1.6.0 AI 监测元信息 → AI 综合分"的展示层次，
 * 便于前端按位置读取。注意：构造函数参数顺序即 record 字段顺序，新增字段必须放尾部。
 */
public record HazardDto(
    // ---- 基础信息 ----
    Long id,
    String hazardCode,
    String name,
    String type,
    String level,
    String regionCode,
    String owner,
    String status,
    Double lon,
    Double lat,
    String threatObject,
    OffsetDateTime lastInspectionAt,
    String distance,
    // ---- v1.6.0 数据来源 ----
    String source,
    OffsetDateTime discoveredAt,
    Double detectionConfidence,
    // ---- AI 综合分（v1.3.0+，由 AiRiskEnricher 注入）----
    String aiLevel,
    String aiLevelLabel,
    Double aiScore,
    OffsetDateTime aiAnalyzedAt,
    String aiDisasterType,
    String aiDisasterTypeSource,
    String aiConcernLabel,
    Boolean aiDeviceAvailable
) {
}
