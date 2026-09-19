package cn.disasterguard.hazard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "hazard_point")
public class HazardPoint {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "hazard_code", nullable = false, unique = true)
  private String hazardCode;

  @Column(nullable = false)
  private String name;

  @Column(name = "hazard_type", nullable = false)
  private String hazardType;

  @Column(name = "risk_level", nullable = false)
  private String riskLevel;

  @Column(name = "region_code")
  private String regionCode;

  @Column(name = "owner")
  private String owner;

  @Column(nullable = false)
  private String status;

  @Column(name = "longitude", nullable = false)
  private Double longitude;

  @Column(name = "latitude", nullable = false)
  private Double latitude;

  /** EPSG:4326 空间点，由应用层从 longitude/latitude 生成（GeometryFactory） */
  @Column(name = "geom", columnDefinition = "geometry(Point,4326)")
  private Point geom;

  @Column(name = "threat_object")
  private String threatObject;

  @Column(name = "last_inspection_at")
  private OffsetDateTime lastInspectionAt;

  /**
   * 数据来源：
   *   AI_MONITOR —— 由 AiHazardMonitorScheduler 实时监测产出（v1.6.0+ 默认）
   *   MANUAL     —— 用户/管理员手动新增
   *   DEMO       —— 历史种子数据（已废弃，留给存量兼容）
   */
  @Column(name = "source", nullable = false)
  private String source = "AI_MONITOR";

  /** AI 首次识别时间（仅 AI_MONITOR 来源有值）。用于实时列表按"刚发现"排序。 */
  @Column(name = "discovered_at")
  private OffsetDateTime discoveredAt;

  /** AI 评估置信度 0.0-1.0，前端展示用。 */
  @Column(name = "detection_confidence")
  private Double detectionConfidence;

  // ---- v2.0 新增字段 ----

  /** 上次巡检设备类型（camera / drone / sentinel2 / landsat / radar 等）。 */
  @Column(name = "last_inspection_device_type")
  private String lastInspectionDeviceType;

  /** 该灾害点历史灾害事件总数。用于 AI 历史灾情维度（historical_count）。 */
  @Column(name = "historical_count")
  private Integer historicalCount = 0;

  /** 历史最大灾害评分（0-100）。用于 AI 历史灾情维度（historical_max_score）。 */
  @Column(name = "historical_max_score")
  private Double historicalMaxScore = 0.0;

  /** 承灾人口数（人）。用于 AI 承灾体评估。 */
  @Column(name = "exposure_population")
  private Long exposurePopulation = 0L;

  /** 承灾建筑物数量（栋）。用于 AI 承灾体评估。 */
  @Column(name = "exposure_buildings")
  private Integer exposureBuildings = 0;

  /** 土地利用类型（urban / cropland / forest / water / barren / unknown）。用于 AI 承灾体评估。 */
  @Column(name = "land_use_type")
  private String landUseType = "unknown";

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getHazardType() { return hazardType; }
  public void setHazardType(String hazardType) { this.hazardType = hazardType; }
  public String getRiskLevel() { return riskLevel; }
  public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
  public String getRegionCode() { return regionCode; }
  public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
  public String getOwner() { return owner; }
  public void setOwner(String owner) { this.owner = owner; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Double getLongitude() { return longitude; }
  public void setLongitude(Double longitude) { this.longitude = longitude; }
  public Double getLatitude() { return latitude; }
  public void setLatitude(Double latitude) { this.latitude = latitude; }
  public Point getGeom() { return geom; }
  public void setGeom(Point geom) { this.geom = geom; }
  public String getThreatObject() { return threatObject; }
  public void setThreatObject(String threatObject) { this.threatObject = threatObject; }
  public OffsetDateTime getLastInspectionAt() { return lastInspectionAt; }
  public void setLastInspectionAt(OffsetDateTime lastInspectionAt) { this.lastInspectionAt = lastInspectionAt; }
  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }
  public OffsetDateTime getDiscoveredAt() { return discoveredAt; }
  public void setDiscoveredAt(OffsetDateTime discoveredAt) { this.discoveredAt = discoveredAt; }
  public Double getDetectionConfidence() { return detectionConfidence; }
  public void setDetectionConfidence(Double detectionConfidence) { this.detectionConfidence = detectionConfidence; }
  public String getLastInspectionDeviceType() { return lastInspectionDeviceType; }
  public void setLastInspectionDeviceType(String lastInspectionDeviceType) { this.lastInspectionDeviceType = lastInspectionDeviceType; }
  public Integer getHistoricalCount() { return historicalCount; }
  public void setHistoricalCount(Integer historicalCount) { this.historicalCount = historicalCount; }
  public Double getHistoricalMaxScore() { return historicalMaxScore; }
  public void setHistoricalMaxScore(Double historicalMaxScore) { this.historicalMaxScore = historicalMaxScore; }
  public Long getExposurePopulation() { return exposurePopulation; }
  public void setExposurePopulation(Long exposurePopulation) { this.exposurePopulation = exposurePopulation; }
  public Integer getExposureBuildings() { return exposureBuildings; }
  public void setExposureBuildings(Integer exposureBuildings) { this.exposureBuildings = exposureBuildings; }
  public String getLandUseType() { return landUseType; }
  public void setLandUseType(String landUseType) { this.landUseType = landUseType; }
}
