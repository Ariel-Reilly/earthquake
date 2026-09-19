package cn.disasterguard.emergency;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.export.EmergencyExportRow;
import cn.disasterguard.common.export.ExcelExportService;
import cn.disasterguard.emergency.dto.EmergencyDto;
import cn.disasterguard.emergency.service.EmergencyService;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/emergency")
public class EmergencyController {

  private final EmergencyService emergencyService;
  private final ExcelExportService excelExportService;

  public EmergencyController(EmergencyService emergencyService, ExcelExportService excelExportService) {
    this.emergencyService = emergencyService;
    this.excelExportService = excelExportService;
  }

  @GetMapping
  public ApiResponse<PageResponse<EmergencyDto>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status
  ) {
    return ApiResponse.success(emergencyService.page(page, size, keyword, status));
  }

  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    return ApiResponse.success(Map.of("item", emergencyService.getById(id)));
  }

  @PostMapping("/{id}/close")
  @PreAuthorize("hasAuthority('emergency:close')")
  public ApiResponse<EmergencyDto> close(@PathVariable Long id, @RequestBody(required = false) Map<String, String> payload) {
    String summary = payload == null ? null : payload.get("summary");
    return ApiResponse.success("已闭环", emergencyService.resolve(id, summary));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('emergency:close')")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    if (!emergencyService.exists(id)) {
      return ApiResponse.fail(404, "未找到应急事件");
    }
    emergencyService.delete(id);
    return ApiResponse.success("已关闭", null);
  }

  @PostMapping("/export")
  @PreAuthorize("hasAuthority('emergency:export')")
  public void export(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
    excelExportService.export(response, "应急处置导出.xlsx", EmergencyExportRow.class, emergencyService.exportRows());
  }
}
