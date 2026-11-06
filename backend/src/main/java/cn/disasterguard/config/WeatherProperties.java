package cn.disasterguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 气象数据接入的可调参数。对应配置前缀 <code>disasterguard.weather.*</code>。
 *
 * <ul>
 *   <li>{@code cache-enabled}：是否缓存最新帧探测结果。默认 true</li>
 *   <li>{@code cache-ttl-seconds}：缓存存活秒数，超过则下次请求重新探测。默认 300 秒（5 分钟）</li>
 *   <li>{@code proxy-enabled}：是否启用 <code>/api/weather/image/{kind}</code> 图片代理接口（CORS 兜底）</li>
 * </ul>
 *
 * <p>所有字段都有 <code>application.yml</code> 环境变量兜底，缺省时按以下取值：
 * <pre>
 *   cacheEnabled    = true
 *   cacheTtlSeconds = 300
 *   proxyEnabled    = true
 * </pre>
 */
@ConfigurationProperties(prefix = "disasterguard.weather")
public record WeatherProperties(
    boolean cacheEnabled,
    long cacheTtlSeconds,
    boolean proxyEnabled
) {

  /** 测试 / 非 Spring 上下文使用的默认值。 */
  public static WeatherProperties defaults() {
    return new WeatherProperties(true, 300L, true);
  }
}