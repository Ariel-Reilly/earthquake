package cn.disasterguard.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** 统一操作审计日志。由 {@code OperationLogAspect} AOP 切面自动写入。 */
@Entity
@Table(name = "operation_log")
public class OperationLog {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String operator;

  @Column(name = "module_name", nullable = false)
  private String moduleName;

  @Column(name = "action_name", nullable = false)
  private String actionName;

  @Column(name = "request_method")
  private String requestMethod;

  @Column(name = "request_path")
  private String requestPath;

  @Column(name = "request_body", columnDefinition = "TEXT")
  private String requestBody;

  @Column(name = "result_status")
  private String resultStatus;

  @Column(name = "cost_ms")
  private Long costMs;

  @Column(name = "ip_address")
  private String ipAddress;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getOperator() { return operator; }
  public void setOperator(String operator) { this.operator = operator; }
  public String getModuleName() { return moduleName; }
  public void setModuleName(String moduleName) { this.moduleName = moduleName; }
  public String getActionName() { return actionName; }
  public void setActionName(String actionName) { this.actionName = actionName; }
  public String getRequestMethod() { return requestMethod; }
  public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }
  public String getRequestPath() { return requestPath; }
  public void setRequestPath(String requestPath) { this.requestPath = requestPath; }
  public String getRequestBody() { return requestBody; }
  public void setRequestBody(String requestBody) { this.requestBody = requestBody; }
  public String getResultStatus() { return resultStatus; }
  public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }
  public Long getCostMs() { return costMs; }
  public void setCostMs(Long costMs) { this.costMs = costMs; }
  public String getIpAddress() { return ipAddress; }
  public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
