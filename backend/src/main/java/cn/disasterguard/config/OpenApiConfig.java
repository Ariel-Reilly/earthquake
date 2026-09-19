package cn.disasterguard.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(title = "Disasterguard API", version = "v1", description = "Disasterguard inspection backend API"))
public class OpenApiConfig {
}
