package cn.disasterguard.ai;

import cn.disasterguard.common.api.ApiResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 哨兵卫星覆盖率查询（v1.7.0+ 卫星合成图层）。
 *
 * <p>前端 dashboardApi.js 依赖：{@code GET /api/sentinel/coverage}.
 *
 * <p>参数 {@code bbox} 格式：{@code "minLon,minLat,maxLon,maxLat"}（如 {@code "73,18,135,54"}）。
 * 返回哨兵2（Sentinel-2）覆盖切片列表（当前为 mock 数据）。
 *
 * <p>后续接入 ESA SciHub / Microsoft Planetary Computer 时替换为真实卫星 API 调用。
 */
@RestController
@RequestMapping("/api/sentinel")
public class SentinelController {

  private static final String[] SATELLITES = {"Sentinel-2A", "Sentinel-2B"};
  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

  /**
   * 查询指定 bbox 范围内的哨兵卫星覆盖数据。
   *
   * @param bbox 视野边界，格式 {@code "minLon,minLat,maxLon,maxLat"}，
   *             默认返回中国全境覆盖
   */
  @GetMapping("/coverage")
  public ApiResponse<Map<String, Object>> coverage(
      @RequestParam(required = false) String bbox) {

    double minLon, minLat, maxLon, maxLat;
    if (bbox != null && !bbox.isBlank()) {
      String[] parts = bbox.split(",");
      if (parts.length == 4) {
        try {
          minLon = Double.parseDouble(parts[0].trim());
          minLat = Double.parseDouble(parts[1].trim());
          maxLon = Double.parseDouble(parts[2].trim());
          maxLat = Double.parseDouble(parts[3].trim());
        } catch (NumberFormatException e) {
          return ApiResponse.fail(400, "bbox 格式错误，期望：minLon,minLat,maxLon,maxLat");
        }
      } else {
        return ApiResponse.fail(400, "bbox 必须包含 4 个数值，用逗号分隔");
      }
    } else {
      // 默认中国全境
      minLon = 73.0;
      minLat = 18.0;
      maxLon = 135.0;
      maxLat = 54.0;
    }

    // 沿 bbox 边长生成 8 个 mock 切片
    List<Map<String, Object>> tiles = generateMockTiles(minLon, minLat, maxLon, maxLat);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", tiles);
    result.put("total", tiles.size());
    result.put("bbox", Map.of(
        "minLon", minLon,
        "minLat", minLat,
        "maxLon", maxLon,
        "maxLat", maxLat
    ));
    result.put("timestamp", System.currentTimeMillis());
    return ApiResponse.success(result);
  }

  /** 生成 mock 卫星切片数据。 */
  private List<Map<String, Object>> generateMockTiles(
      double minLon, double minLat, double maxLon, double maxLat) {
    List<Map<String, Object>> tiles = new ArrayList<>();
    int count = 8;
    double stepLon = (maxLon - minLon) / count;
    double stepLat = (maxLat - minLat) / count;
    ThreadLocalRandom rand = ThreadLocalRandom.current();
    LocalDate today = LocalDate.now();

    for (int i = 0; i < count; i++) {
      for (int j = 0; j < 2; j++) {
        double tileMinLon = minLon + i * stepLon;
        double tileMinLat = minLat + j * stepLat;
        double tileMaxLon = tileMinLon + stepLon;
        double tileMaxLat = tileMinLat + stepLat;

        // 随机偏移一点点，避免格子对齐感
        double offset = stepLon * 0.05;
        tileMinLon += rand.nextDouble(-offset, offset);
        tileMaxLon += rand.nextDouble(-offset, offset);

        String satellite = SATELLITES[rand.nextInt(SATELLITES.length)];
        LocalDate date = today.minusDays(rand.nextInt(1, 15));
        int cloudCover = rand.nextInt(0, 60); // 0-60% 云量

        // tileId 格式：S2A_20260901_L2A_T50SMK
        String tileId = String.format("%s_%s_L2A_%s%02d%02d",
            satellite.replace("-", "").toUpperCase().substring(0, 3),
            date.format(DATE_FMT).replace("-", ""),
            charFor((int) (tileMinLon + 180) % 26),
            (int) (tileMinLat + 90),
            (int) (tileMinLon + 180) % 100
        );

        Map<String, Object> tile = new LinkedHashMap<>();
        tile.put("tileId", tileId);
        tile.put("satellite", satellite);
        tile.put("date", date.toString());
        tile.put("cloudCover", cloudCover);
        tile.put("url", String.format(
            "https://scihub.copernicus.eu/dhus/odata/v1/Products('%s')/Nodes('%s')/Nodes('IMG_DATA')",
            tileId, tileId));
        tile.put("bbox", List.of(tileMinLon, tileMinLat, tileMaxLon, tileMaxLat));
        tiles.add(tile);
      }
    }
    return tiles;
  }

  private static char charFor(int n) {
    return (char) ('A' + Math.abs(n) % 26);
  }
}
