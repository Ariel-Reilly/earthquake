package cn.disasterguard.auth.register.service;

import cn.disasterguard.auth.register.config.SmsProperties;
import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.notification.TestRecipientGuard;
import cn.disasterguard.notification.channel.EmailSender;
import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.google.gson.Gson;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * 短信 / 邮箱验证码服务。
 *
 * <p>限流（Redis 实现）：
 * <ul>
 *   <li>同一 target 60 秒内只能下发 1 次；</li>
 *   <li>同一 target 每天最多下发 10 次（按自然日滚动过期）。</li>
 * </ul>
 *
 * <p>验证码存 Redis：手机号 {@code sms:{phone}}、邮箱 {@code email:{email}}，TTL 5 分钟，
 * 校验时一次性消费。
 *
 * <p>短信渠道发送策略（{@code disasterguard.sms.enabled}）：
 * <ul>
 *   <li>开启（true）：调用阿里云短信 SDK 真实发送；</li>
 *   <li>关闭且为 prod：直接抛 {@code AppException(50301, "验证码服务未配置")}；</li>
 *   <li>关闭且非 prod：降级为日志输出，验证码打印在 INFO 日志中，便于本地联调。</li>
 * </ul>
 *
 * <p>邮箱渠道发送策略（{@code disasterguard.register.email-enabled}）：
 * <ul>
 *   <li>开启：调用项目的 EmailSender（当前为 Mock 实现）投递；</li>
 *   <li>关闭且为 prod：抛 {@code AppException(50301)}；</li>
 *   <li>关闭且非 prod：降级为日志输出。</li>
 * </ul>
 */
@Service
public class VerificationCodeService {

  private static final Logger log = LoggerFactory.getLogger(VerificationCodeService.class);

  private static final String SMS_PREFIX = "sms:";
  private static final String EMAIL_PREFIX = "email:";
  private static final String LIMIT_MINUTE_PREFIX = "register:code:limit:minute:";
  private static final String LIMIT_DAY_PREFIX = "register:code:limit:day:";

  private static final Duration CODE_TTL = Duration.ofMinutes(5);
  private static final int MINUTE_LIMIT = 1;
  private static final int DAY_LIMIT = 10;

  private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

  private final StringRedisTemplate redis;
  private final EmailSender emailSender;
  private final boolean emailEnabled;
  private final boolean prodProfile;
  private final SecureRandom random = new SecureRandom();
  private final Gson gson = new Gson();

  // 阿里云号码认证服务配置（可选注入，enabled=false 时为 null）
  private final SmsProperties smsProperties;
  private final Client aliyunDypnsClient;
  private final TestRecipientGuard recipientGuard;

  public VerificationCodeService(
      StringRedisTemplate redis,
      EmailSender emailSender,
      Environment environment,
      @Value("${disasterguard.register.email-enabled:false}") boolean emailEnabled,
      SmsProperties smsProperties,
      // 参数级 @Autowired(required=false) 对构造器注入并不生效（Spring 把构造器参数
      // 视为必需），关闭 ALIYUN_SMS_ENABLED 时 Client bean 不存在会直接启动失败。
      // @Nullable 才是让该参数真正可选的正确写法。
      @Autowired(required = false) @Nullable Client aliyunDypnsClient,
      TestRecipientGuard recipientGuard) {
    this.redis = redis;
    this.emailSender = emailSender;
    this.emailEnabled = emailEnabled;
    this.prodProfile = java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod");
    this.smsProperties = smsProperties;
    this.aliyunDypnsClient = aliyunDypnsClient;
    this.recipientGuard = recipientGuard;
  }

  /**
   * 下发验证码。
   *
   * @param type   phone / email
   * @param target 手机号或邮箱
   */
  public void send(String type, String target) {
    boolean phone = "phone".equalsIgnoreCase(type);
    boolean email = "email".equalsIgnoreCase(type);
    if (!phone && !email) {
      throw new AppException(40010, "验证码类型仅支持 phone / email");
    }
    String value = target == null ? "" : target.trim();
    if (phone && !PHONE_PATTERN.matcher(value).matches()) {
      throw new AppException(40010, "手机号格式不正确");
    }
    if (email && !EMAIL_PATTERN.matcher(value).matches()) {
      throw new AppException(40010, "邮箱格式不正确");
    }

    // 渠道未接入时先失败再消费频控：否则 prod 下每个请求都会白白扣掉
    // 「60 秒 1 次 / 每天 10 次」的额度，用户却永远收不到验证码。
    if (!isChannelReady(phone)) {
      throw new AppException(50301, "验证码服务未配置");
    }

    // 现阶段投递边界：只有确实具备真实投递能力的分支才需要闸门。
    // 短信需 enabled + Client bean 存在 + 凭证不是 CHANGE_ME 占位；邮箱 Sender 目前只打日志，
    // 不产生对外投递（isRealEmailDelivery() 恒为 false）。默认 ALIYUN_SMS_ENABLED=true
    // 但凭证是占位值，不能据此把注册验证码全拦掉。
    // 判定收敛到一处，将来邮件接入真实 SMTP 时只需改 isRealEmailDelivery() 即可自动纳管。
    // 检查放在频控之前，避免被拦请求白扣额度。
    boolean realDelivery = phone ? isRealSmsDelivery() : isRealEmailDelivery();
    if (realDelivery && !recipientGuard.isAllowed(value)) {
      log.warn("[投递边界] 验证码真实下发被拦截: type={}, target={}",
          type, TestRecipientGuard.mask(value));
      throw new AppException(40301,
          "当前阶段仅允许向项目测试成员下发验证码，该收件人不在白名单");
    }

    enforceRateLimit(value);

    String code = String.format("%06d", random.nextInt(1_000_000));
    String key = (phone ? SMS_PREFIX : EMAIL_PREFIX) + value;
    try {
      redis.opsForValue().set(key, code, CODE_TTL);
    } catch (DataAccessException ex) {
      log.error("验证码写入 Redis 失败 key={}: {}", key, ex.getMessage());
      throw new AppException(50302, "验证码服务暂不可用，请稍后重试");
    }

    deliver(phone, value, code);
  }

