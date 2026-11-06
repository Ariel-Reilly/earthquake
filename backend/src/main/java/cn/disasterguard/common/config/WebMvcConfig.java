package cn.disasterguard.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WebMvc 通用扩展点。CORS 配置已迁移至 {@link cn.disasterguard.config.CorsConfig}，
 * 禁止在此文件或其他类中重复定义 addCorsMappings，避免配置相互覆盖。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  private final NoStoreCacheInterceptor noStoreCacheInterceptor;

  public WebMvcConfig(NoStoreCacheInterceptor noStoreCacheInterceptor) {
    this.noStoreCacheInterceptor = noStoreCacheInterceptor;
  }

  /**
   * 给所有 /api/** 响应注入 Cache-Control: no-store，
   * 避免浏览器/反向代理缓存导致前端切换页面时拿到陈旧数据。
   * 拦截器自身已经做了路径判定，注册时仍限定范围是为了双保险。
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(noStoreCacheInterceptor)
        .addPathPatterns("/api/**");
  }
}

