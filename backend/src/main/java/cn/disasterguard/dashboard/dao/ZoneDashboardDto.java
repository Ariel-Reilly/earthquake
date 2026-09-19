package cn.disasterguard.dashboard.dao;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * P0-5：区域专项大屏聚合 DTO。
 */
public record ZoneDashboardDto(
    int hazardCount,
    int onlineDeviceCount,
    int warningCount,
    double aiAccuracy,
    List<TopHazard> topHazards,
    List<RelatedWarning> relatedWarnings,
    List<RelatedEmergency> relatedEmergencies,
    List<TrendPoint> trendPoints,
    OffsetDateTime lastUpdated
) {

  public record TopHazard(
      int rank,
      String hazardCode,
      String name,
      String type,
      String level,
      double riskScore,
      String displacement,
      String rainfall,
      String region,
      Double lon,
      Double lat
  ) {}

  public record RelatedWarning(
      String code,
      String title,
      String level,
      String status,
      String region,
      OffsetDateTime triggeredAt
  ) {}

  public record RelatedEmergency(
      String code,
      String title,
      String level,
      String status,
      String region,
      String commander,
      OffsetDateTime startedAt
  ) {}

  public record TrendPoint(String date, int value) {}
}
