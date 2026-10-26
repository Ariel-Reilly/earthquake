package cn.disasterguard.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AppProperties.class, JwtProperties.class, AiProperties.class, MinioProperties.class, WeatherProperties.class, ExternalDataProperties.class, AiImageProperties.class})
public class PropertiesConfig {

  @Bean
  public MinioClient minioClient(MinioProperties props) {
    return MinioClient.builder()
        .endpoint(props.endpoint())
        .credentials(props.accessKey(), props.secretKey())
        .build();
  }
}
