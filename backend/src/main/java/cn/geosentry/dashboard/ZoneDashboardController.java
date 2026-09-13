package cn.geosentry.dashboard;

import cn.geosentry.common.api.ApiResponse;
import cn.geosentry.dashboard.dto.ZoneDashboardDto;
import cn.geosentry.dashboard.dto.ZoneDashboardDto.RelatedEmergency;
import cn.geosentry.dashboard.dto.ZoneDashboardDto.RelatedWarning;
import cn.geosentry.dashboard.dto.ZoneDashboardDto.TopHazard;
import cn.geosentry.dashboard.dto.ZoneDashboardDto.TrendPoint;
import cn.geosentry.dashboard.service.ZoneDashboardService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P0-5：区域专项大屏接口。
 * 提供独立端点（按 KPI / 趋势 / Top / 关联预警 / 关联应急），
 * 前端可一次拿全部或按需获取。
 */
@RestController
@RequestMapping("/api/dashboard/zone")
public class ZoneDashboardController {

  private final ZoneDashboardService zoneDashboardService;

  public ZoneDashboardController(ZoneDashboardService zoneDashboardService) {
    this.zoneDashboardService = zoneDashboardService;
  }

  @GetMapping("/kpi")
  public ApiResponse<ZoneDashboardDto> kpi(@RequestParam(required = false) String region,
                                            @RequestParam(required = false) String kind,
                                            @RequestParam(defaultValue = "7d") String range) {
    return ApiResponse.success(zoneDashboardService.aggregate(
        zoneDashboardService.mapRegion(region),
        zoneDashboardService.mapKind(kind),
        range));
  }

  @GetMapping("/trend")
  public ApiResponse<List<TrendPoint>> trend(@RequestParam(defaultValue = "7d") String range) {
    return ApiResponse.success(zoneDashboardService.aggregate("all", "all", range).trendPoints());
  }

  @GetMapping("/top-hazards")
  public ApiResponse<List<TopHazard>> top(@RequestParam(required = false) String region,
                                          @RequestParam(required = false) String kind) {
    return ApiResponse.success(zoneDashboardService.aggregate(region, kind, "7d").topHazards());
  }

  @GetMapping("/related-warnings")
  public ApiResponse<List<RelatedWarning>> warnings(@RequestParam(required = false) String region,
                                                    @RequestParam(required = false) String kind) {
    return ApiResponse.success(zoneDashboardService.aggregate(region, kind, "7d").relatedWarnings());
  }

  @GetMapping("/related-emergencies")
  public ApiResponse<List<RelatedEmergency>> emergencies(@RequestParam(required = false) String region) {
    return ApiResponse.success(zoneDashboardService.aggregate(region, "all", "7d").relatedEmergencies());
  }
}
