package cn.disasterguard.auth.register.controller;

import cn.disasterguard.auth.register.dto.CaptchaView;
import cn.disasterguard.auth.register.dto.RegisterRequest;
import cn.disasterguard.auth.register.dto.RegisterStatusView;
import cn.disasterguard.auth.register.dto.SendCodeRequest;
import cn.disasterguard.auth.register.service.CaptchaService;
import cn.disasterguard.auth.register.service.RegisterService;
import cn.disasterguard.auth.register.service.VerificationCodeService;
import cn.disasterguard.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放注册申请接口（匿名可访问）。
 *
 * <p>注意：本系统<b>不提供自助注册</b>，此处只开放「提交申请 + 查询申请状态」，
 * 账号真正创建由管理员在后台审核通过后完成。
 */
@RestController
@RequestMapping("/api/auth")
public class RegisterController {

  private static final Logger log = LoggerFactory.getLogger(RegisterController.class);

  private static final int IP_MAX = 64;

  private final CaptchaService captchaService;
  private final VerificationCodeService verificationCodeService;
  private final RegisterService registerService;

  public RegisterController(CaptchaService captchaService,
                            VerificationCodeService verificationCodeService,
                            RegisterService registerService) {
    this.captchaService = captchaService;
    this.verificationCodeService = verificationCodeService;
    this.registerService = registerService;
  }

  /** 获取图形验证码。 */
  @GetMapping("/captcha")
  public ApiResponse<CaptchaView> captcha() {
    return ApiResponse.success(captchaService.generate());
  }

  /** 下发短信 / 邮箱验证码。 */
  @PostMapping("/register/send-code")
  public ApiResponse<Void> sendCode(@Valid @RequestBody SendCodeRequest request) {
    verificationCodeService.send(request.type(), request.target());
    return ApiResponse.success("验证码已发送", null);
  }

  /** 提交注册申请（落库待审核）。 */
  @PostMapping("/register")
  public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
    registerService.register(request, resolveIp(httpRequest), httpRequest.getHeader("User-Agent"));
    log.info("注册申请已受理 username={}", request.username());
    return ApiResponse.success("申请已提交，请等待管理员审核", null);
  }

  /** 查询某用户名的申请状态。 */
  @GetMapping("/register/status")
  public ApiResponse<RegisterStatusView> status(@RequestParam String username) {
    RegisterStatusView view = registerService.queryStatus(username);
    if (view == null) {
      return ApiResponse.success("未找到申请记录", null);
    }
    return ApiResponse.success(view);
  }

  /**
   * 提取客户端 IP：优先取 X-Forwarded-For 首个非空段（经过 Nginx 代理时才有），
   * 退化为 request.getRemoteAddr()。
   */
  private String resolveIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      for (String segment : forwarded.split(",")) {
        String ip = segment.trim();
        if (!ip.isEmpty()) {
          return truncate(ip);
        }
      }
    }
    return truncate(request.getRemoteAddr());
  }

  private String truncate(String ip) {
    if (ip == null) {
      return null;
    }
    return ip.length() <= IP_MAX ? ip : ip.substring(0, IP_MAX);
  }
}
