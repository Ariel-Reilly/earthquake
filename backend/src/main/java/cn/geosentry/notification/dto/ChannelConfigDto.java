package cn.geosentry.notification.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record ChannelConfigDto(
    Long id,
    String disasterType,
    String level,
    List<ChannelSetting> channels,
    Map<String, String> templateMapping,
    Boolean enabled,
    OffsetDateTime updatedAt
) {
  public record ChannelSetting(
      String channel,
      Boolean enabled,
      Integer priority,
      String templateId
  ) {}
}
