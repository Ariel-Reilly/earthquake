package cn.disasterguard.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

  @Value("${disasterguard.frontend-origin:http://localhost:5173}")
  private String frontendOrigin;

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    // 支持逗号分隔多域：配置 "http://a.com,http://b.com" 时会正确拆开
    String[] origins = Arrays.stream(frontendOrigin.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .toArray(String[]::new);
    if (origins.length == 0) {
      origins = new String[]{"http://localhost:5173"};
    }
    // 仅允许必要的请求头，避免敏感信息通过 CORS 泄露
    registry.addMapping("/**")
        .allowedOrigins(origins)
        .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
        .allowedHeaders(
            "Accept",
            "Accept-Language",
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "X-User",
            "X-Device-Key"
        )
        .exposedHeaders("Authorization", "Content-Disposition")
        .allowCredentials(true)
        .maxAge(3600);  // 预检缓存 1h
  }
}
