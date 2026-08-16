package cn.geosentry.auth;

public record ChangePasswordResponse(
    String username,
    boolean changed
) {
}
