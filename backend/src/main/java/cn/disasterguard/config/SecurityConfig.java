package cn.disasterguard.config;

import cn.disasterguard.common.security.DeviceAuthFilter;
import cn.disasterguard.common.security.JwtAuthenticationFilter;
import cn.disasterguard.common.security.RateLimitFilter;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
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
@Profile("!prod")
public class SecurityConfig {

  @Value("${disasterguard.device.auth-strict-mode:false}")
  private boolean deviceAuthStrictMode;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
      MonitoringDeviceRepository deviceRepository, RateLimitFilter rateLimitFilter) throws Exception {
    DeviceAuthFilter deviceAuthFilter = new DeviceAuthFilter(deviceRepository, deviceAuthStrictMode);
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            // logout 由 JwtAuthenticationFilter 的 shouldNotFilter 显式跳过（控制器自行从
            // Authorization 头取 token 验签并吊销），因此这里必须同步 permitAll，
            // 否则 anyRequest().authenticated() 会在进入控制器前直接返回 403，
            // 登出永远无法把 JWT 写入黑名单。控制器对无头/无效 token 是安全的空操作。
            .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html",
                "/api/system/health", "/api/auth/login", "/api/auth/logout").permitAll()
            // 注册申请链路：只开放「提交申请 + 自查进度」，不开放自助注册。
            // 账号真正创建发生在管理员审核通过后（RegisterService.approve），
            // 因此这里放行不会产生未授权建号。
            // 逐个方法声明而非整段 /api/auth/register/**，避免将来在注册前缀下
            // 新增写接口时被顺带放开（如 /api/auth/register/reset-password）。
            .requestMatchers(HttpMethod.GET, "/api/auth/captcha",
                "/api/auth/register/status").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/register",
                "/api/auth/register/send-code").permitAll()
            // WebSocket 握手由 JwtHandshakeInterceptor 在握手层鉴权，Spring Security 放行
            .requestMatchers("/ws").permitAll()
            // R5：debug-trigger 会无鉴权构造「红色预警」写库并推 WebSocket，
            // 前端 0 引用但外部可直接污染预警表。收回 permitAll，改为需登录 + 管理员权限
            // （权限判定见 WarningController#debugTrigger）。
            // 国家气象数据接入：公开资源（NSMC / NMC 不需要鉴权），CORS 由 CorsConfig 接管，限流由 RateLimitFilter 保护
            // R3 P1-6：仅 GET 放行。此前整个 /api/weather/** 对匿名开放，
            // 导致 POST /api/weather/products/refresh 可被匿名触发回源拉取外部资源
            // （无频控的上游放大 + SSRF 面）。该前缀下当前只有 GET 与这一个 POST。
            .requestMatchers(HttpMethod.GET, "/api/weather/**").permitAll()
            // 卫星覆盖数据：公开资源（CDSE / Sentinel-2 / Landsat 等），限流由 RateLimitFilter 保护
            // 该前缀下当前仅 GET /coverage，同步收紧为仅 GET，
            // 防止后续新增写接口时再次对匿名裸奔。
            .requestMatchers(HttpMethod.GET, "/api/sentinel/**").permitAll()
            // 设备数据上报：允许两种身份——设备（DeviceAuthFilter 注入 ROLE_DEVICE/device:report）
            // 与后台补录用户（JWT + devices:edit）。此前写死 hasRole("DEVICE")，
            // 导致带 devices:edit 的管理员在设备详情页手工补录时被 403。
            // 具体权限仍由 @PreAuthorize 精确判定，这里只要求「已认证」。
            .requestMatchers("/api/devices/{deviceCode}/data").authenticated()
            .anyRequest().authenticated())
        // 限流过滤器最先执行，保护公开端点
        .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
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
