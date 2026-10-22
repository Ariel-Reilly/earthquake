package cn.disasterguard.auth.register.service;

import cn.disasterguard.auth.register.dto.CaptchaView;
import cn.disasterguard.common.exception.AppException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 图形验证码服务。
 *
 * <p>验证码内容存 Redis（{@code captcha:{id}}，TTL 5 分钟），校验时一次性消费
 * （GET + DEL 原子语义由 {@code getAndDelete} 提供），防止暴力重放。
 *
 * <p>图形使用 JDK 自带 AWT 绘制，不引入任何第三方依赖。
 */
@Service
public class CaptchaService {

  private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

  private static final String KEY_PREFIX = "captcha:";
  private static final Duration TTL = Duration.ofMinutes(5);
  private static final int WIDTH = 120;
  private static final int HEIGHT = 40;
  private static final int MIN_LENGTH = 4;
  private static final int MAX_LENGTH = 6;
  /** 去掉容易混淆的字符（0/O、1/I/L）。 */
  private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

  private final StringRedisTemplate redis;
  private final SecureRandom random = new SecureRandom();

  public CaptchaService(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * 生成一组图形验证码。
   *
   * @return 验证码 ID 与 Base64 图片（不含 data URI 前缀）
   */
  public CaptchaView generate() {
    String captchaId = UUID.randomUUID().toString();
    String code = randomCode();
    try {
      redis.opsForValue().set(KEY_PREFIX + captchaId, code, TTL);
    } catch (DataAccessException ex) {
      log.error("图形验证码写入 Redis 失败: {}", ex.getMessage());
      throw new AppException(50302, "验证码服务暂不可用，请稍后重试");
    }
    return new CaptchaView(captchaId, render(code));
  }

  /**
   * 校验图形验证码，校验通过后立即失效（一次性消费）。
   *
   * @param captchaId 验证码 ID
   * @param code     用户输入
   * @return 校验通过返回 true
   */
  public boolean verify(String captchaId, String code) {
    if (captchaId == null || captchaId.isBlank() || code == null || code.isBlank()) {
      return false;
    }
    String expected;
    try {
      expected = redis.opsForValue().getAndDelete(KEY_PREFIX + captchaId);
    } catch (DataAccessException ex) {
      log.error("图形验证码读取 Redis 失败: {}", ex.getMessage());
      throw new AppException(50302, "验证码服务暂不可用，请稍后重试");
    }
    if (expected == null) {
      return false;
    }
    return expected.equalsIgnoreCase(code.trim());
  }

  private String randomCode() {
    int length = MIN_LENGTH + random.nextInt(MAX_LENGTH - MIN_LENGTH + 1);
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    }
    return sb.toString();
  }

  /**
   * 绘制验证码图片。绘制失败时（极端的无头环境缺字体）返回空串，
   * 此时仍可依赖服务端校验，只是前端看不到图形 —— 记 WARN 便于排查。
   */
  private String render(String code) {
    try {
      BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = image.createGraphics();
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setColor(new Color(245, 247, 250));
      g.fillRect(0, 0, WIDTH, HEIGHT);

      // 干扰线
      for (int i = 0; i < 5; i++) {
        g.setColor(new Color(150 + random.nextInt(80), 150 + random.nextInt(80), 150 + random.nextInt(80)));
        g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT), random.nextInt(WIDTH), random.nextInt(HEIGHT));
      }

      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
      int x = 12;
      for (int i = 0; i < code.length(); i++) {
        g.setColor(new Color(random.nextInt(120), random.nextInt(120), random.nextInt(120)));
        g.drawString(String.valueOf(code.charAt(i)), x, 30 + random.nextInt(4));
        x += 18;
      }
      g.dispose();

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return Base64.getEncoder().encodeToString(out.toByteArray());
    } catch (Exception ex) {
      log.warn("图形验证码绘制失败: {}", ex.getMessage());
      return "";
    }
  }
}
