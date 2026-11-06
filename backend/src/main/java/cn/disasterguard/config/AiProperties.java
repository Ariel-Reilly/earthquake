package cn.disasterguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 推理服务配置。 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
    String serviceBaseUrl,
    boolean enabled,
    long timeoutMs
) {
}
