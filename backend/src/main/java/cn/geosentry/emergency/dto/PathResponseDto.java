package cn.geosentry.emergency.dto;

import java.util.List;

/**
 * 路径规划响应 DTO。
 * 包含多条方案（fastest / safest / shortest 等），每条带 geometry、waypoints。
 */
public record PathResponseDto(List<RouteDto> routes) {

    /**
     * 单条路径方案。
     *
     * @param id          方案 ID
     * @param mode        模式（fastest / safest / shortest）
     * @param distance    总距离（米）
     * @param duration    预计耗时（秒）
     * @param riskLevel   风险等级（low / medium / high）
     * @param obstacleCount 路径沿途避障区数量
     * @param geometry    WKT "LINESTRING(...)" 表达，未来也支持 GeoJSON
     * @param waypoints   关键节点
     */
    public record RouteDto(
        String id,
        String mode,
        long distance,
        long duration,
        String riskLevel,
        int obstacleCount,
        String geometry,
        List<Waypoint> waypoints
    ) {
        public record Waypoint(String name, double lng, double lat, String type) {}
    }
}
