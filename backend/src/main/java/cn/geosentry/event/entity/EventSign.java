package cn.geosentry.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * P0-4：会签记录（每个 signer 一行）。
 * 用 sign_id 把同一会签的所有 signer 关联起来。
 */
@Entity
@Table(name = "event_sign")
public class EventSign {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "sign_id", nullable = false, unique = true, length = 64)
  private String signId;

  @Column(name = "event_id", nullable = false, length = 64)
  private String eventId;

  @Column(nullable = false, length = 20)
  private String mode = "PARALLEL";

  @Column(name = "timeout_hours")
  private Integer timeoutHours = 24;

  @Column(name = "timeout_action", length = 20)
  private String timeoutAction = "ESCALATE";

  @Column(columnDefinition = "TEXT")
  private String comment;

  @Column(length = 64)
  private String initiator;

  @Column(nullable = false, length = 20)
  private String status = "RUNNING";

  @Column(name = "signer_id", nullable = false, length = 64)
  private String signerId;

  @Column(name = "signer_name", length = 64)
  private String signerName;

  @Column(length = 64)
  private String specialty;

  @Column(name = "order_index")
  private Integer orderIndex = 0;

  @Column(name = "signer_status", length = 20)
  private String signerStatus = "PENDING";

  @Column(columnDefinition = "TEXT")
  private String opinion;

  @Column(name = "signed_at")
  private OffsetDateTime signedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getSignId() { return signId; }
  public void setSignId(String signId) { this.signId = signId; }
  public String getEventId() { return eventId; }
  public void setEventId(String eventId) { this.eventId = eventId; }
  public String getMode() { return mode; }
  public void setMode(String mode) { this.mode = mode; }
  public Integer getTimeoutHours() { return timeoutHours; }
  public void setTimeoutHours(Integer timeoutHours) { this.timeoutHours = timeoutHours; }
  public String getTimeoutAction() { return timeoutAction; }
  public void setTimeoutAction(String timeoutAction) { this.timeoutAction = timeoutAction; }
  public String getComment() { return comment; }
  public void setComment(String comment) { this.comment = comment; }
  public String getInitiator() { return initiator; }
  public void setInitiator(String initiator) { this.initiator = initiator; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getSignerId() { return signerId; }
  public void setSignerId(String signerId) { this.signerId = signerId; }
  public String getSignerName() { return signerName; }
  public void setSignerName(String signerName) { this.signerName = signerName; }
  public String getSpecialty() { return specialty; }
  public void setSpecialty(String specialty) { this.specialty = specialty; }
  public Integer getOrderIndex() { return orderIndex; }
  public void setOrderIndex(Integer orderIndex) { this.orderIndex = orderIndex; }
  public String getSignerStatus() { return signerStatus; }
  public void setSignerStatus(String signerStatus) { this.signerStatus = signerStatus; }
  public String getOpinion() { return opinion; }
  public void setOpinion(String opinion) { this.opinion = opinion; }
  public OffsetDateTime getSignedAt() { return signedAt; }
  public void setSignedAt(OffsetDateTime signedAt) { this.signedAt = signedAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getCompletedAt() { return completedAt; }
  public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
