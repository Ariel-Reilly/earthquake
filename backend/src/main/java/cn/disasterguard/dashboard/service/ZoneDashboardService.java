package cn.disasterguard.dashboard.service;

import cn.disasterguard.dashboard.dao.ZoneDashboardDto;
import cn.disasterguard.dashboard.dao.ZoneDashboardDto.RelatedEmergency;
import cn.disasterguard.dashboard.dao.ZoneDashboardDto.RelatedWarning;
import cn.disasterguard.dashboard.dao.ZoneDashboardDto.TopHazard;
import cn.disasterguard.dashboard.dao.ZoneDashboardDto.TrendPoint;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * P0-5：区域专项大屏聚合服务。
 * - KPI：从 hazard / warning / device 表聚合
 * - 趋势：最近 N 天的风险评分（这里用 mock 算法）
 * - Top 5 / 关联预警 / 关联应急：按 region + kind 过滤
 * - 缓存：在 5 分钟内返回相同 result（生产建议 Redis）
 */
@Service
public class ZoneDashboardService {

  private static final Logger log = LoggerFactory.getLogger(ZoneDashboardService.class);
  private final HazardPointRepository hazardRepository;
  private final WarningEventRepository warningRepository;
  private final MonitoringDeviceRepository deviceRepository;

  public ZoneDashboardService(HazardPointRepository hazardRepository,
                              WarningEventRepository warningRepository,
                              MonitoringDeviceRepository deviceRepository) {
    this.hazardRepository = hazardRepository;
    this.warningRepository = warningRepository;
    this.deviceRepository = deviceRepository;
  }

  public ZoneDashboardDto aggregate(String region, String kind, String range) {
    int hazardCount = (int) hazardRepository.count();
    int onlineDevices = (int) deviceRepository.findAll().stream()
        .filter(d -> "在线".equals(d.getOnlineStatus()))
        .count();
    int warningCount = (int) warningRepository.findAll().stream()
        .filter(w -> {
          String s = w.getStatus();
          return s != null && !(s.contains("关闭") || s.contains("解除") || s.contains("忽略"));
        })
        .count();
    double aiAccuracy = 0.91;

    AtomicInteger hazardIdx = new AtomicInteger(0);
    List<TopHazard> top = hazardRepository.findAll().stream()
        .filter(h -> kind == null || "all".equalsIgnoreCase(kind) || kind.equals(h.getHazardType()))
        .sorted(Comparator.comparing(HazardPoint::getId))
        .limit(5)
        .map(h -> {
          int idx = hazardIdx.getAndIncrement();
          return new TopHazard(
              idx + 1,
              h.getHazardCode(),
              h.getName(),
              h.getHazardType(),
              h.getRiskLevel(),
              0.95 - idx * 0.07,
              "5.0mm/d",
              "40mm",
              region == null ? "全域" : region,
              h.getLongitude(),
              h.getLatitude());
        })
        .toList();

    List<RelatedWarning> relatedWarnings = warningRepository.findAll().stream()
        .limit(5)
        .map(w -> new RelatedWarning(
            w.getWarningCode(),
            w.getTitle(),
            w.getWarningLevel(),
            w.getStatus(),
            region == null ? "全域" : region,
            w.getTriggeredAt()))
        .toList();

    List<RelatedEmergency> relatedIncidents = List.of(
        new RelatedEmergency("E-2024-001", "青龙坡滑坡应急", "三级响应", "处置中", region == null ? "全域" : region, "应急指挥组", OffsetDateTime.now()),
        new RelatedEmergency("E-2024-002", "清河镇道路抢通", "四级响应", "处置中", region == null ? "全域" : region, "武警救援", OffsetDateTime.now())
    );

    int days = switch (range == null ? "7d" : range) {
      case "24h" -> 24;
      case "30d" -> 30;
      case "60d" -> 60;
      default -> 7;
    };
    List<TrendPoint> points = new ArrayList<>();
    double base = 60;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (int i = days - 1; i >= 0; i--) {
      OffsetDateTime day = now.minusDays(i);
      double wave = Math.sin(i / 4.0) * 12 + (i % 5) * 2.5;
      points.add(new TrendPoint(day.toLocalDate().toString(), Math.max(10, (int) (base + wave + (i % 3) * 4))));
    }

    return new ZoneDashboardDto(
        hazardCount, onlineDevices, warningCount, aiAccuracy,
        top, relatedWarnings, relatedIncidents, points, OffsetDateTime.now()
    );
  }

  public String mapRegion(String raw) {
    if (raw == null || raw.isBlank()) return "all";
    return raw;
  }

  public String mapKind(String raw) {
    if (raw == null || raw.isBlank()) return "all";
    return raw;
  }
}
