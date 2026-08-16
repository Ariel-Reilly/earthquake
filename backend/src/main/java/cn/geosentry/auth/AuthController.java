package cn.geosentry.auth;

import cn.geosentry.auth.jwt.JwtService;
import cn.geosentry.auth.security.DbUserDetailsService;
import cn.geosentry.common.api.ApiResponse;
import cn.geosentry.realtime.WsTicketRepository;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final JwtService jwtService;
  private final DbUserDetailsService userDetailsService;
  private final PasswordEncoder passwordEncoder;
  private final cn.geosentry.auth.repository.SysUserRepository userRepository;
  private final WsTicketRepository ticketRepository;

  public AuthController(JwtService jwtService,
                        DbUserDetailsService userDetailsService,
                        PasswordEncoder passwordEncoder,
                        cn.geosentry.auth.repository.SysUserRepository userRepository,
                        WsTicketRepository ticketRepository) {
    this.jwtService = jwtService;
    this.userDetailsService = userDetailsService;
    this.passwordEncoder = passwordEncoder;
    this.userRepository = userRepository;
    this.ticketRepository = ticketRepository;
  }

  @PostMapping("/login")
  public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
    var userDetails = userDetailsService.loadUserByUsername(request.username());

    // DB 用户：校验 BCrypt 密码
    boolean dbUser = userDetails.getPassword().startsWith("$2b$") || userDetails.getPassword().startsWith("$2a$");
    if (dbUser) {
      if (!passwordEncoder.matches(request.password(), userDetails.getPassword())) {
        log.warn("登录失败：密码错误 user={}", request.username());
        return ApiResponse.fail(401, "用户名或密码错误");
      }
      // 更新最后登录时间
      var sysUser = userRepository.findByUsername(request.username());
      if (sysUser != null) {
        sysUser.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(sysUser);
      }
    } else {
      // 内存 fallback：仅校验账号存在即可（密码接受任意非空值，便于演示）
      log.info("登录成功（内存 fallback）user={}", request.username());
    }

    // 从 authorities 提取角色
    String role = "USER";
    for (var auth : userDetails.getAuthorities()) {
      if (auth.getAuthority().startsWith("ROLE_")) {
        role = auth.getAuthority().substring(5).toLowerCase();
        break;
      }
    }

    String token = jwtService.generateToken(userDetails.getUsername(), role);
    LoginResponse response = new LoginResponse(
        token,
        new LoginResponse.UserProfile(request.username(), role, userDetails.getUsername())
    );
    log.info("登录成功 user={}, role={}, dbUser={}", request.username(), role, dbUser);
    return ApiResponse.success("登录成功", response);
  }

  @PostMapping("/logout")
  public ApiResponse<Void> logout() {
    return ApiResponse.success("退出成功", null);
  }

  /**
   * 为已认证用户签发 WebSocket 一次性 ticket。
   *
   * <p>建议客户端优先使用 ticket 连接 WebSocket（{@code ws://host/ws?ticket=xxx}），
   * 避免把长期 JWT 放入反向代理/服务器日志。
   * ticket 有效期 60s，一次性使用，握手后失效。
   */
  @GetMapping("/ws-ticket")
  public ApiResponse<Map<String, Object>> wsTicket(@AuthenticationPrincipal UserDetails userDetails) {
    String username = userDetails.getUsername();
    String role = "USER";
    for (var auth : userDetails.getAuthorities()) {
      if (auth.getAuthority().startsWith("ROLE_")) {
        role = auth.getAuthority().substring(5).toLowerCase();
        break;
      }
    }
    String ticket = ticketRepository.issue(username, role);
    log.info("签发 WS ticket user={}", username);
    return ApiResponse.success("ok", Map.of(
        "ticket", ticket,
        "username", username,
        "role", role,
        "expires_in", 60
    ));
  }
}
