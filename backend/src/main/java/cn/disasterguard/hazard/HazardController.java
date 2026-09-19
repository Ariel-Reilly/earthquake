package cn.disasterguard.hazard;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.export.ExcelExportService;
import cn.disasterguard.common.export.HazardExportRow;
import cn.disasterguard.hazard.dto.HazardDto;
import cn.disasterguard.hazard.dto.HazardUpsertRequest;
import cn.disasterguard.hazard.service.HazardService;
import jakarta.validation.Valid;
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

  private final HazardService hazardService;
  private final ExcelExportService excelExportService;

  public HazardController(HazardService hazardService, ExcelExportService excelExportService) {
    this.hazardService = hazardService;
    this.excelExportService = excelExportService;
  }

  @GetMapping
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
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    HazardDto hazard = hazardService.getById(id);
    return ApiResponse.success(Map.of(
        "item", hazard,
        "history", List.of(
            Map.of("time", "今天 09:30", "title", "巡检上报", "desc", "发现位移变化，已同步到预警中心"),
            Map.of("time", "昨天 16:20", "title", "雨后复查", "desc", "新增裂缝记录已拍照留档"),
            Map.of("time", "2026-08-08", "title", "风险评估", "desc", "风险等级保持高风险")
        ),
        "attachments", List.of(),
        "linked", List.of()
    ));
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
  public ApiResponse<List<HazardDto>> aiMonitorList() {
    return ApiResponse.success(hazardService.listAiMonitoredRecent(50));
  }
}
