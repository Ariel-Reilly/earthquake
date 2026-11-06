package cn.disasterguard.ai;

import cn.disasterguard.ai.dto.AiAnalysisRecordDto;
import cn.disasterguard.ai.service.AiAnalysisService;
import cn.disasterguard.ai.scheduler.AiForecastScheduler;
import cn.disasterguard.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI 分析记录查询。 */
@RestController
@RequestMapping("/api/ai/analysis")
public class AiAnalysisController {

  private final AiAnalysisService service;
  private final AiForecastScheduler scheduler;

  public AiAnalysisController(AiAnalysisService service, AiForecastScheduler scheduler) {
    this.service = service;
    this.scheduler = scheduler;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('analytics:view')")
  public ApiResponse<List<AiAnalysisRecordDto>> list(
      @RequestParam String businessType,
      @RequestParam String businessCode
  ) {
    return ApiResponse.success(service.list(businessType, businessCode));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    service.delete(id);
    return ApiResponse.success("删除成功", null);
  }

  /** 手动触发一次批量预测（联调用，仅管理员）。 */
  @PostMapping("/forecast/run")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Map<String, Object>> runForecast() {
    return ApiResponse.success(scheduler.runOnce());
  }
}
