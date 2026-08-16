package cn.geosentry.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(title = "Geosentry API", version = "v1", description = "Geohazard inspection backend API"))
public class OpenApiConfig {
}
