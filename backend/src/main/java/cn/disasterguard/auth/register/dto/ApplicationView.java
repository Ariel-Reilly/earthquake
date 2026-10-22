package cn.disasterguard.auth.register.dto;

import java.time.Instant;

/**
 * 管理端可见的注册申请视图。
 *
 * <p>安全约束：<b>不得</b>包含 {@code passwordHash} 与 {@code idCardEnc}，
 * 身份证只暴露脱敏串 {@code idCardMask}。
 *
 * @param id            申请 ID
 * @param username     用户名
 * @param realName     姓名
 * @param phone        手机号
 * @param email        邮箱
 * @param idCardMask   身份证脱敏串
 * @param department   部门
 * @param position     岗位
 * @param roleCode     申请角色
 * @param regionCode   区域编码
 * @param applyReason  申请理由
 * @param inviteCode   使用的邀请码
 * @param status       申请状态
 * @param rejectReason 拒绝原因
 * @param registerIp   提交来源 IP
 * @param userAgent    提交来源 User-Agent
 * @param phoneVerified 手机是否已验证
 * @param emailVerified 邮箱是否已验证
 * @param reviewer     审核人
 * @param reviewedAt   审核时间
 * @param createdAt    提交时间
 */
public record ApplicationView(
    Long id,
    String username,
    String realName,
    String phone,
    String email,
    String idCardMask,
    String department,
    String position,
    String roleCode,
    String regionCode,
    String applyReason,
    String inviteCode,
    String status,
    String rejectReason,
    String registerIp,
    String userAgent,
    boolean phoneVerified,
    boolean emailVerified,
    String reviewer,
    Instant reviewedAt,
    Instant createdAt
) {
}