  /**
   * 校验验证码，校验通过后立即失效（一次性消费）。
   *
   * @param type   phone / email
   * @param target 手机号或邮箱
   * @param code   用户输入的验证码
   * @return 校验通过返回 true
   */
  public boolean verify(String type, String target, String code) {
    if (target == null || target.isBlank() || code == null || code.isBlank()) {
      return false;
    }
    boolean phone = "phone".equalsIgnoreCase(type);
    String key = (phone ? SMS_PREFIX : EMAIL_PREFIX) + target.trim();
    String expected;
    try {
      expected = redis.opsForValue().getAndDelete(key);
    } catch (DataAccessException ex) {
      log.error("验证码读取 Redis 失败 key={}: {}", key, ex.getMessage());
      throw new AppException(50302, "验证码服务暂不可用，请稍后重试");
    }
    return expected != null && expected.equals(code.trim());
  }

  /**
   * 频控：60 秒 1 次 + 每天 10 次。
   */
  private void enforceRateLimit(String target) {
    try {
      String minuteKey = LIMIT_MINUTE_PREFIX + target;
      Boolean first = redis.opsForValue().setIfAbsent(minuteKey, "1", Duration.ofSeconds(60));
      if (Boolean.FALSE.equals(first)) {
        throw new AppException(40011, "验证码发送过于频繁，请 60 秒后再试");
      }

      String dayKey = LIMIT_DAY_PREFIX + LocalDate.now() + ":" + target;
      Long count = redis.opsForValue().increment(dayKey);
      if (count != null && count == 1L) {
        // 当日首次发送，设置到当天结束过期
        LocalTime now = LocalTime.now();
        long seconds = java.time.Duration.between(now, LocalTime.MAX).getSeconds() + 60;
        redis.expire(dayKey, Duration.ofSeconds(seconds));
      }
      if (count != null && count > DAY_LIMIT) {
        throw new AppException(40012, "今日验证码发送次数已达上限（10 次），请明天再试");
      }
    } catch (DataAccessException ex) {
      log.error("验证码频控访问 Redis 失败: {}", ex.getMessage());
      throw new AppException(50302, "验证码服务暂不可用，请稍后重试");
    }
  }

  /** 短信是否真的会离开本机：开关打开 + Client 已构造 + 凭证不是占位值。 */
  private boolean isRealSmsDelivery() {
    String ak = smsProperties.getAccessKeyId();
    return smsProperties.isEnabled()
        && aliyunDypnsClient != null
        && ak != null && !ak.isBlank()
        && !"CHANGE_ME".equalsIgnoreCase(ak.trim());
  }

  /**
   * 邮件是否真的会离开本机。
   *
   * <p>当前 {@code EmailSender} 是纯日志 Mock（无 JavaMail / SMTP），所以恒为 false。
   * <b>接入真实邮件网关时必须把本方法改为按配置判定并返回 true</b>，否则白名单闸门不会生效。
   * 作为兜底，{@code deliverEmail} 会在 Sender 返回非 mock 结果时 fail-closed 抛错，
   * 避免「网关已接入、开关没开」被静默绕过。
   */
  private boolean isRealEmailDelivery() {
    return false;
  }

  /**
   * 渠道是否可用。
   *
   * <p>prod 下渠道开关为 false 等同于「网关未接入」，此时必须直接失败；
   * 非 prod 允许走日志降级，方便本地联调。
   *
   * @param phone true 表示短信渠道，false 表示邮件渠道
   * @return 渠道可用返回 true
   */
  private boolean isChannelReady(boolean phone) {
    // 短信：开关开着但凭证仍是 CHANGE_ME 时，SDK 根本发不出去。若沿用「开关即就绪」，
    // prod 下会写 Redis、返回成功、deliverSms 静默降级到日志 —— 用户永远收不到验证码，
    // 而接口却报成功。只有真的能外发才算 ready；非 prod 保留日志降级方便本地联调。
    if (phone) {
      return isRealSmsDelivery() || !prodProfile;
    }
    return emailEnabled || !prodProfile;
  }

