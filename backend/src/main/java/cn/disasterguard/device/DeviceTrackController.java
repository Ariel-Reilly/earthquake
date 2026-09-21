package cn.disasterguard.device;

import cn.disasterguard.common.api.ApiResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/devices")
public class DeviceTrackController {

  private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  private static final Random RANDOM = new Random();

  @GetMapping("/{deviceId}/track")
  @PreAuthorize("hasAuthority('devices:view')")
  public ApiResponse<Map<String, Object>> getTrack(
      @PathVariable String deviceId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "1000") int limit
  ) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime fromTime = from != null && !from.isBlank()
        ? OffsetDateTime.parse(from, ISO_FORMATTER)
        : now.minusHours(24);
    OffsetDateTime toTime = to != null && !to.isBlank()
        ? OffsetDateTime.parse(to, ISO_FORMATTER)
        : now;

    if (limit <= 0) {
      limit = 1000;
    }
    if (limit > 10000) {
      limit = 10000;
    }

    long totalMinutes = ChronoUnit.MINUTES.between(fromTime, toTime);
    int pointCount = (int) Math.min(totalMinutes / 5 + 1, limit);

    double baseLat = 26.08;
    double baseLon = 119.28;
    double currentLat = baseLat + (RANDOM.nextDouble() - 0.5) * 0.1;
    double currentLon = baseLon + (RANDOM.nextDouble() - 0.5) * 0.1;
    double currentHeading = RANDOM.nextDouble() * 360;

    List<Map<String, Object>> points = new ArrayList<>();
    long intervalSeconds = totalMinutes * 60 / Math.max(pointCount, 1);

    for (int i = 0; i < pointCount; i++) {
      OffsetDateTime timestamp = fromTime.plusSeconds(i * intervalSeconds);

      currentLat += (RANDOM.nextDouble() - 0.5) * 0.001;
      currentLon += (RANDOM.nextDouble() - 0.5) * 0.001;
      currentHeading = (currentHeading + (RANDOM.nextDouble() - 0.5) * 30) % 360;
      if (currentHeading < 0) {
        currentHeading += 360;
      }

      double speed = RANDOM.nextDouble() * 30;
      double altitude = 50 + RANDOM.nextDouble() * 50;
      double accuracy = 1 + RANDOM.nextDouble() * 9;

      points.add(Map.of(
          "timestamp", timestamp.toString(),
          "lat", Math.round(currentLat * 1000000.0) / 1000000.0,
          "lon", Math.round(currentLon * 1000000.0) / 1000000.0,
          "speed", Math.round(speed * 100.0) / 100.0,
          "heading", Math.round(currentHeading),
          "altitude", Math.round(altitude * 10.0) / 10.0,
          "accuracy", Math.round(accuracy * 10.0) / 10.0
      ));
    }

    Map<String, Object> result = new HashMap<>();
    result.put("deviceId", deviceId);
    result.put("deviceCode", "DV-" + deviceId.toUpperCase());
    result.put("from", fromTime.toString());
    result.put("to", toTime.toString());
    result.put("total", points.size());
    result.put("points", points);

    return ApiResponse.success(result);
  }
}
