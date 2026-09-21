package cn.disasterguard.dashboard;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.dashboard.dao.ZoneDashboardDto;
import cn.disasterguard.dashboard.dao.ZoneDashboardDto.RelatedEmergency;
import cn.disasterguard.dashboard.dao.ZoneDashboardDto.RelatedWarning;
import cn.disasterguard.dashboard.dao.ZoneDashboardDto.TopHazard;
import cn.disasterguard.dashboard.dao.ZoneDashboardDto.TrendPoint;
import cn.disasterguard.dashboard.service.ZoneDashboardService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

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
  @PreAuthorize("hasAuthority('overview:view')")
  public ApiResponse<ZoneDashboardDto> kpi(@RequestParam(required = false) String region,
                                            @RequestParam(required = false) String kind,
                                            @RequestParam(defaultValue = "7d") String range) {
    return ApiResponse.success(zoneDashboardService.aggregate(
        zoneDashboardService.mapRegion(region),
        zoneDashboardService.mapKind(kind),
        range));
  }

  /**
   * 风险趋势。
   *
   * <p>v2.2 补齐 region/kind：原先硬编码 {@code "all","all"}，导致前端
   * {@code getZoneRiskTrend} 传来的区域与灾种过滤被静默忽略——切换区域时趋势图不变。
   * 底层 {@link ZoneDashboardService#aggregate} 本来就支持这两个维度，这里与
   * {@link #kpi} 保持一致的取值与归一化方式。
   */
  @GetMapping("/trend")
  @PreAuthorize("hasAuthority('overview:view')")
  public ApiResponse<List<TrendPoint>> trend(@RequestParam(required = false) String region,
                                             @RequestParam(required = false) String kind,
                                             @RequestParam(defaultValue = "7d") String range) {
    return ApiResponse.success(zoneDashboardService.aggregate(
        zoneDashboardService.mapRegion(region),
        zoneDashboardService.mapKind(kind),
        range).trendPoints());
  }

  @GetMapping("/top-hazards")
  @PreAuthorize("hasAuthority('overview:view')")
  public ApiResponse<List<TopHazard>> top(@RequestParam(required = false) String region,
                                          @RequestParam(required = false) String kind) {
    return ApiResponse.success(zoneDashboardService.aggregate(region, kind, "7d").topHazards());
  }

  @GetMapping("/related-warnings")
  @PreAuthorize("hasAuthority('overview:view')")
  public ApiResponse<List<RelatedWarning>> warnings(@RequestParam(required = false) String region,
                                                    @RequestParam(required = false) String kind) {
    return ApiResponse.success(zoneDashboardService.aggregate(region, kind, "7d").relatedWarnings());
  }

  @GetMapping("/related-emergencies")
  @PreAuthorize("hasAuthority('overview:view')")
  public ApiResponse<List<RelatedEmergency>> emergencies(@RequestParam(required = false) String region) {
    return ApiResponse.success(zoneDashboardService.aggregate(region, "all", "7d").relatedEmergencies());
  }
}
