package cn.geosentry.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;
import java.util.Map;

/**
 * 单台设备 AI 健康评分请求。
 *
 * <p>字段命名沿用 snake_case（与 Python FastAPI 端 Pydantic 模型一致），
 * Java 字段通过 {@code @JsonAlias} 接收。
 */
public record DeviceHealthRequestDto(
    @JsonAlias("device_code") String deviceCode,
    Map<String, List<Double>> metrics,
    @JsonAlias("online_status") String onlineStatus,
    @JsonAlias("last_report_at") String lastReportAt
) {}
