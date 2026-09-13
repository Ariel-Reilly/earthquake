package cn.geosentry.emergency.dto;

import java.util.List;

/**
 * 路径规划请求 DTO。起终点 + 障碍物 + 模式。
 */
public record PathRequestDto(
    PointDto start,
    PointDto end,
    List<ObstacleDto> obstacles,
    List<String> mode
) {
    public record PointDto(double lng, double lat) {}

    /**
     * 障碍物；先支持 polygon，未来可扩展 circle / corridor。
     */
    public record ObstacleDto(
        String id,
        String type,
        String name,
        String level,
        List<double[]> coordinates
    ) {}
}
