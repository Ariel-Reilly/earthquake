package cn.disasterguard.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * 单台设备 AI 健康评分请求。
 *
 * <p>序列化方向：Java → Python（FastAPI Pydantic）。字段名固定 snake_case，
 * 通过 {@code @JsonProperty} 锁定出站 JSON 字段名（避免历史 {@code @JsonAlias} 仅影响入站
 * 导致 Java 出站仍输出 camelCase 的 bug）。
 *
 * <p>本 DTO 由 {@code AiDeviceHealthService} 构造后透传给 Python，**不**作为 {@code @RequestBody}
 * 接收前端请求，故无需保留 camelCase 入站别名。
 */
public record DeviceHealthRequestDto(
    @JsonProperty("device_code") String deviceCode,
    @JsonProperty("metrics") Map<String, List<Double>> metrics,
    @JsonProperty("online_status") String onlineStatus,
    @JsonProperty("last_report_at") String lastReportAt
) {}
