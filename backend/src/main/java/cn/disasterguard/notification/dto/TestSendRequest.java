package cn.disasterguard.notification.dto;

import java.util.List;
import java.util.Map;

/**
 * 测试发送请求 DTO
 */
public record TestSendRequest(
    String channel,
    String target,
    String title,
    String content,
    Map<String, Object> variables
) {}
