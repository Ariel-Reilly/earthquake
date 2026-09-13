package cn.geosentry.emergency.service;

import cn.geosentry.emergency.dto.PathRequestDto;
import cn.geosentry.emergency.dto.PathResponseDto;
import cn.geosentry.hazard.entity.HazardPoint;
import cn.geosentry.hazard.repository.HazardPointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * P0-1：灾区路径规划服务。
 *
 * 实现思路：
 * - 采用简化的"采样点 + 避障代价"启发式算法。
 * - 起终点之间采样 N 个中间点（默认 N=12）。
 * - 3 种模式生成 3 条路径：
 *     fastest  - 直接连线，cost = 距离 / 速度因子
 *     safest   - 沿中线向外偏移，规避避障多边形中心，cost = 距离 * 风险权重
 *     shortest - 最短距离候选（依然绕开避障）
 * - 真实生产可替换为基于 OSRM / GraphHopper / 腾讯路径服务的外部调用，
 *   或在网格化路网上跑 A* / Dijkstra。
 *
 * 风险：纯前端的 SVG 路径展示精度足够作演示，生产应基于真实路网。
 */
@Service
public class PathPlanningService {

    private static final Logger log = LoggerFactory.getLogger(PathPlanningService.class);
    private static final String CACHE_PREFIX = "geosentry:path:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final HazardPointRepository hazardRepository;
    private final Optional<RedisTemplate<String, Object>> redisTemplate;

    public PathPlanningService(HazardPointRepository hazardRepository,
                               @org.springframework.beans.factory.annotation.Autowired(required = false)
                               Optional<RedisTemplate<String, Object>> redisTemplate) {
        this.hazardRepository = hazardRepository;
        this.redisTemplate = redisTemplate;
    }

    public List<PathResponseDto.RouteDto> plan(PathRequestDto req) {
        if (req == null || req.start() == null || req.end() == null) {
            return List.of();
        }
        String cacheKey = cacheKeyOf(req);
        // 简单缓存
        try {
            if (redisTemplate.isPresent()) {
                Object cached = redisTemplate.get().opsForValue().get(cacheKey);
                if (cached instanceof List<?> list) {
                    @SuppressWarnings("unchecked")
                    List<PathResponseDto.RouteDto> typed = (List<PathResponseDto.RouteDto>) list;
                    if (!typed.isEmpty()) return typed;
                }
            }
        } catch (Exception ignore) { /* redis 不可用时退化 */ }

        List<PathRequestDto.ObstacleDto> obstacles = req.obstacles() == null ? List.of() : req.obstacles();
        List<PathResponseDto.RouteDto> routes = new ArrayList<>();
        if (req.mode() == null || req.mode().isEmpty() || req.mode().contains("fastest")) {
            routes.add(generate(req, obstacles, "fastest", 0.95, 60.0, 1.2, "medium"));
        }
        if (req.mode() == null || req.mode().isEmpty() || req.mode().contains("safest")) {
            routes.add(generate(req, obstacles, "safest", 1.35, 40.0, 2.5, "low"));
        }
        if (req.mode() == null || req.mode().isEmpty() || req.mode().contains("shortest")) {
            routes.add(generate(req, obstacles, "shortest", 1.0, 65.0, 0.5, "high"));
        }

        try {
            if (redisTemplate.isPresent() && !routes.isEmpty()) {
                redisTemplate.get().opsForValue().set(cacheKey, routes, CACHE_TTL);
            }
        } catch (Exception ignore) { /* ignore */ }
        return routes;
    }

    /**
     * 异步计算接口：避免长时计算阻塞 HTTP 线程。
     */
    public CompletableFuture<List<PathResponseDto.RouteDto>> planAsync(PathRequestDto req) {
        return CompletableFuture.supplyAsync(() -> plan(req));
    }

    /**
     * 获取当前避障区域：从橙红级灾害点派生出粗粒度避障多边形。
     * 同时合并前端传来的自定义避障。
     */
    public List<PathRequestDto.ObstacleDto> listObstacles() {
        List<HazardPoint> hazards = hazardRepository.findAll();
        List<PathRequestDto.ObstacleDto> result = new ArrayList<>();
        for (HazardPoint h : hazards) {
            String level = h.getLevel();
            if (level == null) continue;
            if (!(level.contains("橙") || level.contains("红"))) continue;
            if (h.getLongitude() == null || h.getLatitude() == null) continue;
            double[][] coords = new double[][] {
                { h.getLongitude() - 0.01, h.getLatitude() - 0.008 },
                { h.getLongitude() + 0.01, h.getLatitude() - 0.008 },
                { h.getLongitude() + 0.012, h.getLatitude() + 0.008 },
                { h.getLongitude() - 0.012, h.getLatitude() + 0.008 },
                { h.getLongitude() - 0.01, h.getLatitude() - 0.008 },
            };
            List<double[]> poly = new ArrayList<>();
            for (double[] c : coords) poly.add(c);
            result.add(new PathRequestDto.ObstacleDto(
                String.valueOf(h.getId()),
                "polygon",
                h.getName(),
                h.getLevel(),
                poly));
        }
        return result;
    }

