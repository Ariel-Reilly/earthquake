package cn.disasterguard.device;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.device.dto.DeviceImageEventDto;
import cn.disasterguard.device.service.DeviceImageService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 设备图像上传入口（AI 图像识别链路第一步）。
 *
 * <p>独立于 {@link DeviceController}：设备增删改查与媒体接入的权限模型、
 * 限流策略和演进节奏都不同，混在一个控制器里会让后续接入 AI 识别时
 * 类体量继续膨胀。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceMediaController {

  private final DeviceImageService deviceImageService;

  public DeviceMediaController(DeviceImageService deviceImageService) {
    this.deviceImageService = deviceImageService;
  }

  /**
   * 上传设备图像并落 MinIO。
   *
   * <p>本阶段只做接收与存储，不触发 AI 识别、不产生预警；
   * 返回的图像事件上下文即后续识别链路的输入。
   *
   * @param deviceId 设备编号（如 DV-002）或设备主键 id
   * @param file     图片文件，multipart 表单字段名固定为 file
   * @param lng      可选经度；缺省时取设备档案坐标
   * @param lat      可选纬度；缺省时取设备档案坐标
   */
  @PostMapping(value = "/{deviceId}/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyAuthority('devices:edit', 'device:report')")
  public ApiResponse<DeviceImageEventDto> uploadImage(
      @PathVariable String deviceId,
      @RequestPart("file") MultipartFile file,
      @RequestParam(value = "lng", required = false) Double lng,
      @RequestParam(value = "lat", required = false) Double lat) {
    return ApiResponse.success("图像已接收", deviceImageService.receive(deviceId, file, lng, lat));
  }
}
