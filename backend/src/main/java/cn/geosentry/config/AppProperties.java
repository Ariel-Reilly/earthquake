package cn.geosentry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "geosentry")
public record AppProperties(
    String frontendOrigin,
    String jwtSecret,
    long jwtExpireMinutes,
    String fileBaseUrl
) {
}
