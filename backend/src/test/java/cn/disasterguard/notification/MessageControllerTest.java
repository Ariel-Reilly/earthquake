package cn.disasterguard.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.notification.entity.NotificationRecord;
import cn.disasterguard.notification.repository.NotificationRecordRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

/**
 * 锁定消息列表端点的两个修复：
 *
 * <p>① total 取数据库计算的全局匹配数（{@code Page#getTotalElements()}），
 *    不再用单页内容做内存二次过滤后产生的"当前页条数"，否则分页器和红点统计都会错；
 * ② keyword 通过 {@code findByFilters} 下推到 DB 层 LIKE 匹配，不在内存里再过滤。
 */
@DisplayName("MessageController — 消息列表过滤与分页")
class MessageControllerTest {

  private NotificationRecordRepository repo;
  private MessageController controller;

  @BeforeEach
  void setUp() {
    repo = mock(NotificationRecordRepository.class);
    controller = new MessageController(repo);
  }

  @Test
  @DisplayName("total 取全局匹配数，而非单页过滤后条数")
  void total_uses_global_count() {
    NotificationRecord r1 = record(1L, "暴雨橙色预警");
    NotificationRecord r2 = record(2L, "设备离线提醒");
    // 单页只返回 2 条，但数据库里全局匹配 137 条
    Page<NotificationRecord> page = mock(Page.class);
    when(page.getContent()).thenReturn(List.of(r1, r2));
    when(page.getTotalElements()).thenReturn(137L);
    when(page.getTotalPages()).thenReturn(7);
    when(repo.findByFilters(any(), any(), any(), any(), any(), any(), any())).thenReturn(page);

    ApiResponse<Map<String, Object>> resp =
        controller.list(1, 20, null, null, null, null, null, null, null);
    Map<String, Object> body = resp.data();

    assertEquals(137, body.get("total"), "total 应为全局匹配数");
    assertEquals(7, body.get("pages"), "pages 应基于全局 total 计算");
    assertEquals(2, ((List<?>) body.get("items")).size());
  }

  @Test
  @DisplayName("keyword 下推到 findByFilters 的 LIKE 匹配，而非内存二次过滤")
  void keyword_pushed_to_repository() {
    Page<NotificationRecord> page = mock(Page.class);
    when(page.getContent()).thenReturn(List.of(record(9L, "地震速报")));
    when(page.getTotalElements()).thenReturn(1L);
    when(repo.findByFilters(any(), any(), any(), any(), any(), any(), any())).thenReturn(page);

    controller.list(1, 20, null, null, null, null, null, null, "地震");

    // findByFilters 第 5 个形参即 keyword；验证确实以非 null 形式传入
    verify(repo).findByFilters(any(), any(), any(), any(), eq("地震"), any(), any());
  }

  private NotificationRecord record(Long id, String title) {
    NotificationRecord r = new NotificationRecord();
    r.setId(id);
    r.setTitle(title);
    r.setStatus("SENT");
    return r;
  }
}
