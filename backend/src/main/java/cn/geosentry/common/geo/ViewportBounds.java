package cn.geosentry.common.geo;

/**
 * 视口边界（bbox）：minLon, minLat, maxLon, maxLat。
 *
 * <p>使用 EPSG:4326 经纬度（PostGIS 兼容），{@link #parse(String)} 同时兼容 bbox
 * 格式 minLon,minLat,maxLon,maxLat 和 maxLon,maxLat,minLon,minLat（自动规整）。
 */
public record ViewportBounds(double minLon, double minLat, double maxLon, double maxLat) {

  public boolean contains(Double lon, Double lat) {
    if (lon == null || lat == null) return false;
    return lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
  }

  /**
   * 解析 "{@code minLon,minLat,maxLon,maxLat}" 格式的 bbox 字符串。
   * 同时对坐标范围做合理性校验：经度 [-180, 180]、纬度 [-90, 90]、且 min <= max。
   *
   * @return 合法的视口边界，或 null（解析失败 / 坐标越界）
   */
  public static ViewportBounds parse(String bbox) {
    if (bbox == null || bbox.isBlank()) return null;
    String[] parts = bbox.split(",");
    if (parts.length != 4) return null;
    try {
      double aLon = Double.parseDouble(parts[0].trim());
      double aLat = Double.parseDouble(parts[1].trim());
      double bLon = Double.parseDouble(parts[2].trim());
      double bLat = Double.parseDouble(parts[3].trim());
      // 坐标合理性校验（防御恶意值）
      if (!isValidLon(aLon) || !isValidLon(bLon) || !isValidLat(aLat) || !isValidLat(bLat)) {
        return null;
      }
      double minLon = Math.min(aLon, bLon);
      double maxLon = Math.max(aLon, bLon);
      double minLat = Math.min(aLat, bLat);
      double maxLat = Math.max(aLat, bLat);
      // 拒绝过大的 bbox（面积超过 180°×90° 视为无效）
      if (maxLon - minLon > 180 || maxLat - minLat > 90) {
        return null;
      }
      return new ViewportBounds(minLon, minLat, maxLon, maxLat);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static boolean isValidLon(double lon) {
    return lon >= -180 && lon <= 180;
  }

  private static boolean isValidLat(double lat) {
    return lat >= -90 && lat <= 90;
  }
}
