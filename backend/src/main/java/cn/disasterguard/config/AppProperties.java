package cn.disasterguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "disasterguard")
public record AppProperties(
    String frontendOrigin,
    String jwtSecret,
    long jwtExpireMinutes,
    String fileBaseUrl
) {
}
