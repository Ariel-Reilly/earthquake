package cn.disasterguard.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * ai-service {@code POST /api/ai/detect-image} 的响应体。
 *
 * <p>与 {@link DetectionResult} 的区别：{@code /detect} 返回原始检测框（COCO 类别名），
 * 而 {@code /detect-image} 返回的是 Python 侧已折算好的<b>业务灾种</b>，
 * 因此这里不需要解析 {@code detections}，只取结论字段。
 *
 * <p>为什么两种命名都挂 {@link JsonAlias}：Python 侧 {@code disasterType} 用的是
 * serialization_alias 输出的 camelCase，其余字段是 snake_case。任一端后续调整命名风格
 * 都不应打崩反序列化，别名能让两种写法都读得到。
 *
 * @param disasterType   业务灾种码（{@code debris_flow} / {@code none} 等，与 hazard_point 同套编码）
 * @param confidence     业务置信度 0.0-1.0
 * @param degraded       true 表示 Python 端模型未加载、返回的是启发式 mock 结果
 * @param detectionCount 原始检出目标数，仅用于日志判断"为什么被判为无险情"
 * @param model          模型标识，写入 risk_score_log 便于回溯
 * @param imageUrl       回显的图片地址，便于日志与事件对账
 */
public record AiImageResponse(
    @JsonAlias({"disasterType", "disaster_type"}) String disasterType,
    double confidence,
    @JsonAlias("mock") boolean degraded,
    @JsonAlias({"detectionCount", "detection_count"}) int detectionCount,
    String model,
    @JsonAlias({"imageUrl", "image_url"}) String imageUrl
) {
}
