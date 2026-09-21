package cn.disasterguard.inspection;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.inspection.dto.GpsCheckRequest;
import cn.disasterguard.inspection.dto.GpsCheckResult;
import cn.disasterguard.inspection.dto.InspectionRecordDto;
import cn.disasterguard.inspection.dto.InspectionSubmitRequest;
import cn.disasterguard.inspection.service.InspectionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/inspection")
public class InspectionController {

  private final InspectionService inspectionService;

  public InspectionController(InspectionService inspectionService) {
    this.inspectionService = inspectionService;
  }

  @PostMapping("/gps-check")
  @PreAuthorize("hasAuthority('inspection:submit')")
  public ApiResponse<GpsCheckResult> gpsCheck(@Valid @RequestBody GpsCheckRequest request) {
    return ApiResponse.success(inspectionService.checkFence(request));
  }

  @PostMapping("/submit")
  @PreAuthorize("hasAuthority('inspection:submit')")
  public ApiResponse<InspectionRecordDto> submit(@Valid @RequestBody InspectionSubmitRequest request) {
    return ApiResponse.success("上报成功", inspectionService.submit(request));
  }

  @GetMapping("/task/{taskCode}")
  @PreAuthorize("hasAuthority('inspection:view')")
  public ApiResponse<List<InspectionRecordDto>> listByTask(@PathVariable String taskCode) {
    return ApiResponse.success(inspectionService.listByTask(taskCode));
  }

  @GetMapping("/hazard/{hazardCode}")
  @PreAuthorize("hasAuthority('inspection:view')")
  public ApiResponse<List<InspectionRecordDto>> listByHazard(@PathVariable String hazardCode) {
    return ApiResponse.success(inspectionService.listByHazard(hazardCode));
  }

  @GetMapping("/example")
  @PreAuthorize("hasAuthority('inspection:view')")
  public ApiResponse<Map<String, Object>> example() {
    Map<String, Object> sample = new java.util.LinkedHashMap<>();
    sample.put("taskCode", "TK-1750418932741");
    sample.put("hazardCode", "HZ-LA-003");
    sample.put("lon", 118.5421);
    sample.put("lat", 30.1284);
    sample.put("allowedRadius", 200);
    sample.put("inspector", "周小雨");
    sample.put("abnormalFlag", false);
    sample.put("note", "山体表面无异常，监测设备运行正常");
    sample.put("photoIds", List.of("photo-001", "photo-002"));
    Map<String, Object> formData = new java.util.LinkedHashMap<>();
    formData.put("displacement_mm", 2.3);
    formData.put("crack_width_mm", 0);
    formData.put("rainfall_mm", 5.2);
    sample.put("formData", formData);
    Map<String, Object> wrapper = new java.util.LinkedHashMap<>();
    wrapper.put("description", "巡检上报接口示例 payload，用于移动端联调参考");
    wrapper.put("submitUrl", "/api/inspection/submit");
    wrapper.put("method", "POST");
    wrapper.put("sample", sample);
    return ApiResponse.success(wrapper);
  }
}
