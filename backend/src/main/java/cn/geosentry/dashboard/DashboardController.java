package cn.geosentry.dashboard;

import cn.geosentry.common.api.ApiResponse;
import cn.geosentry.common.export.DashboardExportRow;
import cn.geosentry.common.export.ExcelExportService;
import cn.geosentry.dashboard.dto.DashboardDtos.ChartData;
import cn.geosentry.dashboard.dto.DashboardDtos.OverviewCard;
import cn.geosentry.dashboard.service.DashboardService;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import cn.geosentry.emergency.repository.EmergencyEventRepository;
import cn.geosentry.hazard.entity.HazardPoint;
import cn.geosentry.hazard.repository.HazardPointRepository;
import cn.geosentry.task.repository.InspectionTaskRepository;
import cn.geosentry.warning.repository.WarningEventRepository;
import cn.geosentry.warning.repository.WarningRuleRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

  private final DashboardService dashboardService;
  private final HazardPointRepository hazardRepository;
  private final MonitoringDeviceRepository deviceRepository;
  private final WarningEventRepository warningRepository;
  private final WarningRuleRepository warningRuleRepository;
  private final InspectionTaskRepository taskRepository;
  private final EmergencyEventRepository emergencyRepository;

  private final boolean ruleEngineEnabled;
  private final boolean aiEnabled;
  private final ExcelExportService excelExportService;

  public DashboardController(DashboardService dashboardService,
                             HazardPointRepository hazardRepository,
                             MonitoringDeviceRepository deviceRepository,
                             WarningEventRepository warningRepository,
                             WarningRuleRepository warningRuleRepository,
                             InspectionTaskRepository taskRepository,
                             EmergencyEventRepository emergencyRepository,
                             ExcelExportService excelExportService,
                             @Value("${geosentry.warning.rule-engine-enabled:true}") boolean ruleEngineEnabled,
                             @Value("${ai.enabled:false}") boolean aiEnabled) {
    this.dashboardService = dashboardService;
    this.hazardRepository = hazardRepository;
    this.deviceRepository = deviceRepository;
    this.warningRepository = warningRepository;
    this.warningRuleRepository = warningRuleRepository;
    this.taskRepository = taskRepository;
    this.emergencyRepository = emergencyRepository;
    this.excelExportService = excelExportService;
    this.ruleEngineEnabled = ruleEngineEnabled;
    this.aiEnabled = aiEnabled;
  }

  @GetMapping("/overview")
  public ApiResponse<List<OverviewCard>> overview() {
    return ApiResponse.success(dashboardService.overviewCards());
  }

  @GetMapping("/charts")
  public ApiResponse<ChartData> charts() {
    return ApiResponse.success(dashboardService.chartData());
  }

  @GetMapping("/analytics")
  public ApiResponse<Map<String, Object>> analytics() {
    ChartData chartData = dashboardService.chartData();
    List<HazardPoint> hazards = hazardRepository.findAll();
    List<Map<String, Object>> hazardList = hazards.stream().map(h -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", h.getId());
      m.put("code", h.getHazardCode() == null ? "" : h.getHazardCode());
      m.put("name", h.getName() == null ? "" : h.getName());
      m.put("level", h.getRiskLevel() == null ? "" : h.getRiskLevel());
      m.put("type", h.getHazardType() == null ? "" : h.getHazardType());
      m.put("owner", h.getOwner() == null ? "" : h.getOwner());
      m.put("status", h.getStatus() == null ? "" : h.getStatus());
      m.put("lon", h.getLongitude() == null ? 0.0 : h.getLongitude());
      m.put("lat", h.getLatitude() == null ? 0.0 : h.getLatitude());
      return m;
    }).toList();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("hazardPoints", hazardList);
    payload.put("overviewStats", dashboardService.overviewCards());
    payload.put("charts", chartData);
    return ApiResponse.success(payload);
  }

  @GetMapping("/summary")
  public ApiResponse<Map<String, Object>> summary() {
    Map<String, Object> modules = new LinkedHashMap<>();
    modules.put("hazardPoints", hazardRepository.count());
    modules.put("monitoringDevices", deviceRepository.count());
    modules.put("warningEvents", warningRepository.count());
    modules.put("warningRules", warningRuleRepository.count());
    modules.put("inspectionTasks", taskRepository.count());
    modules.put("emergencyEvents", emergencyRepository.count());

    Map<String, Object> features = new LinkedHashMap<>();
    features.put("ruleEngine", ruleEngineEnabled);
    features.put("aiFusion", aiEnabled);
    features.put("emergencyLinkage", true);
    features.put("webSocket", true);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", "0.0.1");
    result.put("ready", true);
    result.put("modules", modules);
    result.put("features", features);
    return ApiResponse.success(result);
  }

  /** 总览指标导出（xlsx 附件流）。 */
  @PostMapping("/overview/export")
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('overview:export')")
  public void export(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
    List<OverviewCard> cards = dashboardService.overviewCards();
    List<DashboardExportRow> rows = cards.stream()
        .map(c -> new DashboardExportRow(c.label(), c.value(), c.suffix(), c.change()))
        .toList();
    excelExportService.export(response, "总览指标导出.xlsx", DashboardExportRow.class, rows);
  }
}
