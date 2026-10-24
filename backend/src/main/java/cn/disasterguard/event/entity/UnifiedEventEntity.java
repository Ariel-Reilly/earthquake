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
 *
 * <p><b>v2.1.0 演进（R4 P0-5 修复）：</b>
 * hazard_point 已改为基于 unified_event 的可写视图（{@code WHERE kind='HAZARD'}），
 * 灾害点的业务字段实际存放在 unified_event 的 hazard 领域列里。因此本实体需要
 * 映射这些列才能"读到"灾害详情——但<b>这些列全部声明为只读</b>
 * （{@code insertable=false, updatable=false}）：
 * <ul>
 *   <li>写入路径统一走 {@code HazardPointRepository} → hazard_point 视图 → unified_event 表，
 *       由视图自动重写；本实体不负责写这些列。</li>
 *   <li>WARNING / EMERGENCY 类型的行这些列本就为 NULL。若允许本实体插入，
 *       Hibernate 会生成带这些列的 INSERT 语句；其中 {@code historical_count} /
 *       {@code historical_max_score} / {@code exposure_population} /
 *       {@code exposure_buildings} / {@code land_use_type} 在库中是 NOT NULL，
 *       当前能靠列 DEFAULT 兜底，但一旦某天 DEFAULT 被移除就会 500。
 *       {@code insertable=false} 让 INSERT 根本不带这些列，彻底规避该风险。</li>
 *   <li>{@code hazard_key} 是 GENERATED 列，只能读不能写。</li>
 * </ul>
 *
 * <p>{@code geom}（PostGIS geometry）未映射：本实体无空间查询需求，如需使用请
 * 通过 {@code HazardPoint} 实体或原生 SQL。
 *
 * <p>{@code longitude} / {@code latitude} 在库中是 {@code NUMERIC(10,6)}，
 * 本类用 {@link Double} 映射。WGS84 坐标 6 位小数约 0.1 米精度，double 的
 * 有效数字范围内无精度损失；如需严格保留 NUMERIC 语义可改 {@code BigDecimal}，
 * 但会改变 getter 返回类型，需同步排查调用方。
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

  // ---------------------------------------------------------------------------
  // hazard 领域字段（只读映射，写入走 HazardPoint 视图 → unified_event 表）
  // 目的：让事件中心/确认工作台通过本实体能读到灾害点业务详情。
  // 约束：全部 insertable=false, updatable=false，避免污染 WARNING/EMERGENCY 行。
  // ---------------------------------------------------------------------------

  /** 灾害点名称（VARCHAR(128)） */
  @Column(name = "name", length = 128, insertable = false, updatable = false)
  private String name;

  /** 灾害类型（英文码，如 landslide / collapse，VARCHAR(64)） */
  @Column(name = "hazard_type", length = 64, insertable = false, updatable = false)
  private String hazardType;

  /** 风险等级（中文存值，如"高风险"，VARCHAR(32)） */
  @Column(name = "risk_level", length = 32, insertable = false, updatable = false)
  private String riskLevel;

  /** 行政区划编码（VARCHAR(64)） */
  @Column(name = "region_code", length = 64, insertable = false, updatable = false)
  private String regionCode;

  /** 负责人（VARCHAR(128)） */
  @Column(name = "owner", length = 128, insertable = false, updatable = false)
  private String owner;

  /**
   * 灾害点状态（注意：hazard_point 视图把本列别名成 {@code status} 对外暴露，
   * 但 unified_event 表里的列名仍是 {@code hazard_status}）。
   */
  @Column(name = "hazard_status", length = 32, insertable = false, updatable = false)
  private String hazardStatus;

  /** 经度（WGS84，库中 NUMERIC(10,6)） */
  @Column(name = "longitude", insertable = false, updatable = false)
  private Double longitude;

  /** 纬度（WGS84，库中 NUMERIC(10,6)） */
  @Column(name = "latitude", insertable = false, updatable = false)
  private Double latitude;

  /** 威胁对象（库中 TEXT） */
  @Column(name = "threat_object", insertable = false, updatable = false)
  private String threatObject;

  /** 最近一次巡检时间（TIMESTAMPTZ） */
  @Column(name = "last_inspection_at", insertable = false, updatable = false)
  private OffsetDateTime lastInspectionAt;

  /** 灾害来源（注意：视图别名成 {@code source}，表列名仍是 {@code hazard_source}） */
  @Column(name = "hazard_source", length = 32, insertable = false, updatable = false)
  private String hazardSource;

  /** 发现时间（TIMESTAMPTZ） */
  @Column(name = "discovered_at", insertable = false, updatable = false)
  private OffsetDateTime discoveredAt;

  /** 发现置信度（0~1，AI 检测场景，DOUBLE PRECISION） */
  @Column(name = "detection_confidence", insertable = false, updatable = false)
  private Double detectionConfidence;

  /** 最近一次巡检的设备类型（VARCHAR(64)） */
  @Column(name = "last_inspection_device_type", length = 64, insertable = false, updatable = false)
  private String lastInspectionDeviceType;

  /** 历史预警次数（INTEGER NOT NULL DEFAULT 0） */
  @Column(name = "historical_count", insertable = false, updatable = false)
  private Integer historicalCount;

  /** 历史最高风险评分（DOUBLE PRECISION NOT NULL DEFAULT 0） */
  @Column(name = "historical_max_score", insertable = false, updatable = false)
  private Double historicalMaxScore;

  /** 暴露人口估算（BIGINT NOT NULL DEFAULT 0，用 Long） */
  @Column(name = "exposure_population", insertable = false, updatable = false)
  private Long exposurePopulation;

  /** 暴露建筑估算（INTEGER NOT NULL DEFAULT 0） */
  @Column(name = "exposure_buildings", insertable = false, updatable = false)
  private Integer exposureBuildings;

  /** 土地利用类型（VARCHAR(32) NOT NULL DEFAULT 'unknown'） */
  @Column(name = "land_use_type", length = 32, insertable = false, updatable = false)
  private String landUseType;

  /**
   * hazard 的稳定业务键（GENERATED 列，子表外键 {@code unified_event(hazard_key)} 指向它）。
   * 生成表达式：{@code CASE WHEN kind='HAZARD' THEN hazard_code ELSE NULL END}。
   * 只读；本实体不参与其生成。
   */
  @Column(name = "hazard_key", length = 64, insertable = false, updatable = false)
  private String hazardKey;

  // ---------------------------------------------------------------------------

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

  // hazard 领域字段：getter 齐全；不提供 setter（字段只读，写入走视图），避免误用。

  public String getName() { return name; }
  public String getHazardType() { return hazardType; }
  public String getRiskLevel() { return riskLevel; }
  public String getRegionCode() { return regionCode; }
  public String getOwner() { return owner; }
  public String getHazardStatus() { return hazardStatus; }
  public Double getLongitude() { return longitude; }
  public Double getLatitude() { return latitude; }
  public String getThreatObject() { return threatObject; }
  public OffsetDateTime getLastInspectionAt() { return lastInspectionAt; }
  public String getHazardSource() { return hazardSource; }
  public OffsetDateTime getDiscoveredAt() { return discoveredAt; }
  public Double getDetectionConfidence() { return detectionConfidence; }
  public String getLastInspectionDeviceType() { return lastInspectionDeviceType; }
  public Integer getHistoricalCount() { return historicalCount; }
  public Double getHistoricalMaxScore() { return historicalMaxScore; }
  public Long getExposurePopulation() { return exposurePopulation; }
  public Integer getExposureBuildings() { return exposureBuildings; }
  public String getLandUseType() { return landUseType; }
  public String getHazardKey() { return hazardKey; }

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