package cn.disasterguard.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 现阶段投递边界：只有「项目测试成员」允许进入通知发送链路。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>fail-closed</b>：白名单为空时任何人都不放行。忘配名单的后果是「谁都发不出去」，
 *       而不是「谁都能发」，避免把配置遗漏变成对外误发。</li>
 *   <li>匹配用规范化后的<b>精确值</b>（去全部空白 + 转小写），不做通配符 / 域名后缀匹配，
 *       否则 {@code *@example.com} 一类写法会误放行整个域名。</li>
 *   <li>本类只是<b>准入闸门</b>，不代表具备真实投递能力：Sender 是否真发由各自网关决定，
 *       现阶段 6 个 Sender 均为演示实现（{@code mock=true}）。</li>
 *   <li>名单不落库、不硬编码个人信息，来自环境变量
 *       {@code NOTIFICATION_TEST_RECIPIENTS}（逗号 / 分号 / 换行分隔）。</li>
 * </ul>
 */
@Component
public class TestRecipientGuard {

  public static final String BLOCKED_STATUS = "BLOCKED";
  public static final String BLOCKED_REASON =
      "[未投递] 当前阶段仅允许发送给项目测试成员；该收件人不在白名单，未调用任何发送器。";

  private final Set<String> allowed;

  public TestRecipientGuard(
      @Value("${disasterguard.notification.test-recipients:}") String rawRecipients) {
    this.allowed = parse(rawRecipients);
  }

  private static Set<String> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    Set<String> parsed = new LinkedHashSet<>();
    for (String part : raw.split("[,;\\n]")) {
      String normalized = normalize(part);
      if (!normalized.isEmpty()) {
        parsed.add(normalized);
      }
    }
    return Collections.unmodifiableSet(parsed);
  }

  /** 去所有空白 + 转小写；手机号、邮箱、设备 Token、用户名都适用。 */
  public static String normalize(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
  }

  public boolean hasRecipients() {
    return !allowed.isEmpty();
  }

  public int size() {
    return allowed.size();
  }

  /**
   * 名单中的第一个成员；用于「没填 target 的模板测试」这类场景，
   * 保证默认目标也一定在白名单内。名单为空返回 null（调用方必须拒绝发送）。
   */
  public String firstRecipient() {
    return allowed.isEmpty() ? null : allowed.iterator().next();
  }

  /** 名单外（含空名单、空 target）一律返回 false。 */
  public boolean isAllowed(String target) {
    return !allowed.isEmpty() && allowed.contains(normalize(target));
  }

  /** 无收件人的纯探测（渠道连通性测试），不构成对外投递。 */
  public static boolean isProbeWithoutTarget(String target) {
    return normalize(target).isEmpty();
  }

  /** 日志脱敏：只保留首字符与末两字符，避免把手机号/邮箱写进日志。 */
  public static String mask(String target) {
    String value = target == null ? "" : target.trim();
    if (value.length() <= 3) {
      return "***";
    }
    return value.charAt(0) + "***" + value.substring(value.length() - 2);
  }
}