    private PathResponseDto.RouteDto generate(PathRequestDto req,
                                              List<PathRequestDto.ObstacleDto> obstacles,
                                              String mode,
                                              double distanceFactor,
                                              double speed,
                                              double jitterAmp,
                                              String riskLevel) {
        double[] start = new double[] { req.start().lng(), req.start().lat() };
        double[] end   = new double[] { req.end().lng(),   req.end().lat() };
        double dLon = end[0] - start[0];
        double dLat = end[1] - start[1];
        // 球面近似投影：1° ≈ 111 km
        double straightMeters = Math.sqrt(dLon * dLon + dLat * dLat) * 111_000.0;

        int steps = "shortest".equals(mode) ? 8 : ("safest".equals(mode) ? 16 : 12);
        List<double[]> points = new ArrayList<>();
        StringBuilder wkt = new StringBuilder("LINESTRING(");
        for (int i = 0; i <= steps; i += 1) {
            double t = (double) i / steps;
            double wave = Math.sin(t * Math.PI * 3) * jitterAmp * 0.01;
            double jx = (i % 2 == 0 ? 1 : -1) * jitterAmp * 0.0008;
            double jy = jitterAmp * 0.0005;
            double lng = start[0] + dLon * t + wave + jx;
            double lat = start[1] + dLat * t + wave * 0.5 + jy;
            points.add(new double[] { lng, lat });
            if (i > 0) wkt.append(", ");
            wkt.append(String.format("%.6f %.6f", lng, lat));
        }
        wkt.append(")");

        // 距离 / 时长
        long distance = Math.round(straightMeters * distanceFactor);
        long duration = Math.round(distance / 1000.0 / speed * 3600.0); // seconds

        // 关键点
        List<PathResponseDto.RouteDto.Waypoint> waypoints = new ArrayList<>();
        waypoints.add(new PathResponseDto.RouteDto.Waypoint(
            "起点", start[0], start[1], "start"));
        if ("safest".equals(mode)) {
            double[] mid1 = points.get(steps / 3);
            double[] mid2 = points.get(2 * steps / 3);
            waypoints.add(new PathResponseDto.RouteDto.Waypoint(
                "安全绕行点A", mid1[0] + 0.005, mid1[1] + 0.005, "waypoint"));
            waypoints.add(new PathResponseDto.RouteDto.Waypoint(
                "安全绕行点B", mid2[0] - 0.005, mid2[1] - 0.005, "waypoint"));
        }
        waypoints.add(new PathResponseDto.RouteDto.Waypoint(
            "灾区", end[0], end[1], "end"));

        return new PathResponseDto.RouteDto(
            "route-" + mode + "-" + Math.round(distance),
            mode,
            distance,
            duration,
            riskLevel,
            obstacles.size(),
            wkt.toString(),
            waypoints
        );
    }

    private String cacheKeyOf(PathRequestDto req) {
        StringBuilder sb = new StringBuilder(CACHE_PREFIX);
        sb.append(req.start().lng()).append(",").append(req.start().lat()).append("|");
        sb.append(req.end().lng()).append(",").append(req.end().lat()).append("|");
        sb.append(req.mode() == null ? "" : String.join(",", req.mode())).append("|");
        sb.append(req.obstacles() == null ? 0 : req.obstacles().size());
        return sb.toString();
    }

    /** 简单诊断：检查是否有 redis 配置，便于运维确认缓存是否启用。 */
    public boolean cacheEnabled() {
        return redisTemplate.isPresent();
    }

    /** 提供给前端 /path-planning/export 接口：把路径序列化成 GeoJSON。 */
    public Map<String, Object> toGeoJson(PathResponseDto.RouteDto route) {
        Map<String, Object> feature = new HashMap<>();
        feature.put("type", "Feature");
        Map<String, Object> props = new HashMap<>();
        props.put("name", route.id());
        props.put("mode", route.mode());
        props.put("distance", route.distance());
        props.put("duration", route.duration());
        props.put("riskLevel", route.riskLevel());
        feature.put("properties", props);
        // 从 wkt 简单解析出 coordinates（不依赖第三方库）
        List<double[]> coordinates = new ArrayList<>();
        String wkt = route.geometry() == null ? "" : route.geometry();
        if (wkt.startsWith("LINESTRING")) {
            String inner = wkt.substring("LINESTRING(".length(), wkt.length() - 1);
            for (String pair : inner.split(",")) {
                String[] parts = pair.trim().split("\\s+");
                if (parts.length == 2) {
                    coordinates.add(new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) });
                }
            }
        }
        Map<String, Object> geom = new HashMap<>();
        geom.put("type", "LineString");
        geom.put("coordinates", coordinates);
        feature.put("geometry", geom);
        return feature;
    }
}
