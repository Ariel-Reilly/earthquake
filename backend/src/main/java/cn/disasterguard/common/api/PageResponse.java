package cn.disasterguard.common.api;

import java.util.List;

/**
 * 统一分页响应信封。所有 Controller 分页接口均返回此结构。
 *
 * <p>历史上存在 {@code PageResponse}（含 pages）与 {@code PageResponse}（不含 pages）
 * 两个 record 造成前端兼容负担；现已统一为含 pages 的版本。
 *
 * @param records 当前页数据
 * @param total   总记录数
 * @param page    当前页（从 1 开始）
 * @param size    每页大小
 * @param pages   总页数
 */
public record PageResponse<T>(
    List<T> records,
    long total,
    int page,
    int size,
    int pages
) {
  /** 便捷工厂：从 Spring Data Page 生成。 */
  public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
    return new PageResponse<>(
        page.getContent(),
        page.getTotalElements(),
        page.getNumber() + 1,
        page.getSize(),
        page.getTotalPages()
    );
  }
}
