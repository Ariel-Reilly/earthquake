package cn.disasterguard.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.disasterguard.auth.register.config.SmsProperties;
import cn.disasterguard.auth.register.service.VerificationCodeService;
import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.notification.channel.EmailSender;
import cn.disasterguard.notification.channel.NotificationSender;
import cn.disasterguard.notification.dto.NotificationRequestDto;
import cn.disasterguard.notification.dto.SendResultDto;
import cn.disasterguard.notification.dto.TestSendRequest;
import cn.disasterguard.notification.entity.NotificationRecord;
import cn.disasterguard.notification.entity.NotificationReceipt;
import cn.disasterguard.notification.repository.NotificationRecordRepository;
import cn.disasterguard.notification.repository.NotificationReceiptRepository;
import com.aliyun.dypnsapi20170525.Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 「现阶段只投递给项目测试成员」的契约测试。
 *
 * <p>锁定三件事：① 空名单 fail-closed；② 名单外的收件人在三条发送链路上都被拦截，
 * 且<b>不会调用任何 Sender</b>；③ 名单内的演示发送不会伪装成真实送达。
 *
 * <p>纯单元测试，不加载 Spring 上下文，不需要 PostgreSQL / Redis / MinIO。
 */
class TestRecipientGuardTest {

  private static TestSendRequest req(String channel, String target) {
    return new TestSendRequest(channel, target, "标题", "正文", Map.of());
  }

  @Test
  void emptyAllowlist_blocksEveryone() {
    var guard = new TestRecipientGuard("");

    assertThat(guard.hasRecipients()).isFalse();
    assertThat(guard.isAllowed("13800000000")).isFalse();
    assertThat(guard.isAllowed("tester@example.com")).isFalse();
    assertThat(guard.isAllowed(null)).isFalse();
  }

  @Test
  void allowlist_usesExactNormalizedMatch_notDomainWildcard() {
    var guard = new TestRecipientGuard("13800000000, Tester@Example.com ; device-token\n");

    assertThat(guard.size()).isEqualTo(3);
    assertThat(guard.isAllowed("138 0000 0000")).isTrue();       // 忽略内部空格
    assertThat(guard.isAllowed("tester@example.com")).isTrue();  // 忽略大小写
    assertThat(guard.isAllowed("DEVICE-TOKEN")).isTrue();
    assertThat(guard.isAllowed("other@example.com")).isFalse();  // 同域名不能顺带放行
    assertThat(guard.isAllowed("13800000001")).isFalse();
  }

  @Test
  void mask_hidesSensitiveMiddle() {
    assertThat(TestRecipientGuard.mask("13800000000")).isEqualTo("1***00");
    assertThat(TestRecipientGuard.mask("a@b.com")).isEqualTo("a***om");
    assertThat(TestRecipientGuard.mask("")).isEqualTo("***");
    assertThat(TestRecipientGuard.isProbeWithoutTarget("   ")).isTrue();
    assertThat(TestRecipientGuard.isProbeWithoutTarget(null)).isTrue();
  }

  @Test
  void router_blocksOutsider_beforeCallingSender_andWritesBlockedReceipt() {
    var sender = mock(NotificationSender.class);
    when(sender.getChannel()).thenReturn("EMAIL");
    var receipts = mock(NotificationReceiptRepository.class);

    var router = new NotificationRouter(
        null, null, receipts, null,
        List.of(sender), new ObjectMapper(), null,
        new TestRecipientGuard("tester@example.com"));

    SendResultDto.ChannelResult result = router.sendToChannel(
        7L, "EMAIL", "outsider@example.com", "标题", "正文", Map.of());

    assertThat(result.status()).isEqualTo(TestRecipientGuard.BLOCKED_STATUS);
    assertThat(result.errorMsg()).contains("未投递");
    verify(sender, never()).send(anyString(), anyString(), anyString(), anyMap());

    ArgumentCaptor<NotificationReceipt> captor = ArgumentCaptor.forClass(NotificationReceipt.class);
    verify(receipts).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("BLOCKED");
    assertThat(captor.getValue().getSentAt()).isNull();  // 绝不能被记成已发送
  }

  @Test
  void testSend_blocksOutsider_evenWithoutAnySender() {
    var service = new NotificationService(
        null, null, null, null, List.of(), new ObjectMapper(),
        new TestRecipientGuard("tester@example.com"));

    SendResultDto result = service.testSend(req("SMS", "13900000000"));

    assertThat(result.status()).isEqualTo("BLOCKED");
    assertThat(result.message()).contains("项目测试成员");
    assertThat(result.results().get(0).status()).isEqualTo("BLOCKED");
  }

