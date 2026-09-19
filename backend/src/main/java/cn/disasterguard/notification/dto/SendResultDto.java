package cn.disasterguard.notification.dto;

import java.util.List;

/**
 * 发送结果 DTO
 */
public record SendResultDto(
    Long recordId,
    String status,
    List<ChannelResult> results,
    String message
) {
  public record ChannelResult(
      String channel,
      String target,
      String status,
      String errorMsg
  ) {}
}
