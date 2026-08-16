package cn.geosentry.health;

import cn.geosentry.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class HealthController {

  @GetMapping("/health")
  public ApiResponse<Map<String, Object>> health() {
    return ApiResponse.success(Map.of(
        "status", "UP",
        "service", "geosentry-backend"
    ));
  }
}
