package cn.disasterguard.auth.dto;
public record LoginResponse(
    String token,
    UserProfile user
) {
  public record UserProfile(String name, String role, String username) {}
}
