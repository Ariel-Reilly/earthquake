package cn.disasterguard.auth.dto;
public record ChangePasswordResponse(
    String username,
    boolean changed
) {
}
