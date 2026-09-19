package cn.disasterguard.auth.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "disasterguard.jwt")
public record JwtProperties(
    String secret,
    Duration expire,
    String issuer
) {
}
