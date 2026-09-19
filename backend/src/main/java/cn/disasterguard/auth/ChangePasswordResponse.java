package cn.disasterguard.auth;

public record ChangePasswordResponse(
    String username,
    boolean changed
) {
}
