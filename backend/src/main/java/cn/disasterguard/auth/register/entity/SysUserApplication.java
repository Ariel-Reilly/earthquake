package cn.disasterguard.auth.register.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 用户注册申请单。映射到 {@code sys_user_application} 表。
 *
 * <p>安全约定：
 * <ul>
 *   <li>{@code passwordHash} 与 {@code idCardEnc} 属于敏感字段，禁止出现在任何响应 DTO 中；</li>
 *   <li>身份证明文不落库，密文由 {@code SensitiveDataCipher} 生成，
 *       对外只暴露脱敏串 {@code idCardMask}；</li>
 *   <li>{@code status} 使用中文状态值（待审核/已通过/已拒绝），
 *       与 {@code data.sql} 种子数据中的业务口径保持一致。</li>
 * </ul>
 */
@Entity
@Table(name = "sys_user_application")
public class SysUserApplication {

  /** 申请状态：待审核。 */
  public static final String STATUS_PENDING = "待审核";
  /** 申请状态：已通过。 */
  public static final String STATUS_APPROVED = "已通过";
  /** 申请状态：已拒绝。 */
  public static final String STATUS_REJECTED = "已拒绝";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "username", nullable = false, length = 64)
  private String username;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(name = "real_name", nullable = false, length = 64)
  private String realName;

  @Column(name = "phone", nullable = false, length = 32)
  private String phone;

  @Column(name = "email", nullable = false, length = 128)
  private String email;

  @Column(name = "id_card_enc", nullable = false, length = 512)
  private String idCardEnc;

  @Column(name = "id_card_mask", nullable = false, length = 32)
  private String idCardMask;

  @Column(name = "department", nullable = false, length = 128)
  private String department;

  @Column(name = "position", length = 64)
  private String position;

  @Column(name = "role_code", nullable = false, length = 64)
  private String roleCode;

  @Column(name = "region_code", nullable = false, length = 64)
  private String regionCode;

  @Column(name = "apply_reason", nullable = false, columnDefinition = "text")
  private String applyReason;

  @Column(name = "invite_code", length = 64)
  private String inviteCode;

  @Column(name = "status", nullable = false, length = 32)
  private String status = STATUS_PENDING;

  @Column(name = "reject_reason", length = 255)
  private String rejectReason;

  @Column(name = "register_ip", nullable = false, length = 64)
  private String registerIp;

  @Column(name = "user_agent", length = 512)
  private String userAgent;

  @Column(name = "phone_verified", nullable = false)
  private boolean phoneVerified;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified;

  @Column(name = "reviewer", length = 64)
  private String reviewer;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
  public String getRealName() { return realName; }
  public void setRealName(String realName) { this.realName = realName; }
  public String getPhone() { return phone; }
  public void setPhone(String phone) { this.phone = phone; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getIdCardEnc() { return idCardEnc; }
  public void setIdCardEnc(String idCardEnc) { this.idCardEnc = idCardEnc; }
  public String getIdCardMask() { return idCardMask; }
  public void setIdCardMask(String idCardMask) { this.idCardMask = idCardMask; }
  public String getDepartment() { return department; }
  public void setDepartment(String department) { this.department = department; }
  public String getPosition() { return position; }
  public void setPosition(String position) { this.position = position; }
  public String getRoleCode() { return roleCode; }
  public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
  public String getRegionCode() { return regionCode; }
  public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
  public String getApplyReason() { return applyReason; }
  public void setApplyReason(String applyReason) { this.applyReason = applyReason; }
  public String getInviteCode() { return inviteCode; }
  public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getRejectReason() { return rejectReason; }
  public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
  public String getRegisterIp() { return registerIp; }
  public void setRegisterIp(String registerIp) { this.registerIp = registerIp; }
  public String getUserAgent() { return userAgent; }
  public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
  public boolean isPhoneVerified() { return phoneVerified; }
  public void setPhoneVerified(boolean phoneVerified) { this.phoneVerified = phoneVerified; }
  public boolean isEmailVerified() { return emailVerified; }
  public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
  public String getReviewer() { return reviewer; }
  public void setReviewer(String reviewer) { this.reviewer = reviewer; }
  public Instant getReviewedAt() { return reviewedAt; }
  public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
