package cn.disasterguard.governance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 数据同步任务实体，对应 {@code data_sync_task} 表。
 *
 * <p>与 {@link SyncTaskRecord}（{@code sync_task_record}，存任务当前状态 JSON）不同，
 * 本表是规范化表，带 {@code last_run_at / last_status / records_synced / error_message}
 * 等真实执行字段，且通过 {@code data_source_id} 外键关联真实数据源。
 *
 * <p>用于让 {@code /api/sync/history} 与 {@code /api/sync/anomalies} 摆脱硬编码
 * 内存 mock（原实现用 {@code LocalDateTime.now()} 编造 6 条历史 + 3 条异常）。
 */
@Entity
@Table(name = "data_sync_task")
public class DataSyncTask {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_code", nullable = false, unique = true)
  private String taskCode;

  @Column(name = "task_name", nullable = false)
  private String taskName;

  @Column(name = "data_source_id")
  private Long dataSourceId;

  @Column(name = "sync_type", nullable = false)
  private String syncType;

  @Column(name = "cron_expr")
  private String cronExpr;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "last_run_at")
  private OffsetDateTime lastRunAt;

  @Column(name = "last_status")
  private String lastStatus;

  @Column(name = "next_run_at")
  private OffsetDateTime nextRunAt;

  @Column(name = "records_synced")
  private Long recordsSynced;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getTaskCode() { return taskCode; }
  public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
  public String getTaskName() { return taskName; }
  public void setTaskName(String taskName) { this.taskName = taskName; }
  public Long getDataSourceId() { return dataSourceId; }
  public void setDataSourceId(Long dataSourceId) { this.dataSourceId = dataSourceId; }
  public String getSyncType() { return syncType; }
  public void setSyncType(String syncType) { this.syncType = syncType; }
  public String getCronExpr() { return cronExpr; }
  public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public OffsetDateTime getLastRunAt() { return lastRunAt; }
  public void setLastRunAt(OffsetDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
  public String getLastStatus() { return lastStatus; }
  public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
  public OffsetDateTime getNextRunAt() { return nextRunAt; }
  public void setNextRunAt(OffsetDateTime nextRunAt) { this.nextRunAt = nextRunAt; }
  public Long getRecordsSynced() { return recordsSynced; }
  public void setRecordsSynced(Long recordsSynced) { this.recordsSynced = recordsSynced; }
  public String getErrorMessage() { return errorMessage; }
  public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
