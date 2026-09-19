package cn.disasterguard.inspection.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "inspection_record")
public class InspectionRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "record_code", nullable = false, unique = true)
  private String recordCode;

  @Column(name = "task_code", nullable = false)
  private String taskCode;

  @Column(name = "hazard_code", nullable = false)
  private String hazardCode;

  @Column(nullable = false)
  private String inspector;

  @Column(name = "inspect_at", nullable = false)
  private OffsetDateTime inspectAt;

  @Column(name = "sign_in_lon", precision = 10, scale = 6)
  private BigDecimal signInLon;

  @Column(name = "sign_in_lat", precision = 10, scale = 6)
  private BigDecimal signInLat;

  /** EPSG:4326 空间点，由应用层从 sign_in_lon/sign_in_lat 生成（GeometryFactory） */
  @Column(name = "geom", columnDefinition = "geometry(Point,4326)")
  private Point geom;

  @Column(name = "distance_m", precision = 10, scale = 2)
  private BigDecimal distanceM;

  @Column(name = "abnormal_flag", nullable = false)
  private Boolean abnormalFlag;

  @Column(name = "inspection_result")
  private String inspectionResult;

  @Column(name = "note")
  private String note;

  @Column(name = "photo_ids")
  private String photoIds;

  @Column(name = "created_at")
  private OffsetDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getRecordCode() { return recordCode; }
  public void setRecordCode(String recordCode) { this.recordCode = recordCode; }
  public String getTaskCode() { return taskCode; }
  public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public String getInspector() { return inspector; }
  public void setInspector(String inspector) { this.inspector = inspector; }
  public OffsetDateTime getInspectAt() { return inspectAt; }
  public void setInspectAt(OffsetDateTime inspectAt) { this.inspectAt = inspectAt; }
  public BigDecimal getSignInLon() { return signInLon; }
  public void setSignInLon(BigDecimal signInLon) { this.signInLon = signInLon; }
  public BigDecimal getSignInLat() { return signInLat; }
  public void setSignInLat(BigDecimal signInLat) { this.signInLat = signInLat; }
  public Point getGeom() { return geom; }
  public void setGeom(Point geom) { this.geom = geom; }
  public BigDecimal getDistanceM() { return distanceM; }
  public void setDistanceM(BigDecimal distanceM) { this.distanceM = distanceM; }
  public Boolean getAbnormalFlag() { return abnormalFlag; }
  public void setAbnormalFlag(Boolean abnormalFlag) { this.abnormalFlag = abnormalFlag; }
  public String getInspectionResult() { return inspectionResult; }
  public void setInspectionResult(String inspectionResult) { this.inspectionResult = inspectionResult; }
  public String getNote() { return note; }
  public void setNote(String note) { this.note = note; }
  public String getPhotoIds() { return photoIds; }
  public void setPhotoIds(String photoIds) { this.photoIds = photoIds; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
