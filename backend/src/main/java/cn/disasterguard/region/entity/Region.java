package cn.disasterguard.region.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 行政区划实体（v1.7.0）。
 *
 * <p>记录全国省-市-区-镇-村五级行政区划信息，核心用途是为
 * {@link cn.disasterguard.ai.scheduler.AiHazardMonitorScheduler} 提供监控锚点：
 * <ul>
 *   <li>center_lon / center_lat：AI 网格扫描的锚点经纬度（由运维配置，精度到镇/村级）</li>
 *   <li>geo_type：地貌类型（PLAIN / HILL / MOUNTAIN / COASTAL），决定网格步长和扫描权重</li>
 * </ul>
 *
 * <p>数据来源：可由运维通过 data.sql 扩展种子数据，或接入国家统计局/民政部行政区划接口。
 */
@Entity
@Table(name = "region")
public class Region {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "parent_id")
  private Long parentId;

  @Column(name = "region_code", nullable = false, unique = true)
  private String regionCode;

  @Column(name = "region_name", nullable = false)
  private String regionName;

  /**
   * 行政级别：province / city / district / town / village。
   * AI 实时监测的扫描粒度建议到 town/village 级别。
   */
  @Column(name = "region_level", nullable = false)
  private String regionLevel;

  /**
   * 层级路径，形如 "100000/100100/100101"（根节点/父节点/当前节点）。
   * 用于按 province/city 下级过滤。
   */
  @Column(name = "path")
  private String path;

  @Column(name = "sort_order")
  private Integer sortOrder = 0;

  /**
   * AI 网格扫描锚点经度（WGS-84）。
   * 运维根据当地实际坐标配置；未配置时回退默认值（105.0）。
   */
  @Column(name = "center_lon", nullable = false)
  private Double centerLon = 105.0;

  /**
   * AI 网格扫描锚点纬度（WGS-84）。
   * 运维根据当地实际坐标配置；未配置时回退默认值（35.0）。
   */
  @Column(name = "center_lat", nullable = false)
  private Double centerLat = 35.0;

  /**
   * 地貌类型：
   * <ul>
   *   <li>PLAIN    — 平原（低风险区，默认跳过，降低 AI 计算量）</li>
   *   <li>HILL     — 丘陵/低山（推荐步长 0.02°≈2.2km）</li>
   *   <li>MOUNTAIN — 山地（推荐步长 0.01°≈1.1km）</li>
   *   <li>COASTAL  — 沿海（推荐步长 0.015°≈1.6km）</li>
   * </ul>
   */
  @Column(name = "geo_type", nullable = false)
  private String geoType = "HILL";

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getParentId() { return parentId; }
  public void setParentId(Long parentId) { this.parentId = parentId; }
  public String getRegionCode() { return regionCode; }
  public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
  public String getRegionName() { return regionName; }
  public void setRegionName(String regionName) { this.regionName = regionName; }
  public String getRegionLevel() { return regionLevel; }
  public void setRegionLevel(String regionLevel) { this.regionLevel = regionLevel; }
  public String getPath() { return path; }
  public void setPath(String path) { this.path = path; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public Double getCenterLon() { return centerLon; }
  public void setCenterLon(Double centerLon) { this.centerLon = centerLon; }
  public Double getCenterLat() { return centerLat; }
  public void setCenterLat(Double centerLat) { this.centerLat = centerLat; }
  public String getGeoType() { return geoType; }
  public void setGeoType(String geoType) { this.geoType = geoType; }
}
