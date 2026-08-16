package cn.geosentry.common.geo;

public record ViewportBounds(double minLon, double minLat, double maxLon, double maxLat) {

  public boolean contains(Double lon, Double lat) {
    if (lon == null || lat == null) return false;
    return lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
  }

  public static ViewportBounds parse(String bbox) {
    if (bbox == null || bbox.isBlank()) return null;
    String[] parts = bbox.split(",");
    if (parts.length != 4) return null;
    try {
      double minLon = Double.parseDouble(parts[0].trim());
      double minLat = Double.parseDouble(parts[1].trim());
      double maxLon = Double.parseDouble(parts[2].trim());
      double maxLat = Double.parseDouble(parts[3].trim());
      return new ViewportBounds(Math.min(minLon, maxLon), Math.min(minLat, maxLat), Math.max(minLon, maxLon), Math.max(minLat, maxLat));
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}
