package cn.disasterguard.device.event;

import java.time.OffsetDateTime;

/**
 * 设备图像上传完成事件。
 *
 * <p>只携带下游 AI 研判必需的最小上下文，不搬运 {@code DeviceImageEventDto} 的全部字段：
 * {@code objectKey} 属于存储实现细节，AI 侧只应依赖可直接下载的 {@code imageUrl}，
 * 否则一旦更换存储或改为内网直连，消费方也得跟着改。
 *
 * <p>用 record 而非可变对象，是因为事件会被异步线程消费，
 * 不可变可以避免发布方与消费方之间的隐式共享状态。
 *
 * @param deviceCode 设备业务编号（AI 侧据此回溯设备档案与历史图像）
 * @param imageUrl   预签名下载地址，有效期 7 天，消费方需及时处理
 * @param longitude  拍摄点经度，请求未带坐标时为设备档案坐标，可能为 null
 * @param latitude   拍摄点纬度，同上
 * @param capturedAt 后端接收到图像的时间（设备时钟不可信，故不采用 EXIF 时间）
 */
public record DeviceImageUploadedEvent(
    String deviceCode,
    String imageUrl,
    Double longitude,
    Double latitude,
    OffsetDateTime capturedAt
) {
}