  @Test
  void testSend_allowlistedRecipient_isExplicitlyMarkedAsDemo() {
    var sender = mock(NotificationSender.class);
    when(sender.getChannel()).thenReturn("SMS");
    when(sender.send(anyString(), anyString(), anyString(), anyMap()))
        .thenReturn(NotificationSender.SendResult.mockSuccess("msg-1", "短信网关未接入"));

    var service = new NotificationService(
        null, null, null, null, List.of(sender), new ObjectMapper(),
        new TestRecipientGuard("tester@example.com"));

    SendResultDto result = service.testSend(req("SMS", "tester@example.com"));

    assertThat(result.status()).isEqualTo("MOCK_SENT");       // 不是 SUCCESS
    assertThat(result.message()).contains("未真实投递");
    verify(sender).send(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void testSend_probeWithoutTarget_isNotReportedAsDelivery() {
    var sender = mock(NotificationSender.class);
    when(sender.getChannel()).thenReturn("SMS");
    when(sender.send(anyString(), anyString(), anyString(), anyMap()))
        .thenReturn(NotificationSender.SendResult.mockSuccess("msg-2", "短信网关未接入"));
    var service = new NotificationService(
        null, null, null, null, List.of(sender), new ObjectMapper(),
        new TestRecipientGuard(""));  // 空名单下探测仍允许，因为它没有收件人

    SendResultDto result = service.testSend(req("SMS", ""));

    assertThat(result.status()).isEqualTo("PROBE_ONLY");
    assertThat(result.message()).contains("未投递任何消息");
    assertThat(result.status()).isNotEqualTo("SUCCESS");
    // SCREEN 会向所有在线 WebSocket 客户端广播，探测绝不能碰 Sender。
    verify(sender, never()).send(anyString(), anyString(), anyString(), anyMap());
  }

  @Test
  void verificationCode_realSmsDelivery_isBlockedForOutsiders() {
    var sms = mock(SmsProperties.class);
    when(sms.isEnabled()).thenReturn(true);
    when(sms.getAccessKeyId()).thenReturn("real-access-key-id");  // 网关已接入
    var env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[0]);
    var redis = mock(StringRedisTemplate.class);

    var service = new VerificationCodeService(
        redis, mock(EmailSender.class), env, false, sms, mock(Client.class),
        new TestRecipientGuard("13800000000"));

    assertThatThrownBy(() -> service.send("phone", "13900000000"))
        .isInstanceOf(AppException.class);
    verifyNoInteractions(redis);  // 拦在频控与写 Redis 之前
  }

  @Test
  void verificationCode_placeholderCredentials_doNotBlockRegistration() {
    // 回归：ALIYUN_SMS_ENABLED 默认 true，但凭证是 CHANGE_ME 时根本不会真实投递，
    // 不能因为白名单为空就把所有手机注册验证码拦成 40301。
    var sms = mock(SmsProperties.class);
    when(sms.isEnabled()).thenReturn(true);
    when(sms.getAccessKeyId()).thenReturn("CHANGE_ME");
    var env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[0]);
    var redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    // Mockito 对 Boolean 默认返回 false，不 stub 会命中「60 秒 1 次」频控分支。
    when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

    var service = new VerificationCodeService(
        redis, mock(EmailSender.class), env, false, sms, null,
        new TestRecipientGuard(""));  // 空名单也不该拦，因为不会真实投递

    assertThatCode(() -> service.send("phone", "13900000000"))
        .doesNotThrowAnyException();
  }

  @Test
  void router_mockSender_writesMockSentReceipt_withoutSentAt() {
    var sender = mock(NotificationSender.class);
    when(sender.getChannel()).thenReturn("SMS");
    when(sender.send(anyString(), anyString(), anyString(), anyMap()))
        .thenReturn(NotificationSender.SendResult.mockSuccess("msg-3", "短信网关未接入"));
    var receipts = mock(NotificationReceiptRepository.class);
    var router = new NotificationRouter(
        null, null, receipts, null, List.of(sender), new ObjectMapper(), null,
        new TestRecipientGuard("13800000000"));

    var result = router.sendToChannel(8L, "SMS", "13800000000", "标题", "正文", Map.of());

    assertThat(result.status()).isEqualTo("MOCK_SENT");
    ArgumentCaptor<NotificationReceipt> captor = ArgumentCaptor.forClass(NotificationReceipt.class);
    verify(receipts).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("MOCK_SENT");  // 不是 SENT
    assertThat(captor.getValue().getSentAt()).isNull();
  }

  @Test
  void dispatcher_marksWholeRecordAsBlocked_whenEveryRecipientIsBlocked() {
    var router = mock(NotificationRouter.class);
    when(router.sendToChannel(any(), anyString(), anyString(), anyString(), anyString(), anyMap()))
        .thenReturn(new SendResultDto.ChannelResult(
            "SMS", "13900000000", "BLOCKED", TestRecipientGuard.BLOCKED_REASON));
    var records = mock(NotificationRecordRepository.class);
    var dispatcher = new NotificationDispatcher(
        router, records, mock(NotificationReceiptRepository.class));

    var record = new NotificationRecord();
    var request = new NotificationRequestDto(
        "WARNING", "b1", "LANDSLIDE", "RED", "标题", "正文",
        List.of("13900000000"), null, List.of("SMS"), null, null, Map.of());

    dispatcher.asyncSend(record, request, List.of("SMS"));

    assertThat(record.getStatus()).isEqualTo("BLOCKED");  // 不是 SENDING / PARTIAL
  }
}
