package cn.disasterguard.auth.register.dto;

import java.time.Instant;

/**
 * 申请状态查询结果（申请人自查用）。
 *
 * @param status       申请状态
 * @param rejectReason 拒绝原因（仅已拒绝时有值）
 * @param createdAt   提交时间
 */
public record RegisterStatusView(
    String status,
    String rejectReason,
    Instant createdAt
) {
}
