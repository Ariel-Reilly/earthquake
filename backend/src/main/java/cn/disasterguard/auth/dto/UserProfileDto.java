package cn.disasterguard.auth.dto;
import java.time.OffsetDateTime;

/**
 * 用户资料 DTO。
 */
public record UserProfileDto(
    Long id,
    String username,
    String name,
    String phone,
    String email,
    String department,
    String avatar,
    String role,
    OffsetDateTime updatedAt
) {}
