package cn.disasterguard.notification.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "notification_receipt")
public class NotificationReceipt {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "record_id", nullable = false)
  private Long recordId;

  @Column(length = 20)
  private String channel;

  @Column(length = 255)
  private String target;

  @Column(length = 20)
  private String status;

  @Column(name = "error_msg", columnDefinition = "TEXT")
  private String errorMsg;

  @Column(name = "retry_count")
  private Integer retryCount;

  @Column(name = "sent_at")
  private OffsetDateTime sentAt;

  @Column(name = "delivered_at")
  private OffsetDateTime deliveredAt;

  @Column(name = "created_at")
  private OffsetDateTime createdAt;

  public NotificationReceipt() {
    this.createdAt = OffsetDateTime.now();
    this.retryCount = 0;
    this.status = "PENDING";
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getRecordId() { return recordId; }
  public void setRecordId(Long recordId) { this.recordId = recordId; }
  public String getChannel() { return channel; }
  public void setChannel(String channel) { this.channel = channel; }
  public String getTarget() { return target; }
  public void setTarget(String target) { this.target = target; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getErrorMsg() { return errorMsg; }
  public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
  public Integer getRetryCount() { return retryCount; }
  public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
  public OffsetDateTime getSentAt() { return sentAt; }
  public void setSentAt(OffsetDateTime sentAt) { this.sentAt = sentAt; }
  public OffsetDateTime getDeliveredAt() { return deliveredAt; }
  public void setDeliveredAt(OffsetDateTime deliveredAt) { this.deliveredAt = deliveredAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
