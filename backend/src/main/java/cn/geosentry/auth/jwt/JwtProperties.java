package cn.geosentry.auth.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "geosentry.jwt")
public record JwtProperties(
    String secret,
    Duration expire,
    String issuer
) {
}
