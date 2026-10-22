package cn.disasterguard.event.entity;

import cn.disasterguard.event.EventState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 统一事件实体（v1.7.0+ P3-4）。
 *
 * - 一条 unified_event 行对应一个完整的"监测 → 确认 → 预警 → 应急 → 关闭"生命周期
 * - hazard_point / warning_event / emergency_event 三张表通过 unified_event_id
 *   反向引用本表，构成统一的事件视图
 * - 当前状态由 EventStateMachine 维护，状态机持久化在 unified_event.state 列
 *
 * 表结构见 schema.sql 的 unified_event 段。
 */
@Entity
@Table(
  name = "unified_event",
  indexes = {
    @Index(name = "idx_unified_state", columnList = "state"),
    @Index(name = "idx_unified_hazard", columnList = "hazard_code"),
    @Index(name = "idx_unified_updated", columnList = "updated_at DESC"),
  }
)
public class UnifiedEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 业务侧统一事件号（UFE-YYYYMMDD-NNNN），跨表串联用 */
  @Column(name = "event_code", nullable = false, unique = true, length = 64)
  private String eventCode;

  /** 当前状态（由状态机写入） */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private EventState state;

  /** 关联的灾害点（来自 hazard_point.hazard_code） */
  @Column(name = "hazard_code", length = 64)
  private String hazardCode;

  /** 关联的预警（来自 warning_event.warning_code，可空） */
  @Column(name = "warning_code", length = 64)
  private String warningCode;

  /** 关联的应急事件（来自 emergency_event.event_code，可空） */
  @Column(name = "emergency_code", length = 64)
  private String emergencyCode;

  /**
   * 判别列：HAZARD / WARNING / EMERGENCY（schema.sql 中 NOT NULL DEFAULT 'HAZARD'）。
   *
   * <p>R4 P0-5 修复引入：hazard_point 是可写视图，隐患点记录的写入会走本表，
   * 而 {@code EventStateService} 需要区分"这一行是否已由 hazard 视图落库"，
   * 以避免对同一 hazard_code 重复插入（hazard_key 上有唯一索引，重复必冲突）。
   */
  @Column(name = "kind", length = 16)
  private String kind;

  /** 事件标题（取自最早被触发实体的 title） */
  @Column(length = 256)
  private String title;

  /** 事件来源：AI_AUTO / RULE_ENGINE / MANUAL */
  @Column(length = 32)
  private String source;

  /** 当前状态自何时起（时间戳，不是时长） */
  @Column(name = "current_state_since")
  private OffsetDateTime currentStateSince;

  /** 最后一次状态机跳转的时间戳 */
  @Column(name = "last_transition_at")
  private OffsetDateTime lastTransitionAt;

  /** 最后一次状态机跳转的事件编码（EventTrigger） */
  @Column(name = "last_trigger", length = 32)
  private String lastTrigger;

  /** 关闭原因 / 驳回原因 / 误报原因 */
  @Column(name = "close_reason", length = 512)
  private String closeReason;

  /**
   * 当前持有人 / 最后操作人。
   *
   * <p>每次状态流转（认领、确认、驳回）都会写入操作人，使「我已认领」筛选
   * 和转派来源人有据可查；终态（CLOSED / REJECTED）时它同时表示关闭人/驳回人。
   */
  @Column(name = "closed_by", length = 64)
  private String closedBy;

  /** 创建时间 */
  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  /** 更新时间 */
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getEventCode() { return eventCode; }
  public void setEventCode(String eventCode) { this.eventCode = eventCode; }
  public EventState getState() { return state; }
  public void setState(EventState state) { this.state = state; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public String getWarningCode() { return warningCode; }
  public void setWarningCode(String warningCode) { this.warningCode = warningCode; }
  public String getEmergencyCode() { return emergencyCode; }
  public void setEmergencyCode(String emergencyCode) { this.emergencyCode = emergencyCode; }
  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }
  public OffsetDateTime getCurrentStateSince() { return currentStateSince; }
  public void setCurrentStateSince(OffsetDateTime t) { this.currentStateSince = t; }
  public OffsetDateTime getLastTransitionAt() { return lastTransitionAt; }
  public void setLastTransitionAt(OffsetDateTime t) { this.lastTransitionAt = t; }
  public String getLastTrigger() { return lastTrigger; }
  public void setLastTrigger(String lastTrigger) { this.lastTrigger = lastTrigger; }
  public String getCloseReason() { return closeReason; }
  public void setCloseReason(String closeReason) { this.closeReason = closeReason; }
  public String getClosedBy() { return closedBy; }
  public void setClosedBy(String closedBy) { this.closedBy = closedBy; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
