package cn.geosentry.notification.channel;

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
      String errorMsg
  ) {
    public static SendResult success(String messageId) {
      return new SendResult(true, messageId, null);
    }

    public static SendResult failure(String errorMsg) {
      return new SendResult(false, null, errorMsg);
    }
  }
}
