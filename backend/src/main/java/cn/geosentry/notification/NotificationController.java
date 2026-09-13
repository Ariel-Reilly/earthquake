package cn.geosentry.notification;

import cn.geosentry.common.api.ApiResponse;
import cn.geosentry.common.api.PageResponse;
import cn.geosentry.notification.dto.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notification")
public class NotificationController {

  private final NotificationService service;

  public NotificationController(NotificationService service) {
    this.service = service;
  }

  /**
   * 发送通知（内部调用）
   */
  @PostMapping("/send")
  public ApiResponse<SendResultDto> send(@Valid @RequestBody NotificationRequestDto request) {
    String operator = currentOperator();
    SendResultDto result = service.send(request, operator);
    return ApiResponse.success(result);
  }

  /**
   * 查询发送记录
   */
  @GetMapping("/records")
  public ApiResponse<PageResponse<NotificationRecordDto>> records(
      @RequestParam(required = false) String bizType,
      @RequestParam(required = false) String disasterType,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {

    PageResponse<NotificationRecordDto> result = service.records(
        bizType, disasterType, level, status, page, size);
    return ApiResponse.success(result);
  }

  /**
   * 查询回执详情
   */
  @GetMapping("/receipts/{recordId}")
  public ApiResponse<List<NotificationReceiptDto>> receipts(@PathVariable Long recordId) {
    List<NotificationReceiptDto> result = service.receipts(recordId);
    return ApiResponse.success(result);
  }

  /**
   * 测试发送
   */
  @PostMapping("/test")
  public ApiResponse<SendResultDto> test(@RequestBody TestSendRequest request) {
    SendResultDto result = service.testSend(request);
    return ApiResponse.success(result);
  }

  /**
   * 获取渠道配置
   */
  @GetMapping("/channel-config")
  public ApiResponse<List<ChannelConfigDto>> getChannelConfigs(
      @RequestParam(required = false) String disasterType,
      @RequestParam(required = false) String level) {
    List<ChannelConfigDto> result = service.getChannelConfigs(disasterType, level);
    return ApiResponse.success(result);
  }

  /**
   * 更新渠道配置
   */
  @PutMapping("/channel-config")
  public ApiResponse<ChannelConfigDto> updateChannelConfig(@RequestBody ChannelConfigDto config) {
    ChannelConfigDto result = service.updateChannelConfig(config);
    return ApiResponse.success(result);
  }

  /**
   * 失败重发
   */
  @PostMapping("/retry/{recordId}")
  public ApiResponse<SendResultDto> retry(@PathVariable Long recordId) {
    SendResultDto result = service.retry(recordId);
    return ApiResponse.success(result);
  }

  private String currentOperator() {
    try {
      var ctx = org.springframework.security.core.context.SecurityContextHolder.getContext();
      var auth = ctx.getAuthentication();
      if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
        return auth.getName();
      }
    } catch (Exception ignored) {}
    return "system";
  }
}
