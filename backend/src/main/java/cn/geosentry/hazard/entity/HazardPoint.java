package cn.geosentry.hazard.entity;

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
}
