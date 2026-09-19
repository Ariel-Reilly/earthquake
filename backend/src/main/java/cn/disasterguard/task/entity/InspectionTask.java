package cn.disasterguard.task.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "inspection_task")
public class InspectionTask {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_code", nullable = false, unique = true)
  private String taskCode;

  @Column(nullable = false)
  private String name;

  @Column(name = "task_type", nullable = false)
  private String taskType;

  @Column
  private String executor;

  @Column(name = "region_code")
  private String regionCode;

  @Column(nullable = false)
  private String status;

  @Column(nullable = false)
  private Integer progress;

  @Column(name = "start_time")
  private OffsetDateTime startTime;

  @Column(name = "due_time")
  private OffsetDateTime dueTime;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "hazard_code")
  private String hazardCode;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getTaskCode() { return taskCode; }
  public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getTaskType() { return taskType; }
  public void setTaskType(String taskType) { this.taskType = taskType; }
  public String getExecutor() { return executor; }
  public void setExecutor(String executor) { this.executor = executor; }
  public String getRegionCode() { return regionCode; }
  public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Integer getProgress() { return progress; }
  public void setProgress(Integer progress) { this.progress = progress; }
  public OffsetDateTime getStartTime() { return startTime; }
  public void setStartTime(OffsetDateTime startTime) { this.startTime = startTime; }
  public OffsetDateTime getDueTime() { return dueTime; }
  public void setDueTime(OffsetDateTime dueTime) { this.dueTime = dueTime; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
}
