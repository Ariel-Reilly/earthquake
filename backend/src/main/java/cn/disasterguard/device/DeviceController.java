package cn.disasterguard.device;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.export.DeviceExportRow;
import cn.disasterguard.common.export.ExcelExportService;
import cn.disasterguard.device.dto.DeviceDto;
import cn.disasterguard.device.dto.DeviceUpsertRequest;
import cn.disasterguard.device.service.DeviceDataService;
import cn.disasterguard.device.service.DeviceService;
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
@RequestMapping("/api/devices")
public class DeviceController {

  private final DeviceService deviceService;
  private final ExcelExportService excelExportService;
  private final DeviceDataService deviceDataService;

  public DeviceController(DeviceService deviceService, ExcelExportService excelExportService, DeviceDataService deviceDataService) {
    this.deviceService = deviceService;
    this.excelExportService = excelExportService;
    this.deviceDataService = deviceDataService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('devices:view')")
  public ApiResponse<PageResponse<DeviceDto>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, name = "state") String state,
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String zoom
  ) {
    return ApiResponse.success(deviceService.page(page, size, keyword, state));
  }

  @GetMapping("/viewport")
  @PreAuthorize("hasAuthority('devices:view')")
  public ApiResponse<List<DeviceDto>> viewport(
      @RequestParam(required = false) String bbox,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, name = "state") String state,
      @RequestParam(required = false) String zoom
  ) {
    return ApiResponse.success(deviceService.listByViewport(bbox, keyword, state));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('devices:view')")
  public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
    return ApiResponse.success(Map.of("item", deviceService.getById(id)));
  }

  /**
   * 设备数据上报（HTTP 链路入口）：
   * 由 {@link cn.disasterguard.common.security.DeviceAuthFilter} 校验 X-Device-Key 后放行，
   * 写入 device_metric 表 + TDengine 时序存储（若启用），供规则引擎消费。
   */
  @PostMapping("/{deviceCode}/data")
  @PreAuthorize("hasAuthority('devices:edit')")
  public ApiResponse<Map<String, Object>> reportData(@PathVariable String deviceCode,
                                                     @RequestBody Map<String, Object> payload) {
    int count = deviceDataService.ingest(deviceCode, payload);
    return ApiResponse.success("已接收 " + count + " 条指标", Map.of("ingested", count));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('devices:create')")
  public ApiResponse<DeviceDto> create(@Valid @RequestBody DeviceUpsertRequest request) {
    return ApiResponse.success("创建成功", deviceService.create(request));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('devices:edit')")
  public ApiResponse<DeviceDto> update(@PathVariable Long id, @Valid @RequestBody DeviceUpsertRequest request) {
    return ApiResponse.success("更新成功", deviceService.update(id, request));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('devices:delete')")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    deviceService.delete(id);
    return ApiResponse.success("删除成功", null);
  }

  @PostMapping("/{id}/diagnose")
  @PreAuthorize("hasAuthority('devices:edit')")
  public ApiResponse<DeviceDto> diagnose(@PathVariable Long id) {
    return ApiResponse.success(deviceService.diagnose(id));
  }

  @PostMapping("/export")
  @PreAuthorize("hasAuthority('devices:export')")
  public void export(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
    excelExportService.export(response, "设备状态导出.xlsx", DeviceExportRow.class, deviceService.exportRows());
  }
}
