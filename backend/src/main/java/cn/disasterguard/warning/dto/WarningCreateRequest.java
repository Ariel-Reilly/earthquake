package cn.disasterguard.warning.dto;

import java.util.List;

/**
 * 人工上报预警请求（供 POST /api/warnings 使用）。
 *
 * <p>字段与前端 {@code WarningCreatePage.vue} 提交的表单一一对应。
 * 注意两个跨端编码差异，由 {@code WarningService#createManual} 统一在服务端归一化，
 * 避免"前端改了、后端没跟上"导致库里混入两套编码：
 * <ul>
 *   <li>{@code level}：前端用英文码（red / orange / yellow / blue），
 *       库里 {@code warning_event.warning_level} 存中文（红色预警 / 橙色预警 / ...）。</li>
 *   <li>{@code hazardType}：前端用中文标签（滑坡 / 崩塌 / ...），
 *       库里 {@code hazard_point.hazard_type} 存英文码（landslide / collapse / ...）。</li>
 * </ul>
 */
public record WarningCreateRequest(
    String title,
    String level,
    String hazardCode,
    String hazardName,
    String hazardType,
    String region,
    Double lon,
    Double lat,
    Double bufferKm,
    List<String> channels,
    String content,
    String validUntil,
    String operator
) {
}
