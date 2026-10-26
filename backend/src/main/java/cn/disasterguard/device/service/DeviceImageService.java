package cn.disasterguard.device.service;

import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.config.MinioProperties;
import cn.disasterguard.device.dto.DeviceImageEventDto;
import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.event.DeviceImageUploadedEvent;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 设备图像接收服务（AI 图像识别链路第一步）。
 *
 * <p>本服务只负责"图片落到 MinIO + 记录坐标与时间 + 发布图像事件"，
 * 不做识别、不产生预警：视觉识别是秒级外部调用，同步执行会拖垮设备上传接口，
 * 因此把识别交给 {@code cn.disasterguard.device.listener.ImageAnalysisListener} 异步消费。
 *
 * <p>之所以独立成服务而不是写进 Controller：后续 AI 研判与气象融合
 * 会复用同一份图像事件上下文，届时只需在此追加识别调用。
 *
 * <p>注意：这里直接使用 MinioClient 而非复用 AttachmentService。
 * 现有 AttachmentService.upload 会写 file_attachment 表，而该表的
 * business_type 列已被历史迁移改成 jsonb（schema.sql 末尾的 ALTER），
 * 实体仍按 String 绑定，插入必然报
 * {@code column "business_type" is of type jsonb but expression is of type character varying}。
 * 图像事件目前也没有入库诉求，故绕开该表；待附件表修复后可再统一到附件体系。
 */
@Service
public class DeviceImageService {

  private static final Logger log = LoggerFactory.getLogger(DeviceImageService.class);

  /** 对象键前缀：与附件业务（预警/应急）在 MinIO 中分目录，便于按前缀批量清理与统计。 */
  private static final String OBJECT_KEY_PREFIX = "device-image/";

  /** 图像体积上限 20 MB：单张监控图片远小于此值，超过多半是误传视频或原始 RAW。 */
  private static final long MAX_IMAGE_SIZE = 20L * 1024 * 1024;

  /** 预签名地址有效期。objectKey 才是长期定位符，URL 只用于即时展示。 */
  private static final int PRESIGNED_EXPIRE_DAYS = 7;

  /** 常见图像扩展名。用于 Content-Type 缺失（部分嵌入式相机不带）时仍能判定为图片。 */
  private static final Set<String> IMAGE_EXTENSIONS = Set.of(
      "jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff", "heic");

  private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyyMMdd");

  private final MonitoringDeviceRepository deviceRepository;
  private final MinioClient minioClient;
  private final MinioProperties minioProperties;
  private final ApplicationEventPublisher eventPublisher;

  public DeviceImageService(MonitoringDeviceRepository deviceRepository,
                            MinioClient minioClient,
                            MinioProperties minioProperties,
                            ApplicationEventPublisher eventPublisher) {
    this.deviceRepository = deviceRepository;
    this.minioClient = minioClient;
    this.minioProperties = minioProperties;
    this.eventPublisher = eventPublisher;
  }

  /**
   * 接收设备图像：校验 → 上传 MinIO → 发布图像事件 → 返回图像上下文。
   *
   * @param deviceId 设备编号或设备主键 id（两者都接受，见 {@link #resolveDevice}）
   * @param file     图像文件
   * @param lng      请求携带的经度，可为 null
   * @param lat      请求携带的纬度，可为 null
   * @return 图像事件上下文，供前端展示与后续 AI 链路消费
   * @throws AppException 文件不合法（400/413）、设备不存在（404）或存储失败（500）
   */
  public DeviceImageEventDto receive(String deviceId, MultipartFile file, Double lng, Double lat) {
    if (file == null || file.isEmpty()) {
      throw new AppException(400, "请选择要上传的图片");
    }
    if (file.getSize() > MAX_IMAGE_SIZE) {
      throw new AppException(413, "图片过大，最大允许 " + (MAX_IMAGE_SIZE / 1024 / 1024) + " MB");
    }
    // 嵌入式相机常常只给 application/octet-stream，故内容类型与扩展名任一命中即放行，
    // 避免为了"严格"把真实设备挡在门外。
    String extension = resolveImageExtension(file);
    if (extension == null) {
      throw new AppException(400, "仅支持上传图片文件");
    }
    validateCoordinate(lng, lat);

    MonitoringDevice device = resolveDevice(deviceId);

    // 请求坐标优先：同编号设备可能被临时架设或挪位，请求值更贴近实际拍摄点；
    // 缺失时回落设备档案坐标，避免图像进入后续空间研判时没有位置信息。
    Double finalLng = lng != null ? lng : device.getLongitude();
    Double finalLat = lat != null ? lat : device.getLatitude();

    String objectKey = buildObjectKey(device.getDeviceCode(), extension);
    putObject(objectKey, file);
    String imageUrl = presignedUrl(objectKey);

    OffsetDateTime capturedAt = OffsetDateTime.now();
    log.info("【图像接收】收到设备 {} 的图片，坐标：{}, {}，已存入 MinIO，URL={}",
        device.getDeviceCode(), finalLng, finalLat, imageUrl);

    // 存证完成后再发事件：此时 imageUrl 已可用，下游 AI 拿到即可下载，
    // 也不会因为识别耗时或失败影响本次上传的返回结果。
    eventPublisher.publishEvent(new DeviceImageUploadedEvent(
        device.getDeviceCode(), imageUrl, finalLng, finalLat, capturedAt));

    return new DeviceImageEventDto(
        device.getId(),
        device.getDeviceCode(),
        imageUrl,
        objectKey,
        finalLng,
        finalLat,
        capturedAt
    );
  }

