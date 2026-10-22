package cn.disasterguard.auth.register.dto;

/**
 * 管理端申请列表查询条件。
 *
 * @param status  申请状态过滤（待审核/已通过/已拒绝），可空
 * @param keyword 关键字，模糊匹配 username / realName / phone，可空
 * @param page    页码，从 1 开始
 * @param size    每页条数
 */
public record ApplicationQuery(
    String status,
    String keyword,
    int page,
    int size
) {
  /** 页码下限保护，避免非法负数导致 JPA 分页异常。 */
  public int safePage() {
    return Math.max(page, 1);
  }

  /** 每页条数上限保护，避免一次性拉取过多数据。 */
  public int safeSize() {
    return Math.min(Math.max(size, 1), 200);
  }
}
