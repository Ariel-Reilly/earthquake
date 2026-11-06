package cn.disasterguard.attachment.dto;

import java.time.OffsetDateTime;

public record AttachmentDto(
    Long id,
    String businessType,
    String businessCode,
    String fileName,
    Long fileSize,
    String fileType,
    String storagePath,
    String fileUrl,
    String uploader,
    OffsetDateTime createdAt
) {
}
