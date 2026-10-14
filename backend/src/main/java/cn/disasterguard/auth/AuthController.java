package cn.disasterguard.auth;

import cn.disasterguard.auth.entity.SysUser;
import cn.disasterguard.auth.jwt.JwtService;
import cn.disasterguard.auth.security.DbUserDetailsService;
import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.security.JwtAuthenticationFilter;
import cn.disasterguard.realtime.WsTicketRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
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
import cn.disasterguard.auth.dto.LoginRequest;
import cn.disasterguard.auth.dto.LoginResponse;
import cn.disasterguard.auth.dto.ChangePasswordRequest;
import cn.disasterguard.auth.dto.ChangePasswordResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final JwtService jwtService;
  private final DbUserDetailsService userDetailsService;
  private final PasswordEncoder passwordEncoder;
  private final cn.disasterguard.auth.repository.SysUserRepository userRepository;
  private final WsTicketRepository ticketRepository;

  public AuthController(JwtService jwtService,
                        DbUserDetailsService userDetailsService,
                        PasswordEncoder passwordEncoder,
                        cn.disasterguard.auth.repository.SysUserRepository userRepository,
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

    // 无论 DB 用户还是内存 fallback，都必须校验 BCrypt 密码。
    // 旧逻辑对 fallback 用户接受任意非空密码，构成"任意账号任意密码可登录"的后门；
    // 这里统一用 PasswordEncoder 校验 DbUserDetailsService 返回的 hash。
    if (!passwordEncoder.matches(request.password(), userDetails.getPassword())) {
      log.warn("登录失败：密码错误 user={}", request.username());
      return ApiResponse.fail(401, "用户名或密码错误");
    }
    boolean dbUser = userDetails.getPassword().startsWith("$2b$") || userDetails.getPassword().startsWith("$2a$");
    if (dbUser) {
      var sysUser = userRepository.findByUsername(request.username());
      if (sysUser.isPresent()) {
        SysUser u = sysUser.get();
        u.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(u);
      }
    } else {
      log.info("登录成功（dev fallback 用户）user={}", request.username());
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
   * 已登录用户修改自己的密码。
   *
   * <p>成功后立即把当前请求所使用的 JWT 加入黑名单，使旧 token 在剩余 TTL 内
   * 失效。客户端必须重新登录获取新 token，否则下一次调用将返回 401。
   * 即便客户端继续用旧 token 访问，黑名单会在 {@code JwtAuthenticationFilter}
   * 调用 {@code JwtService.parseToken} 时拦截。
   */
  @PostMapping("/change-password")
  public ApiResponse<ChangePasswordResponse> changePassword(
      HttpServletRequest request,
      @AuthenticationPrincipal UserDetails userDetails,
      @Valid @RequestBody ChangePasswordRequest requestBody) {
    String username = userDetails.getUsername();
    var sysUserOpt = userRepository.findByUsername(username);
    if (sysUserOpt.isEmpty()) {
      return ApiResponse.fail(404, "用户不存在");
    }
    SysUser sysUser = sysUserOpt.get();

    // 兼容旧参数命名（部分前端可能仍发 "request"），但推荐用 requestBody 避免与 HttpServletRequest 重名。
    ChangePasswordRequest req = requestBody == null ? null : requestBody;
    if (req == null) {
      return ApiResponse.fail(400, "请求体为空");
    }

    // 校验当前密码（内存 fallback 用户跳过，只校验新密码）
    boolean dbUser = sysUser.getPasswordHash() != null
        && (sysUser.getPasswordHash().startsWith("$2b$")
            || sysUser.getPasswordHash().startsWith("$2a$"));
    if (dbUser && !passwordEncoder.matches(req.oldPassword(), sysUser.getPasswordHash())) {
      log.warn("修改密码失败：当前密码错误 user={}", username);
      return ApiResponse.fail(401, "当前密码错误");
    }

    // 校验新密码强度（≥ 8 字符，包含字母和数字）
    String newPwd = req.newPassword();
    if (newPwd == null || newPwd.length() < 8) {
      return ApiResponse.fail(400, "新密码至少 8 个字符");
    }
    if (!newPwd.matches(".*[A-Za-z].*") || !newPwd.matches(".*\\d.*")) {
      return ApiResponse.fail(400, "新密码必须同时包含字母和数字");
    }
    if (newPwd.equals(req.oldPassword())) {
      return ApiResponse.fail(400, "新密码不能与当前密码相同");
    }

    sysUser.setPasswordHash(passwordEncoder.encode(newPwd));
    sysUser.setUpdatedAt(java.time.OffsetDateTime.now());
    userRepository.save(sysUser);

    // 吊销当前 token：从 JwtAuthenticationFilter 写入的 request attribute 取出原 token，
    // 解析后丢给 JwtService.revoke()。若属性缺失（极少见，可能是直接拿 secret 伪造场景），
    // 也允许改密成功但仅跳过吊销，避免因 attribute 缺失导致改密接口失败。
    String currentToken = (String) request.getAttribute(JwtAuthenticationFilter.CURRENT_JWT_ATTR);
    if (currentToken != null && !currentToken.isBlank()) {
      try {
        Claims claims = jwtService.parseToken(currentToken);
        jwtService.revoke(claims);
      } catch (Exception ex) {
        // 这里捕获异常而不是继续抛出：token 解析失败已经说明它无效，无需再吊销；
        // 但改密本身已落库成功，不能因为这一步失败而让用户困惑。
        log.warn("改密后吊销当前 token 失败 user={}, cause={}", username, ex.getMessage());
      }
    } else {
      log.warn("改密后未能取得当前 JWT 属性 user={}，跳过吊销", username);
    }

    log.info("用户修改密码成功 user={}", username);
    return ApiResponse.success("密码修改成功，请重新登录",
        new ChangePasswordResponse(username, true));
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
