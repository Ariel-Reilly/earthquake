package cn.geosentry.attachment.service;

import cn.geosentry.attachment.dto.AttachmentDto;
import cn.geosentry.attachment.entity.FileAttachment;
import cn.geosentry.attachment.repository.FileAttachmentRepository;
import cn.geosentry.common.exception.AppException;
import cn.geosentry.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {

  private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

  /** 单文件大小上限：50 MB（配置项可覆盖）。超过拒绝上传避免磁盘占满 + OOM。 */
  private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

  /** 允许的文件扩展名白名单：Office + 图片 + PDF + 常用工程格式。 */
  private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
      "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
      "png", "jpg", "jpeg", "gif", "bmp", "webp", "tiff",
      "csv", "txt", "zip", "rar", "7z",
      "dwg", "dxf", "shp", "geojson"
  );

  private final FileAttachmentRepository repository;
  private final MinioClient minioClient;
  private final MinioProperties minioProperties;

  public AttachmentService(FileAttachmentRepository repository, MinioClient minioClient, MinioProperties minioProperties) {
    this.repository = repository;
    this.minioClient = minioClient;
    this.minioProperties = minioProperties;
  }

  /** 启动时预热 bucket（首次附件上传时也会 ensureBucket，但启动时预热可提前暴露配置错误）。 */
  @PostConstruct
  void warmUpBucket() {
    try {
      String bucket = minioProperties.bucket();
      boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      if (!exists) {
        log.info("MinIO bucket '{}' 不存在，启动时自动创建", bucket);
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      } else {
        log.debug("MinIO bucket '{}' 已就绪", bucket);
      }
    } catch (Exception e) {
      // bucket 预热失败不阻断启动（MinIO 可能还没起来，后续上传时会重试）
      log.warn("MinIO bucket 预热失败（跳过，首次上传时会重试）: {}", e.getMessage());
    }
  }

  public List<AttachmentDto> list(String businessType, String businessCode) {
    return repository.findByBusinessTypeAndBusinessCodeOrderByCreatedAtDesc(businessType, businessCode)
        .stream().map(this::toDto).toList();
  }

  @Transactional
  public AttachmentDto upload(String businessType, String businessCode, MultipartFile file, String uploader) {
    if (file == null || file.isEmpty()) {
      throw new AppException(400, "请选择上传文件");
    }
    // 文件大小校验
    long size = file.getSize();
    if (size > MAX_FILE_SIZE) {
      throw new AppException(413, "文件过大，最大允许 " + (MAX_FILE_SIZE / 1024 / 1024) + " MB");
    }
    // 文件名清洗：去除路径穿越字符，保留纯文件名
    String rawName = file.getOriginalFilename() == null ? "file.bin" : file.getOriginalFilename();
    String safeName = sanitizeFileName(rawName);
    if (safeName.isEmpty()) {
      safeName = "unnamed.bin";
    }
    // 扩展名白名单校验
    String ext = getExtension(safeName);
    if (!ext.isEmpty() && !ALLOWED_EXTENSIONS.contains(ext.toLowerCase())) {
      throw new AppException(400, "不支持的文件类型: " + ext + "。允许: " + String.join(", ", ALLOWED_EXTENSIONS));
    }
    // objectKey 不再携带原始文件名（避免 MinIO 内部路径穿越 + 下载时用户看到陌生文件名）
    String objectKey = businessType + "/" + businessCode + "/" + UUID.randomUUID();

    String bucket = minioProperties.bucket();
    try {
      boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      if (!exists) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }
      try (InputStream stream = file.getInputStream()) {
        minioClient.putObject(PutObjectArgs.builder()
            .bucket(bucket)
            .object(objectKey)
            .stream(stream, size, -1)
            .contentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType())
            .build());
      }
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      log.error("MinIO 写入失败: {}", e.getMessage());
      throw new AppException(500, "文件存储失败，请稍后重试");
    }

    String fileUrl = presignedUrl(bucket, objectKey);

    FileAttachment attachment = new FileAttachment();
    attachment.setBusinessType(businessType);
    attachment.setBusinessCode(businessCode);
    attachment.setFileName(safeName);  // 存清洗后的文件名
    attachment.setFileSize(size);
    attachment.setFileType(file.getContentType());
    attachment.setStoragePath(objectKey);
    attachment.setFileUrl(fileUrl);
    attachment.setUploader(uploader);
    attachment.setCreatedAt(OffsetDateTime.now());
    return toDto(repository.save(attachment));
  }

  /** 从原始文件名提取小写扩展名（不含点号）。无扩展名返回空串。 */
  private static String getExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) return "";
    return fileName.substring(dot + 1).toLowerCase();
  }

  /** 文件名安全化：移除所有路径分隔符和控制字符，只保留合法文件名字符。 */
  private static String sanitizeFileName(String name) {
    // 移除 \ / : * ? " < > | 以及所有控制字符，同时裁掉前导点和尾部空白
    String safe = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    // 防止以 . 开头（隐藏文件）或以空格开头
    while (safe.startsWith(".") || safe.startsWith(" ")) {
      safe = safe.substring(1);
    }
    // 防止文件名过长（MinIO object key 上限 1024，这里保守限制 200）
    if (safe.length() > 200) {
      safe = safe.substring(0, 200);
    }
    return safe;
  }

  /** 查询附件详情 + 刷新 presigned URL（每次访问更新 DB，保证 7 天过期自动续期）。 */
  @Transactional
  public AttachmentDto getById(Long id) {
    FileAttachment attachment = repository.findById(id)
        .orElseThrow(() -> new AppException(404, "未找到附件"));
    String freshUrl = presignedUrl(minioProperties.bucket(), attachment.getStoragePath());
    attachment.setFileUrl(freshUrl);
    repository.save(attachment);
    return toDto(attachment);
  }

  /**
   * 为指定附件强制刷新 presigned URL（前端检测到 403 时调用）。
   * 返回新 URL，同时更新 DB。
   */
  @Transactional
  public String refreshUrl(Long id) {
    FileAttachment attachment = repository.findById(id)
        .orElseThrow(() -> new AppException(404, "未找到附件"));
    String freshUrl = presignedUrl(minioProperties.bucket(), attachment.getStoragePath());
    attachment.setFileUrl(freshUrl);
    repository.save(attachment);
    return freshUrl;
  }

  @Transactional
  public void delete(Long id) {
    FileAttachment attachment = repository.findById(id).orElseThrow(() -> new AppException(404, "未找到附件"));
    try {
      minioClient.removeObject(RemoveObjectArgs.builder()
          .bucket(minioProperties.bucket())
          .object(attachment.getStoragePath())
          .build());
    } catch (Exception e) {
      log.warn("MinIO 删除失败（忽略，仅删元数据）: {}", e.getMessage());
    }
    repository.delete(attachment);
  }

  private String presignedUrl(String bucket, String objectKey) {
    try {
      return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
          .method(Method.GET)
          .bucket(bucket)
          .object(objectKey)
          .expiry(7, TimeUnit.DAYS)
          .build());
    } catch (Exception e) {
      log.warn("生成 presigned URL 失败，回退默认拼接: {}", e.getMessage());
      return "http://localhost:9000/" + bucket + "/" + objectKey;
    }
  }

  private AttachmentDto toDto(FileAttachment attachment) {
    return new AttachmentDto(
        attachment.getId(),
        attachment.getBusinessType(),
        attachment.getBusinessCode(),
        attachment.getFileName(),
        attachment.getFileSize(),
        attachment.getFileType(),
        attachment.getStoragePath(),
        attachment.getFileUrl(),
        attachment.getUploader(),
        attachment.getCreatedAt()
    );
  }
}
