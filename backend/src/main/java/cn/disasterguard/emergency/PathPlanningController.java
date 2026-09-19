package cn.disasterguard.emergency;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.emergency.dto.PathRequestDto;
import cn.disasterguard.emergency.dto.PathResponseDto;
import cn.disasterguard.emergency.service.PathPlanningService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * P0-1：路径规划接口。
 */
@RestController
@RequestMapping("/api/path-planning")
public class PathPlanningController {

    private final PathPlanningService pathPlanningService;

    public PathPlanningController(PathPlanningService pathPlanningService) {
        this.pathPlanningService = pathPlanningService;
    }

    /** 计算路径（多方案）。 */
    @PostMapping("/calculate")
    @PreAuthorize("hasAuthority('route:view')")
    public ApiResponse<PathResponseDto> calculate(@RequestBody PathRequestDto req) {
        List<PathResponseDto.RouteDto> routes = pathPlanningService.plan(req);
        return ApiResponse.success(new PathResponseDto(routes));
    }

    /** 获取当前避障区域。 */
    @GetMapping("/obstacles")
    public ApiResponse<List<PathRequestDto.ObstacleDto>> obstacles() {
        return ApiResponse.success(pathPlanningService.listObstacles());
    }

    /** 把路径导出为 GeoJSON（返回字典，前端再封装为下载）。 */
    @PostMapping("/export")
    @PreAuthorize("hasAuthority('route:export')")
    public ApiResponse<Map<String, Object>> exportRoute(@RequestBody PathResponseDto.RouteDto route) {
        return ApiResponse.success(pathPlanningService.toGeoJson(route));
    }
}
