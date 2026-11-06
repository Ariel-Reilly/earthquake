package cn.disasterguard.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 通知发送结果的契约测试：演示发送与真实发送必须可区分。
 *
 * <p>6 个渠道 Sender 目前都是「打日志 + 返回成功」的占位实现。改动前它们统一调用
 * {@code SendResult.success(...)}，看板会把演示回执当成真实送达。现在改为
 * {@code mockSuccess(...)}，本测试锁定该契约，防止后续有人改回静默成功。
 *
 * <p>纯单元测试，不加载 Spring 上下文，不需要 PostgreSQL / Redis / MinIO。
 */
class NotificationSenderTest {

  @Test
  void mockSuccess_isAcceptedButExplicitlyMarkedAsMock() {
    var result = NotificationSender.SendResult.mockSuccess("msg-1", "邮件网关未接入");

    assertThat(result.success()).isTrue();
    assertThat(result.messageId()).isEqualTo("msg-1");
    assertThat(result.mock()).isTrue();
    assertThat(result.mockReason()).contains("邮件网关未接入");
    assertThat(result.errorMsg()).isNull();
  }

  @Test
  void realSuccess_isNotMarkedAsMock() {
    var result = NotificationSender.SendResult.success("msg-2");

    assertThat(result.success()).isTrue();
    assertThat(result.mock()).isFalse();
    assertThat(result.mockReason()).isNull();
  }

  @Test
  void failure_isNotMarkedAsMock() {
    var result = NotificationSender.SendResult.failure("渠道已禁用");

    assertThat(result.success()).isFalse();
    assertThat(result.mock()).isFalse();
    assertThat(result.errorMsg()).isEqualTo("渠道已禁用");
    assertThat(result.messageId()).isNull();
  }
}