  /**
   * 解析设备：优先按业务编号匹配，未命中且入参为纯数字时再按主键查找。
   *
   * <p>路径变量同时承载两种语义，是因为硬件侧天然只知道 device_code，
   * 而管理后台拿到的往往是数据库 id；同时支持可省掉调用方的一次转换。
   */
  private MonitoringDevice resolveDevice(String deviceId) {
    if (deviceId == null || deviceId.isBlank()) {
      throw new AppException(400, "设备编号不能为空");
    }
    String key = deviceId.trim();
    MonitoringDevice device = deviceRepository.findByDeviceCode(key);
    if (device == null && key.matches("\\d+")) {
      device = deviceRepository.findById(Long.valueOf(key)).orElse(null);
    }
    if (device == null) {
      throw new AppException(404, "未找到设备: " + key);
    }
    return device;
  }

  /**
   * 判定并返回图像扩展名（小写、不含点号）。非图片返回 null。
   *
   * <p>优先信任 Content-Type（image/* 时再按原文件名取扩展名，取不到用 jpg 兜底），
   * 其次看原文件名扩展名，兼顾标准客户端与裸发字节流的相机。
   */
  private String resolveImageExtension(MultipartFile file) {
    String originalExt = extensionOf(file.getOriginalFilename());
    String contentType = file.getContentType();
    boolean imageContentType = contentType != null
        && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
    if (imageContentType) {
      return originalExt != null && IMAGE_EXTENSIONS.contains(originalExt) ? originalExt : "jpg";
    }
    return originalExt != null && IMAGE_EXTENSIONS.contains(originalExt) ? originalExt : null;
  }

  private String extensionOf(String fileName) {
    if (fileName == null) {
      return null;
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return null;
    }
    return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** 对象键按「设备 / 日期」分层，避免单目录对象过多，也便于按天归档清理。 */
  private String buildObjectKey(String deviceCode, String extension) {
    return OBJECT_KEY_PREFIX + deviceCode + "/" + OffsetDateTime.now().format(DATE_DIR)
        + "/" + UUID.randomUUID() + "." + extension;
  }

  private void putObject(String objectKey, MultipartFile file) {
    String bucket = minioProperties.bucket();
    try {
      if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }
      try (InputStream stream = file.getInputStream()) {
        minioClient.putObject(PutObjectArgs.builder()
            .bucket(bucket)
            .object(objectKey)
            .stream(stream, file.getSize(), -1)
            .contentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType())
            .build());
      }
    } catch (Exception e) {
      // 原始异常只进日志：MinIO 报错里可能带 endpoint/凭据线索，不返回给调用方。
      log.error("设备图像写入 MinIO 失败 objectKey={}: {}", objectKey, e.getMessage());
      throw new AppException(500, "图像存储失败，请稍后重试");
    }
  }

  private String presignedUrl(String objectKey) {
    String bucket = minioProperties.bucket();
    try {
      return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
          .method(Method.GET)
          .bucket(bucket)
          .object(objectKey)
          .expiry(PRESIGNED_EXPIRE_DAYS, TimeUnit.DAYS)
          .build());
    } catch (Exception e) {
      log.warn("生成图像预签名 URL 失败，回退直连拼接: {}", e.getMessage());
      return minioProperties.endpoint() + "/" + bucket + "/" + objectKey;
    }
  }

  /** 提前拦截越界坐标，避免脏数据流入后续空间研判与预警计算。 */
  private void validateCoordinate(Double lng, Double lat) {
    if (lng != null && (lng < -180 || lng > 180)) {
      throw new AppException(400, "经度超出有效范围 [-180, 180]: " + lng);
    }
    if (lat != null && (lat < -90 || lat > 90)) {
      throw new AppException(400, "纬度超出有效范围 [-90, 90]: " + lat);
    }
  }
}
