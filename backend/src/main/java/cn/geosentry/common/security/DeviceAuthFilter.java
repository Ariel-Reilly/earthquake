package cn.geosentry.common.security;

import cn.geosentry.device.entity.MonitoringDevice;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 设备数据上报鉴权过滤器。
 *
 * <p>处理 {@code POST /api/devices/{deviceCode}/data} 路径，从请求头 {@code X-Device-Key}
 * 读取设备密钥，校验通过后将 Authentication 注入 SecurityContext（角色 DEVICE）。
 *
 * <p>两种鉴权模式：
 * <ul>
 *   <li>设备已登记 api_key：校验 header 与数据库 api_key 匹配</li>
 *   <li>设备未登记 api_key：
 *     <ul>
 *       <li>strict-mode=false（默认，迁移期）：允许但 WARN 提示</li>
 *       <li>strict-mode=true（生产环境）：拒绝，返回 403</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>EMQX MQTT 链路不在此过滤器覆盖范围内，由 MQTT broker 本身的鉴权配置处理。
 * broker 侧未配置用户名/密码时，MQTT 链路也无法通过认证。
 */
public class DeviceAuthFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(DeviceAuthFilter.class);
  private static final String DEVICE_PATH_PREFIX = "/api/devices/";
  private static final String DEVICE_DATA_SUFFIX = "/data";

  private final MonitoringDeviceRepository deviceRepository;
  private final boolean strictMode;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public DeviceAuthFilter(MonitoringDeviceRepository deviceRepository,
                          @Value("${geosentry.device.auth-strict-mode:false}") boolean strictMode) {
    this.deviceRepository = deviceRepository;
    this.strictMode = strictMode;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !"POST".equalsIgnoreCase(request.getMethod())
        || !path.startsWith(DEVICE_PATH_PREFIX)
        || !path.endsWith(DEVICE_DATA_SUFFIX);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String path = request.getRequestURI();
    String deviceCode = path.substring(DEVICE_PATH_PREFIX.length(),
        path.length() - DEVICE_DATA_SUFFIX.length());

    String providedKey = request.getHeader("X-Device-Key");
    if (providedKey == null || providedKey.isBlank()) {
      writeError(response, 401, "缺少 X-Device-Key 请求头");
      return;
    }

    MonitoringDevice device = deviceRepository.findByDeviceCode(deviceCode);
    if (device == null) {
      writeError(response, 404, "设备不存在: " + deviceCode);
      return;
    }

    String expectedKey = device.getApiKey();
    if (expectedKey == null || expectedKey.isBlank()) {
      if (strictMode) {
        log.warn("设备 {} 未登记 api_key，严格模式拒绝上报", deviceCode);
        writeError(response, 403, "设备未登记鉴权密钥，请先在设备管理中设置 api_key");
        return;
      }
      log.warn("设备 {} 未登记 api_key，当前允许数据上报但建议立即登记密钥（strict-mode=false）", deviceCode);
    } else if (!expectedKey.equals(providedKey)) {
      log.warn("设备 {} 上报鉴权失败：X-Device-Key 与 api_key 不匹配", deviceCode);
      writeError(response, 401, "设备密钥无效");
      return;
    }

    // 注入 DEVICE 角色 Authentication
    List<SimpleGrantedAuthority> authorities = List.of(
        new SimpleGrantedAuthority("ROLE_DEVICE"),
        new SimpleGrantedAuthority("device:report")
    );
    UserDetails principal = User.withUsername(deviceCode).password("").authorities(authorities).build();
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(principal, null, authorities);
    SecurityContextHolder.getContext().setAuthentication(auth);

    filterChain.doFilter(request, response);
  }

  private void writeError(HttpServletResponse response, int code, String message) throws IOException {
    response.setStatus(code);
    response.setContentType("application/json;charset=UTF-8");
    Map<String, Object> body = new HashMap<>();
    body.put("code", code);
    body.put("message", message);
    body.put("success", false);
    response.getWriter().write(objectMapper.writeValueAsString(body));
  }
}
