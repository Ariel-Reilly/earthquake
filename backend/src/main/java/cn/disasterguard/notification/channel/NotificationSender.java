package cn.disasterguard.notification.channel;

import java.util.Map;

/**
 * 通知渠道发送器接口
 */
public interface NotificationSender {

  /**
   * 获取渠道标识
   */
  String getChannel();

  /**
   * 发送通知
   *
   * @param target  发送目标（手机号/邮箱/设备Token等）
   * @param title   标题
   * @param content 内容
   * @param variables 变量映射
   * @return 发送结果
   */
  SendResult send(String target, String title, String content, Map<String, Object> variables);

  /**
   * 发送结果
   */
  record SendResult(
      boolean success,
      String messageId,
      String errorMsg,
      boolean mock,
      String mockReason
  ) {
    /** 真实网关发送成功。 */
    public static SendResult success(String messageId) {
      return new SendResult(true, messageId, null, false, null);
    }

    /**
     * 演示/占位发送：请求被接受并生成了回执，但**没有真正投递到外部网关**。
     * 保留 mock 标记，让通知记录与页面能如实显示「演示发送」，不再伪装成真实送达。
     */
    public static SendResult mockSuccess(String messageId, String reason) {
      return new SendResult(true, messageId, null, true, reason);
    }

    public static SendResult failure(String errorMsg) {
      return new SendResult(false, null, errorMsg, false, null);
    }
  }
}
