package cn.geosentry.auth;

import java.util.List;
import java.util.Map;

/**
 * 用户通知偏好 DTO。
 */
public record NotificationPreferenceDto(
    Map<String, Boolean> channels,
    Map<String, List<String>> levels,
    String language,
    String theme,
    Boolean compactMode,
    Boolean quietHoursEnabled,
    String quietStart,
    String quietEnd
) {}
