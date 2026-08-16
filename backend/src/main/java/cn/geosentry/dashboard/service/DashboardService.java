package cn.geosentry.dashboard.service;

import cn.geosentry.common.api.PageResponse;
import cn.geosentry.common.exception.AppException;
import cn.geosentry.dashboard.dto.DashboardDtos.ChartData;
import cn.geosentry.dashboard.dto.DashboardDtos.LoginProfile;
import cn.geosentry.dashboard.dto.DashboardDtos.LoginResult;
import cn.geosentry.dashboard.dto.DashboardDtos.OverviewCard;
import cn.geosentry.dashboard.dto.DashboardDtos.RegionRisk;
import cn.geosentry.dashboard.dto.DashboardDtos.RiskZone;
import cn.geosentry.device.repository.DeviceMetricRepository;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import cn.geosentry.hazard.repository.HazardPointRepository;
import cn.geosentry.inspection.repository.InspectionRecordRepository;
import cn.geosentry.task.repository.InspectionTaskRepository;
import cn.geosentry.warning.repository.WarningEventRepository;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
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
  private final InspectionRecordRepository recordRepository;

  public DashboardService(HazardPointRepository hazardRepository,
                          MonitoringDeviceRepository deviceRepository,
                          DeviceMetricRepository metricRepository,
                          WarningEventRepository warningRepository,
                          InspectionTaskRepository taskRepository,
                          InspectionRecordRepository recordRepository) {
    this.hazardRepository = hazardRepository;
    this.deviceRepository = deviceRepository;
    this.metricRepository = metricRepository;
    this.warningRepository = warningRepository;
    this.taskRepository = taskRepository;
    this.recordRepository = recordRepository;
  }

  public LoginResult login(String username, String role) {
    String resolvedRole = (role == null || role.isBlank()) ? "admin" : role;
    return new LoginResult("mock-jwt-token", new LoginProfile("李晨曦", resolvedRole, username));
  }

  public List<OverviewCard> overviewCards() {
    long hazardCount = hazardRepository.count();
    long deviceCount = deviceRepository.count();
    long onlineDevices = deviceRepository.findAll().stream()
        .filter(d -> "在线".equals(d.getOnlineStatus()))
        .count();
    long activeWarnings = warningRepository.findAll().stream()
        .filter(w -> !"已关闭".equals(w.getStatus()))
        .count();
    long taskTotal = taskRepository.count();
    long taskDone = taskRepository.findAll().stream()
        .filter(t -> "已完成".equals(t.getStatus()))
        .count();
    double completion = taskTotal == 0 ? 100.0 : Math.round(taskDone * 1000.0 / taskTotal) / 10.0;

    return List.of(
        new OverviewCard("监测灾害点", String.valueOf(hazardCount), "处", "实时统计", "teal"),
        new OverviewCard("在线监测设备", String.valueOf(onlineDevices) + "/" + deviceCount, "台", "在线/总数", "blue"),
        new OverviewCard("当前预警事件", String.valueOf(activeWarnings), "起", "未关闭", "orange"),
        new OverviewCard("巡检完成率", String.valueOf(completion), "%", "按已完成任务", "purple")
    );
  }

  public ChartData chartData() {
    Map<String, Long> warningByLevel = new LinkedHashMap<>();
    warningRepository.findAll().stream()
        .sorted(Comparator.comparing(w -> w.getStatus() == null ? "" : w.getStatus()))
        .forEach(w -> warningByLevel.merge(
            w.getWarningLevel() == null || w.getWarningLevel().isBlank() ? "未分级" : w.getWarningLevel(),
            1L, Long::sum));

    String[] colors = {"#e27d5b", "#d7a546", "#5db486", "#658fc1"};
    List<Map<String, Object>> warningType = new ArrayList<>();
    int idx = 0;
    for (Map.Entry<String, Long> entry : warningByLevel.entrySet()) {
      warningType.add(Map.of(
          "label", entry.getKey(),
          "value", entry.getValue(),
          "color", colors[idx % colors.length]
      ));
      idx++;
    }
    if (warningType.isEmpty()) {
      warningType.add(Map.of("label", "暂无预警", "value", 0, "color", "#658fc1"));
    }

    // 最近 12 个月巡检完成数（真实：按已完成任务 startTime 归月）
    YearMonth now = YearMonth.now();
    Map<YearMonth, Long> doneByMonth = new HashMap<>();
    for (var t : taskRepository.findAll()) {
      if ("已完成".equals(t.getStatus()) && t.getStartTime() != null) {
        doneByMonth.merge(YearMonth.from(t.getStartTime()), 1L, Long::sum);
      }
    }
    List<Integer> monthlyInspection = series(doneByMonth, now, 12);

    // 最近 7 个月预警触发数（真实）
    Map<YearMonth, Long> warnByMonth = new HashMap<>();
    for (var w : warningRepository.findAll()) {
      if (w.getTriggeredAt() != null) {
        warnByMonth.merge(YearMonth.from(w.getTriggeredAt()), 1L, Long::sum);
      }
    }
    List<Integer> riskTrend = series(warnByMonth, now, 7);

    // 最近 12 个月设备上报量（真实：来自 device_metric）
    Map<YearMonth, Long> reportByMonth = new HashMap<>();
    for (var m : metricRepository.findAll()) {
      if (m.getRecordedAt() != null) {
        reportByMonth.merge(YearMonth.from(m.getRecordedAt()), 1L, Long::sum);
      }
    }
    List<Integer> deviceTrend = series(reportByMonth, now, 12);

    List<RegionRisk> heatmap = buildHeatmap(hazardRepository.findAll());
    List<RiskZone> riskZones = buildRiskZones(hazardRepository.findAll());

    return new ChartData(
        riskTrend,
        monthlyInspection,
        warningType,
        deviceTrend,
        heatmap,
        riskZones
    );
  }

  /** 区域风险热力：按 region_code 聚合 hazard_point 数量与最高风险等级，生成真实热力。 */
  private List<RegionRisk> buildHeatmap(List<cn.geosentry.hazard.entity.HazardPoint> hazards) {
    if (hazards == null || hazards.isEmpty()) {
      return List.of(new RegionRisk("暂无数据", 0, "低"));
    }
    Map<String, List<cn.geosentry.hazard.entity.HazardPoint>> byRegion = new HashMap<>();
    for (var h : hazards) {
      String key = h.getRegionCode() != null ? h.getRegionCode() : "未知";
      byRegion.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(h);
    }
    List<RegionRisk> result = new java.util.ArrayList<>();
    for (var entry : byRegion.entrySet()) {
      List<cn.geosentry.hazard.entity.HazardPoint> list = entry.getValue();
      String regionLabel = inferRegionName(entry.getKey(), list);
      int highCount = (int) list.stream().filter(h -> "高风险".equals(h.getRiskLevel())).count();
      int watchCount = (int) list.stream().filter(h -> "关注".equals(h.getRiskLevel())).count();
      int total = list.size();
      // intensity: 高风险*30 + 关注*15 + 正常*5，上限 100
      int intensity = Math.min(100, highCount * 30 + watchCount * 15 + Math.max(1, total));
      String risk = highCount > 0 ? "高" : watchCount > 0 ? "中" : "低";
      result.add(new RegionRisk(regionLabel, intensity, risk));
    }
    result.sort((a, b) -> Integer.compare(b.intensity(), a.intensity()));
    return result;
  }

  /** 风险分区：按区域聚合，生成前端可视化布局坐标。 */
  private List<RiskZone> buildRiskZones(List<cn.geosentry.hazard.entity.HazardPoint> hazards) {
    if (hazards == null || hazards.isEmpty()) {
      return List.of(new RiskZone("默认区域", "低", 0, 10, 10, 30, 20, "#658fc1"));
    }
    Map<String, List<cn.geosentry.hazard.entity.HazardPoint>> byRegion = new HashMap<>();
    for (var h : hazards) {
      String key = h.getRegionCode() != null ? h.getRegionCode() : "未知";
      byRegion.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(h);
    }
    List<RiskZone> result = new java.util.ArrayList<>();
    String[] palette = {"#e27d5b", "#d7a546", "#5db486", "#658fc1", "#9b6bb5", "#c77a5a"};
    int idx = 0;
    // 简单布局：2 列从上到下
    int x = 10, y = 10;
    int colWidth = 40;
    int rowHeight = 28;
    for (var entry : byRegion.entrySet()) {
      List<cn.geosentry.hazard.entity.HazardPoint> list = entry.getValue();
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

  /** 通过 regionCode 字典或 hazard.owner 推断区域名。 */
  private String inferRegionName(String regionCode, List<cn.geosentry.hazard.entity.HazardPoint> sample) {
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

  /** 预留骨架方法，暂未被 Controller 调用，保留供后续扩展。 */
  public PageResponse<Map<String, Object>> pageOfSamples(String type) {
    return new PageResponse<>(List.of(Map.of("type", type == null ? "all" : type)), 1, 1, 10, 1);
  }

  /** 生成「最近 n 个月」由旧到新对齐的整型序列（缺失月份补 0）。 */
  private List<Integer> series(Map<YearMonth, Long> byMonth, YearMonth end, int months) {
    List<Integer> result = new ArrayList<>();
    for (int i = months - 1; i >= 0; i--) {
      YearMonth ym = end.minusMonths(i);
      result.add(Math.toIntExact(byMonth.getOrDefault(ym, 0L)));
    }
    return result;
  }
}
