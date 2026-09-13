package cn.geosentry.config;

import cn.geosentry.common.security.DeviceAuthFilter;
import cn.geosentry.common.security.JwtAuthenticationFilter;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  @Value("${geosentry.device.auth-strict-mode:false}")
  private boolean deviceAuthStrictMode;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
      MonitoringDeviceRepository deviceRepository) throws Exception {
    DeviceAuthFilter deviceAuthFilter = new DeviceAuthFilter(deviceRepository, deviceAuthStrictMode);
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html",
                "/api/system/health", "/api/auth/login").permitAll()
            // WebSocket 握手由 JwtHandshakeInterceptor 在握手层鉴权，Spring Security 放行
            .requestMatchers("/ws").permitAll()
            // 国家气象数据接入：公开资源（NSMC / NMC 不需要鉴权），CORS 由 CorsConfig 接管
            .requestMatchers("/api/weather/**").permitAll()
            .requestMatchers("/api/devices/{deviceCode}/data").hasRole("DEVICE")
            .anyRequest().authenticated())
        // 显式顺序：DeviceAuthFilter 先于 JwtAuthenticationFilter
        // 否则 JWT 过滤器会拦截 /api/devices/**/data 请求返回 401
        .addFilterBefore(deviceAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(jwtAuthenticationFilter, DeviceAuthFilter.class)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  // DbUserDetailsService 由 @Service 自动注册，直接复用，无需在 SecurityConfig 内手动声明。
}
