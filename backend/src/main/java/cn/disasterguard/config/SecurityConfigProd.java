package cn.disasterguard.config;

import cn.disasterguard.common.security.DeviceAuthFilter;
import cn.disasterguard.common.security.JwtAuthenticationFilter;
import cn.disasterguard.common.security.RateLimitFilter;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 生产环境 SecurityFilterChain（prod profile）。
 *
 * 与默认 SecurityConfig 的主要区别：
 * - Swagger UI / API Docs 不再 permitAll，生产环境应关闭或限内部 IP 访问
 * - 如需在生产开启 Swagger，请通过环境变量 SWAGGER_ENABLED=true 显式启用
 *
 * @see SecurityConfig 默认配置（非 prod 环境）
 */
@Profile("prod")
@Configuration
@EnableMethodSecurity
public class SecurityConfigProd {

  @Value("${disasterguard.device.auth-strict-mode:true}")
  private boolean deviceAuthStrictMode;

  @Value("${disasterguard.security.dev-users-enabled:false}")
  private boolean devUsersEnabled;

  @Value("${swagger.enabled:false}")
  private boolean swaggerEnabled;

  @Bean
  public SecurityFilterChain securityFilterChainProd(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
      MonitoringDeviceRepository deviceRepository, RateLimitFilter rateLimitFilter) throws Exception {
    DeviceAuthFilter deviceAuthFilter = new DeviceAuthFilter(deviceRepository, deviceAuthStrictMode);
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> {
          // Swagger：默认禁用（生产不应暴露接口文档）；设置 swagger.enabled=true 可临时开启
          if (swaggerEnabled) {
            auth.requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll();
          }
          auth
              .requestMatchers("/api/system/health", "/api/auth/login").permitAll()
              .requestMatchers("/ws").permitAll()
              .requestMatchers("/api/weather/**").permitAll()
              .requestMatchers("/api/sentinel/**").permitAll()
              .requestMatchers("/api/devices/{deviceCode}/data").hasRole("DEVICE")
              .anyRequest().authenticated();
        })
        .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(deviceAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(jwtAuthenticationFilter, DeviceAuthFilter.class)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .build();
  }

  @Bean
  public PasswordEncoder passwordEncoderProd() {
    return new BCryptPasswordEncoder();
  }
}
