package cn.disasterguard.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WebMvc 通用扩展点。CORS 配置已迁移至 {@link cn.disasterguard.config.CorsConfig}，
 * 禁止在此文件或其他类中重复定义 addCorsMappings，避免配置相互覆盖。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
}