  /**
   * 渠道投递。
   *
   * <p>短信渠道：
   * <ul>
   *   <li>{@code smsProperties.enabled=true}：调用阿里云短信 SDK 真实发送；</li>
   *   <li>{@code enabled=false + prod}：已在 send() 入口拦截，这里不会到达；</li>
   *   <li>{@code enabled=false + 非 prod}：日志输出降级。</li>
   * </ul>
   *
   * <p>邮箱渠道：
   * <ul>
   *   <li>{@code emailEnabled=true}：调用 EmailSender（Mock 实现）；</li>
   *   <li>{@code enabled=false + 非 prod}：日志输出降级。</li>
   * </ul>
   */
  private void deliver(boolean phone, String target, String code) {
    if (phone) {
      deliverSms(target, code);
    } else {
      deliverEmail(target, code);
    }
  }

  /**
   * 短信投递（阿里云号码认证服务真实发送或日志降级）。
   *
   * <p>关键点：通过 TemplateParam 传递模板变量（${code}、${min} 等），确保阿里云短信网关
   * 正确替换。业务端生成的验证码必须包含在模板参数中。
   */
  private void deliverSms(String phone, String code) {
    if (!isRealSmsDelivery()) {
      // 网关未接入（开关关闭 / 无 Client bean / 凭证仍是 CHANGE_ME）时走日志降级，
      // 不要拿占位凭证去调 SDK 再抛 50301。验证码明文只在非 prod 的调试日志里出现。
      if (prodProfile) {
        log.info("[验证码-本地降级] channel=SMS phone={}（prod 不输出验证码）", phone);
      } else {
        log.info("[验证码-本地降级] channel=SMS phone={} code={}", phone, code);
      }
      return;
    }

    try {
      // 构建模板参数：包含验证码和有效时间
      Map<String, String> templateParam = new HashMap<>();
      templateParam.put("code", code);
      templateParam.put("min", "5");  // 验证码有效时间：5 分钟

      // 构建阿里云号码认证服务请求
      SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
          .setPhoneNumber(phone)
          .setSignName(smsProperties.getSignName())
          .setTemplateCode(smsProperties.getTemplateCode())
          .setTemplateParam(gson.toJson(templateParam));  // 传递 JSON 格式的模板参数

      // 调用阿里云 SDK 发送短信验证码
      SendSmsVerifyCodeResponse response = aliyunDypnsClient.sendSmsVerifyCode(request);

      // 检查发送结果
      if (response == null || response.getBody() == null) {
        log.error("阿里云号码认证服务发送失败：响应为空 phone={}", phone);
        throw new AppException(50301, "验证码发送失败，请稍后重试");
      }

      String responseCode = response.getBody().getCode();
      if (!"OK".equals(responseCode)) {
        String errorMsg = response.getBody().getMessage();
        String requestId = response.getBody().getRequestId();
        log.warn(
            "阿里云号码认证服务发送失败 phone={} code={} msg={} requestId={}",
            phone, responseCode, errorMsg, requestId);
        throw new AppException(50301, "验证码发送失败：" + errorMsg);
      }

      log.info("短信验证码发送成功 phone={}", phone);

      // 非 prod 环境日志输出验证码（便于调试）
      if (!prodProfile) {
        log.info("[验证码-阿里云] phone={} code={}", phone, code);
      }
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      log.error("阿里云号码认证服务异常 phone={}: {}", phone, e.getMessage(), e);
      throw new AppException(50301, "验证码发送失败，请稍后重试");
    }
  }

  /**
   * 邮箱投递（EmailSender Mock 实现或日志降级）。
   */
  private void deliverEmail(String email, String code) {
    if (!emailEnabled) {
      // prod 的「未接入」已在 send() 入口拦截，这里只可能是非 prod 的降级分支
      if (prodProfile) {
        log.info("[验证码-本地降级] channel=EMAIL email={}（prod 不输出验证码）", email);
      } else {
        log.info("[验证码-本地降级] channel=EMAIL email={} code={}", email, code);
      }
      return;
    }

    String title = "地脉智巡注册验证码";
    String content = "【地脉智巡】您的注册验证码为 " + code + "，5 分钟内有效。";
    var result = emailSender.send(email, title, content, java.util.Map.of("code", code));
    // fail-closed：一旦 Sender 不再返回 mock（说明真实 SMTP 已接入），而 isRealEmailDelivery()
    // 还没同步声明，就必须立刻暴露配置遗漏，不能让邮件悄悄绕过白名单闸门。
    if (!result.mock() && !isRealEmailDelivery()) {
      log.error("[投递边界] 邮件发送器返回真实投递结果但未声明真实投递能力，"
          + "请先开启白名单闸门再接入 SMTP: email={}", email);
      throw new AppException(50301, "邮件投递能力未声明，已阻止继续发送");
    }
    if (!result.success()) {
      log.warn("验证码邮件发送失败 email={} reason={}", email, result.errorMsg());
      throw new AppException(50301, "验证码发送失败，请稍后重试");
    }
    if (!prodProfile) {
      log.info("[验证码-本地] channel=EMAIL email={} code={}", email, code);
    }
  }
}
