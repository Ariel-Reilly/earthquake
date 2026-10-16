package cn.disasterguard.hazard;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.export.ExcelExportService;
import cn.disasterguard.common.export.HazardExportRow;
import cn.disasterguard.hazard.dto.HazardDto;
import cn.disasterguard.hazard.dto.HazardUpsertRequest;
import cn.disasterguard.hazard.service.HazardService;
import cn.disasterguard.inspection.dto.InspectionRecordDto;
import cn.disasterguard.inspection.service.InspectionService;
import cn.disasterguard.warning.dto.WarningDto;
import cn.disasterguard.warning.service.WarningService;
import jakarta.validation.Valid;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/hazards")
public class HazardController {

  private static final DateTimeFormatter HISTORY_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

  private final HazardService hazardService;
  private final ExcelExportService excelExportService;
  private final WarningService warningService;
  private final InspectionService inspectionService;

  public HazardController(HazardService hazardService, ExcelExportService excelExportService,
                          WarningService warningService, InspectionService inspectionService) {
    this.hazardService = hazardService;
    this.excelExportService = excelExportService;
    this.warningService = warningService;
    this.inspectionService = inspectionService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('hazards:view')")
  public ApiResponse<PageResponse<HazardDto>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String regionCode,
      @RequestParam(required = false, name = "level") String level,
      @RequestParam(required = false, name = "type") String type,
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String source
  ) {
    return ApiResponse.success(hazardService.page(page, size, keyword, regionCode, level, type, bbox, source));
  }

  @GetMapping("/viewport")
  @PreAuthorize("hasAuthority('hazards:view')")
  public ApiResponse<List<HazardDto>> viewport(
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String regionCode,
      @RequestParam(required = false, name = "level") String level,
      @RequestParam(required = false, name = "type") String type,
      @RequestParam(required = false) String source
  ) {
    return ApiResponse.success(hazardService.listByViewport(bbox, keyword, regionCode, level, type, source));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('hazards:view')")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    HazardDto hazard = hazardService.getById(id);
    String hazardCode = hazard.hazardCode();
    List<Map<String, Object>> history = hazardCode == null
        ? List.of()
        : inspectionService.listByHazard(hazardCode).stream().map(this::toHistoryItem).toList();
    List<Map<String, Object>> linked = hazardCode == null
        ? List.of()
        : warningService.listByHazardCode(hazardCode).stream().map(this::toLinkedItem).toList();
    return ApiResponse.success(Map.of(
        "item", hazard,
        "history", history,
        "attachments", List.of(),
        "linked", linked
    ));
  }

  /** 把巡检记录转成隐患详情页"巡检历史"面板期望的 {time, title, desc} 三元组。 */
  private Map<String, Object> toHistoryItem(InspectionRecordDto record) {
    String inspector = record.inspector() == null ? "" : record.inspector().trim();
    String title = inspector.isEmpty() ? "巡检记录" : "巡检记录 · " + inspector;
    String time = record.inspectAt() == null ? "" : record.inspectAt().format(HISTORY_TIME_FORMAT);
    String note = record.note();
    String desc = (note == null || note.isBlank()) ? "现场巡检记录" : note;
    return Map.of("time", time, "title", title, "desc", desc);
  }

  /** 把预警转成隐患详情页"关联联动"面板期望的 {type, label, path} 三元组，跳转路由见 frontend router。 */
  private Map<String, Object> toLinkedItem(WarningDto warning) {
    return Map.of(
        "type", "预警",
        "label", warning.title() == null ? "" : warning.title(),
        "path", "/dashboard/warnings/" + warning.id()
    );
  }

  @PostMapping
  @PreAuthorize("hasAuthority('hazards:create')")
  public ApiResponse<HazardDto> create(@Valid @RequestBody HazardUpsertRequest request) {
    return ApiResponse.success("创建成功", hazardService.create(request));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('hazards:edit')")
  public ApiResponse<HazardDto> update(@PathVariable Long id, @Valid @RequestBody HazardUpsertRequest request) {
    return ApiResponse.success("更新成功", hazardService.update(id, request));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('hazards:delete')")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    hazardService.delete(id);
    return ApiResponse.success("删除成功", null);
  }

  @PostMapping("/export")
  @PreAuthorize("hasAuthority('hazards:export')")
  public void export(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
    excelExportService.export(response, "灾害点巡检记录.xlsx", HazardExportRow.class, hazardService.exportRows());
  }

  @GetMapping("/geojson")
  @PreAuthorize("hasAuthority('hazards:view')")
  public ApiResponse<Map<String, Object>> geojson(
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String regionCode,
      @RequestParam(required = false, name = "level") String level,
      @RequestParam(required = false, name = "type") String type,
      @RequestParam(required = false) String source
  ) {
    return ApiResponse.success(hazardService.geojson(bbox, keyword, regionCode, level, type, source));
  }

  /**
   * v1.6.0：AI 实时监测专用列表 —— 严格按 discovered_at DESC 排序。
   * 不分页（最多 50 条），供"AI 实时监测"面板秒级刷新用。
   */
  @GetMapping("/ai-monitor")
  @PreAuthorize("hasAuthority('hazards:view')")
  public ApiResponse<List<HazardDto>> aiMonitorList() {
    return ApiResponse.success(hazardService.listAiMonitoredRecent(50));
  }
}
