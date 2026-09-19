package cn.disasterguard.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * P0-4：事件转派历史。
 */
@Entity
@Table(name = "event_transfer")
public class EventTransfer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id", nullable = false, length = 64)
  private String eventId;

  @Column(name = "from_user", length = 64)
  private String fromUser;

  @Column(name = "to_user", nullable = false, length = 64)
  private String toUser;

  @Column(columnDefinition = "TEXT")
  private String reason;

  @Column(length = 64)
  private String operator;

  @Column(name = "transferred_at", nullable = false)
  private OffsetDateTime transferredAt = OffsetDateTime.now();

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getEventId() { return eventId; }
  public void setEventId(String eventId) { this.eventId = eventId; }
  public String getFromUser() { return fromUser; }
  public void setFromUser(String fromUser) { this.fromUser = fromUser; }
  public String getToUser() { return toUser; }
  public void setToUser(String toUser) { this.toUser = toUser; }
  public String getReason() { return reason; }
  public void setReason(String reason) { this.reason = reason; }
  public String getOperator() { return operator; }
  public void setOperator(String operator) { this.operator = operator; }
  public OffsetDateTime getTransferredAt() { return transferredAt; }
  public void setTransferredAt(OffsetDateTime transferredAt) { this.transferredAt = transferredAt; }
}
