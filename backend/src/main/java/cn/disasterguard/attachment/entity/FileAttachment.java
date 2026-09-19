package cn.disasterguard.attachment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "file_attachment")
public class FileAttachment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "business_type", nullable = false)
  private String businessType;

  @Column(name = "business_code", nullable = false)
  private String businessCode;

  @Column(name = "file_name", nullable = false)
  private String fileName;

  @Column(name = "file_size")
  private Long fileSize;

  @Column(name = "file_type")
  private String fileType;

  @Column(name = "storage_path", nullable = false)
  private String storagePath;

  @Column(name = "file_url", nullable = false)
  private String fileUrl;

  @Column(name = "uploader")
  private String uploader;

  @Column(name = "created_at")
  private OffsetDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getBusinessType() { return businessType; }
  public void setBusinessType(String businessType) { this.businessType = businessType; }
  public String getBusinessCode() { return businessCode; }
  public void setBusinessCode(String businessCode) { this.businessCode = businessCode; }
  public String getFileName() { return fileName; }
  public void setFileName(String fileName) { this.fileName = fileName; }
  public Long getFileSize() { return fileSize; }
  public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
  public String getFileType() { return fileType; }
  public void setFileType(String fileType) { this.fileType = fileType; }
  public String getStoragePath() { return storagePath; }
  public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
  public String getFileUrl() { return fileUrl; }
  public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
  public String getUploader() { return uploader; }
  public void setUploader(String uploader) { this.uploader = uploader; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
