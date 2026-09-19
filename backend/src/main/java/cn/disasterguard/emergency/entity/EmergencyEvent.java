package cn.disasterguard.emergency.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "emergency_event")
public class EmergencyEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_code", nullable = false, unique = true)
  private String eventCode;

  @Column(name = "warning_code")
  private String warningCode;

  @Column(name = "hazard_code")
  private String hazardCode;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false)
  private String status;

  private String commander;

  @Column(name = "step_name")
  private String stepName;

  @Column(name = "result_summary")
  private String resultSummary;

  @Column(name = "started_at")
  private OffsetDateTime startedAt;

  @Column(name = "closed_at")
  private OffsetDateTime closedAt;

  @Column(name = "created_at")
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getEventCode() { return eventCode; }
  public void setEventCode(String eventCode) { this.eventCode = eventCode; }
  public String getWarningCode() { return warningCode; }
  public void setWarningCode(String warningCode) { this.warningCode = warningCode; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getCommander() { return commander; }
  public void setCommander(String commander) { this.commander = commander; }
  public String getStepName() { return stepName; }
  public void setStepName(String stepName) { this.stepName = stepName; }
  public String getResultSummary() { return resultSummary; }
  public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
  public OffsetDateTime getStartedAt() { return startedAt; }
  public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
  public OffsetDateTime getClosedAt() { return closedAt; }
  public void setClosedAt(OffsetDateTime closedAt) { this.closedAt = closedAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
