package cn.disasterguard.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Explicit Redis configuration that takes precedence over Spring Boot's auto-config.
 * Fixes an issue where LettuceConnectionFactory is not created on some environments
 * despite condition evaluation reporting all conditions matched.
 */
@Configuration
public class RedisConfig {

  private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

  @Value("${spring.data.redis.host:127.0.0.1}")
  private String host;

  @Value("${spring.data.redis.port:6379}")
  private int port;

  @Value("${spring.data.redis.password:}")
  private String password;

  @Value("${spring.data.redis.database:0}")
  private int database;

  @Bean
  @ConditionalOnMissingBean(RedisConnectionFactory.class)
  public RedisConnectionFactory redisConnectionFactory() {
    log.info("Creating explicit RedisConnectionFactory to {}:{} (db={})", host, port, database);

    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
    config.setDatabase(database);
    if (password != null && !password.isBlank()) {
      config.setPassword(RedisPassword.of(password));
    }

    SocketOptions socketOptions = SocketOptions.builder()
        .keepAlive(true)
        .tcpNoDelay(true)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    ClientOptions clientOptions = ClientOptions.builder()
        .socketOptions(socketOptions)
        .autoReconnect(true)
        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
        .build();

    LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
        .clientOptions(clientOptions)
        .commandTimeout(Duration.ofSeconds(10))
        .build();

    LettuceConnectionFactory factory = new LettuceConnectionFactory(config, clientConfig);
    factory.setShareNativeConnection(false); // per-connection for simplicity
    return factory;
  }

  @Bean
  @ConditionalOnMissingBean(name = "redisTemplate")
  public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);

    StringRedisSerializer stringSerializer = new StringRedisSerializer();
    GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

    template.setKeySerializer(stringSerializer);
    template.setHashKeySerializer(stringSerializer);
    template.setValueSerializer(jsonSerializer);
    template.setHashValueSerializer(jsonSerializer);
    template.afterPropertiesSet();

    return template;
  }
}
