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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {

  private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

  private final FileAttachmentRepository repository;
  private final MinioClient minioClient;
  private final MinioProperties minioProperties;

  public AttachmentService(FileAttachmentRepository repository, MinioClient minioClient, MinioProperties minioProperties) {
    this.repository = repository;
    this.minioClient = minioClient;
    this.minioProperties = minioProperties;
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
    String fileName = file.getOriginalFilename() == null ? "file.bin" : file.getOriginalFilename();
    String objectKey = businessType + "/" + businessCode + "/" + UUID.randomUUID() + "/" + fileName;

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
            .stream(stream, file.getSize(), -1)
            .contentType(file.getContentType())
            .build());
      }
    } catch (Exception e) {
      log.error("MinIO 写入失败: {}", e.getMessage());
      throw new AppException(500, "文件存储失败，请稍后重试");
    }

    String fileUrl = presignedUrl(bucket, objectKey);

    FileAttachment attachment = new FileAttachment();
    attachment.setBusinessType(businessType);
    attachment.setBusinessCode(businessCode);
    attachment.setFileName(fileName);
    attachment.setFileSize(file.getSize());
    attachment.setFileType(file.getContentType());
    attachment.setStoragePath(objectKey);
    attachment.setFileUrl(fileUrl);
    attachment.setUploader(uploader);
    attachment.setCreatedAt(OffsetDateTime.now());
    return toDto(repository.save(attachment));
  }

  public AttachmentDto getById(Long id) {
    FileAttachment attachment = repository.findById(id)
        .orElseThrow(() -> new AppException(404, "未找到附件"));
    // 每次详情访问都生成新的 presigned URL，避免 7 天过期导致前端 403
    String freshUrl = presignedUrl(minioProperties.bucket(), attachment.getStoragePath());
    attachment.setFileUrl(freshUrl);
    repository.save(attachment);
    return toDto(attachment);
  }

  /**
   * 为指定附件强制刷新 presigned URL（前端检测到 403 时调用）。
   * 返回新 URL，同时更新 DB。
   */
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
