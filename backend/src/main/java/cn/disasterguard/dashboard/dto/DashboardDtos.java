package cn.disasterguard.dashboard.dto;

import java.util.List;
import java.util.Map;

public final class DashboardDtos {
  private DashboardDtos() {
  }

  public record OverviewCard(String label, String value, String suffix, String change, String color) {}

  public record LoginProfile(String name, String role, String username) {}

  public record LoginResult(String token, LoginProfile user) {}

  public record RegionRisk(String region, int intensity, String risk) {}

  public record RiskZone(String name, String level, int count, int x, int y, int width, int height, String color) {}

  public record ChartData(
      List<Integer> riskTrend,
      List<Integer> monthlyInspection,
      List<Map<String, Object>> warningType,
      List<Integer> deviceTrend,
      List<RegionRisk> heatmap,
      List<RiskZone> riskZones
  ) {}
}
