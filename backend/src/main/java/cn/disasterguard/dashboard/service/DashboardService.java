package cn.disasterguard.dashboard.service;

import cn.disasterguard.dashboard.dto.DashboardDtos.ChartData;
import cn.disasterguard.dashboard.dto.DashboardDtos.OverviewCard;
import cn.disasterguard.dashboard.dto.DashboardDtos.RegionRisk;
import cn.disasterguard.dashboard.dto.DashboardDtos.RiskZone;
import cn.disasterguard.device.repository.DeviceMetricRepository;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import cn.disasterguard.task.repository.InspectionTaskRepository;
import cn.disasterguard.warning.repository.WarningEventRepository;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {

  private final HazardPointRepository hazardRepository;
  private final MonitoringDeviceRepository deviceRepository;
  private final DeviceMetricRepository metricRepository;
  private final WarningEventRepository warningRepository;
  private final InspectionTaskRepository taskRepository;

  public DashboardService(HazardPointRepository hazardRepository,
                          MonitoringDeviceRepository deviceRepository,
                          DeviceMetricRepository metricRepository,
                          WarningEventRepository warningRepository,
                          InspectionTaskRepository taskRepository) {
    this.hazardRepository = hazardRepository;
    this.deviceRepository = deviceRepository;
    this.metricRepository = metricRepository;
    this.warningRepository = warningRepository;
    this.taskRepository = taskRepository;
  }

  // 概览卡片：全部用 DB 侧 COUNT，避免 findAll + stream 加载实体

  public List<OverviewCard> overviewCards() {
    long hazardCount = hazardRepository.count();
    long deviceCount = deviceRepository.count();
    long onlineDevices = deviceRepository.countByOnlineStatus("在线");  // ✅ 替代 findAll().stream().filter().count()
    long activeWarnings = warningRepository.countActiveWarnings(List.of("已关闭", "已忽略", "已解除"));
    long taskTotal = taskRepository.count();
    long taskDone = taskRepository.countByStatus("已完成");  // ✅ 替代 findAll().stream().filter().count()
    double completion = taskTotal == 0 ? 100.0 : Math.round(taskDone * 1000.0 / taskTotal) / 10.0;

    return List.of(
        new OverviewCard("监测灾害点", String.valueOf(hazardCount), "处", "实时统计", "teal"),
        new OverviewCard("在线监测设备", String.valueOf(onlineDevices) + "/" + deviceCount, "台", "在线/总数", "blue"),
        new OverviewCard("当前预警事件", String.valueOf(activeWarnings), "起", "未关闭", "orange"),
        new OverviewCard("巡检完成率", String.valueOf(completion), "%", "按已完成任务", "purple")
    );
  }

  // 图表数据：全部用 DB 侧 GROUP BY 聚合，避免 findAll

  public ChartData chartData() {
    // 预警按等级聚合（DB 侧 GROUP BY ✅）
    List<Object[]> levelRows = warningRepository.countByLevel();
    String[] colors = {"#e27d5b", "#d7a546", "#5db486", "#658fc1"};
    List<Map<String, Object>> warningType = new ArrayList<>();
    int idx = 0;
    for (Object[] row : levelRows) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("label", (String) row[0]);
      item.put("value", ((Number) row[1]).longValue());
      item.put("color", colors[idx % colors.length]);
      warningType.add(item);
      idx++;
    }
    if (warningType.isEmpty()) {
      Map<String, Object> empty = new LinkedHashMap<>();
      empty.put("label", "暂无预警");
      empty.put("value", 0);
      empty.put("color", "#658fc1");
      warningType.add(empty);
    }

    // 最近 12 个月巡检完成数（DB 侧按月聚合 ✅）
    Map<String, Long> doneByMonth = toMonthMap(taskRepository.countDoneByMonth());
    List<Integer> monthlyInspection = series(doneByMonth, YearMonth.now(), 12);

    // 最近 7 个月预警触发数（DB 侧按月聚合 ✅）
    Map<String, Long> warnByMonth = toMonthMap(warningRepository.countByMonth());
    List<Integer> riskTrend = series(warnByMonth, YearMonth.now(), 7);

    // 最近 12 个月设备上报量（DB 侧按月聚合 ✅ — 最高危，metric 表数据量最大）
    Map<String, Long> reportByMonth = toMonthMap(metricRepository.countByMonth());
    List<Integer> deviceTrend = series(reportByMonth, YearMonth.now(), 12);

    // 热力图 / 风险分区：hazard_point 表数据量小（~5 条种子数据），findAll 可接受
    List<HazardPoint> allHazards = hazardRepository.findAll();
    List<RegionRisk> heatmap = buildHeatmap(allHazards);
    List<RiskZone> riskZones = buildRiskZones(allHazards);

    return new ChartData(
        riskTrend,
        monthlyInspection,
        warningType,
        deviceTrend,
        heatmap,
        riskZones
    );
  }

  // 辅助：将 Object[] 聚合行转为 { "yyyy-mm": count } Map

  private Map<String, Long> toMonthMap(List<Object[]> rows) {
    Map<String, Long> result = new HashMap<>();
    if (rows == null) return result;
    for (Object[] row : rows) {
      String ym = (String) row[0];
      long cnt = ((Number) row[1]).longValue();
      result.put(ym, cnt);
    }
    return result;
  }

  // 区域风险热力 / 风险分区：纯内存计算（hazard_point 表数据量小）

  private List<RegionRisk> buildHeatmap(List<HazardPoint> hazards) {
    if (hazards == null || hazards.isEmpty()) {
      return List.of(new RegionRisk("暂无数据", 0, "低"));
    }
    Map<String, List<HazardPoint>> byRegion = new HashMap<>();
    for (var h : hazards) {
      String key = h.getRegionCode() != null ? h.getRegionCode() : "未知";
      byRegion.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(h);
    }
    List<RegionRisk> result = new java.util.ArrayList<>();
    for (var entry : byRegion.entrySet()) {
      List<HazardPoint> list = entry.getValue();
      String regionLabel = inferRegionName(entry.getKey(), list);
      int highCount = (int) list.stream().filter(h -> "高风险".equals(h.getRiskLevel())).count();
      int watchCount = (int) list.stream().filter(h -> "关注".equals(h.getRiskLevel())).count();
      int total = list.size();
      int intensity = Math.min(100, highCount * 30 + watchCount * 15 + Math.max(1, total));
      String risk = highCount > 0 ? "高" : watchCount > 0 ? "中" : "低";
      result.add(new RegionRisk(regionLabel, intensity, risk));
    }
    result.sort((a, b) -> Integer.compare(b.intensity(), a.intensity()));
    return result;
  }

  private List<RiskZone> buildRiskZones(List<HazardPoint> hazards) {
    if (hazards == null || hazards.isEmpty()) {
      return List.of(new RiskZone("默认区域", "低", 0, 10, 10, 30, 20, "#658fc1"));
    }
    Map<String, List<HazardPoint>> byRegion = new HashMap<>();
    for (var h : hazards) {
      String key = h.getRegionCode() != null ? h.getRegionCode() : "未知";
      byRegion.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(h);
    }
    List<RiskZone> result = new java.util.ArrayList<>();
    String[] palette = {"#e27d5b", "#d7a546", "#5db486", "#658fc1", "#9b6bb5", "#c77a5a"};
    int idx = 0;
    int x = 10, y = 10;
    int colWidth = 40;
    int rowHeight = 28;
    for (var entry : byRegion.entrySet()) {
      List<HazardPoint> list = entry.getValue();
      String regionLabel = inferRegionName(entry.getKey(), list);
      int highCount = (int) list.stream().filter(h -> "高风险".equals(h.getRiskLevel())).count();
      int watchCount = (int) list.stream().filter(h -> "关注".equals(h.getRiskLevel())).count();
      String level = highCount > 0 ? "高" : watchCount > 0 ? "中" : "低";
      String color = palette[idx % palette.length];
      result.add(new RiskZone(regionLabel, level, list.size(), x, y, colWidth, rowHeight - 6, color));
      idx++;
      if (idx % 2 == 0) {
        x = 10;
        y += rowHeight + 4;
      } else {
        x = 50;
      }
    }
    if (result.isEmpty()) {
      result.add(new RiskZone("默认区域", "低", 0, 10, 10, 30, 20, "#658fc1"));
    }
    return result;
  }

  private String inferRegionName(String regionCode, List<HazardPoint> sample) {
    Map<String, String> codeToName = Map.of(
        "100000", "龙川县",
        "100100", "龙安镇",
        "100200", "石桥镇",
        "100300", "清河镇"
    );
    if (codeToName.containsKey(regionCode)) return codeToName.get(regionCode);
    if (sample != null && !sample.isEmpty() && sample.get(0).getOwner() != null) {
      return sample.get(0).getOwner();
    }
    return regionCode;
  }

  /** 生成「最近 n 个月」由旧到新对齐的整型序列（缺失月份补 0）。 */
  private List<Integer> series(Map<String, Long> byMonth, YearMonth end, int months) {
    List<Integer> result = new ArrayList<>();
    for (int i = months - 1; i >= 0; i--) {
      YearMonth ym = end.minusMonths(i);
      String key = ym.toString();  // "2026-08"
      result.add(Math.toIntExact(byMonth.getOrDefault(key, 0L)));
    }
    return result;
  }
}
