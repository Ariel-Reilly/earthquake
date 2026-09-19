package cn.disasterguard.device.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** 设备上报的指标样本。 */
@Entity
@Table(name = "device_metric")
public class DeviceMetric {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "device_code", nullable = false)
  private String deviceCode;

  @Column(name = "hazard_code")
  private String hazardCode;

  @Column(name = "metric_code", nullable = false)
  private String metricCode;

  @Column(nullable = false)
  private Double value;

  @Column(name = "recorded_at", nullable = false)
  private OffsetDateTime recordedAt;

  @Column(name = "raw_payload")
  private String rawPayload;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getDeviceCode() { return deviceCode; }
  public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public String getMetricCode() { return metricCode; }
  public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
  public Double getValue() { return value; }
  public void setValue(Double value) { this.value = value; }
  public OffsetDateTime getRecordedAt() { return recordedAt; }
  public void setRecordedAt(OffsetDateTime recordedAt) { this.recordedAt = recordedAt; }
  public String getRawPayload() { return rawPayload; }
  public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
}
