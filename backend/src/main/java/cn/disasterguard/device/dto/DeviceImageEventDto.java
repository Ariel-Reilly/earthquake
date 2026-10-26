package cn.disasterguard.device.dto;

import java.time.OffsetDateTime;

/**
 * 设备图像事件：图像入库（MinIO）后组装的最小上下文。
 *
 * <p>刻意只保留"谁拍的 / 存在哪 / 拍在哪 / 什么时候"四要素，
 * 作为后续 AI 图像识别与气象融合链路的输入契约，
 * 后续接入识别结果时再扩展字段，避免此刻把未确定的识别结构固化进来。
 *
 * @param deviceId    设备主键 id（未按 id 命中时为 null）
 * @param deviceCode  设备编号，业务侧唯一标识
 * @param imageUrl    MinIO 预签名访问地址（带有效期，可直接给前端展示）
 * @param objectKey   MinIO 对象键，长期有效的定位符，图像识别服务应使用它取原始文件
 * @param longitude   图像所属经度，优先取请求坐标，缺失时回落设备档案坐标
 * @param latitude    图像所属纬度，同 longitude
 * @param capturedAt  服务端接收时间
 */
public record DeviceImageEventDto(
    Long deviceId,
    String deviceCode,
    String imageUrl,
    String objectKey,
    Double longitude,
    Double latitude,
    OffsetDateTime capturedAt
) {
}
