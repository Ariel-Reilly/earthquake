package cn.disasterguard.common.api;

public record ApiPageRequest(
    int page,
    int size,
    String keyword,
    String regionCode,
    String status
) {
  public int safePage() {
    return Math.max(page, 1);
  }

  public int safeSize() {
    return Math.min(Math.max(size, 1), 200);
  }
}
