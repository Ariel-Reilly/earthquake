package cn.disasterguard.event.repository;

import cn.disasterguard.event.entity.EventTransfer;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventTransferRepository extends JpaRepository<EventTransfer, Long> {
  List<EventTransfer> findByEventId(String eventId);

  /**
   * 批量查询多个事件的转派记录。
   *
   * <p>待确认队列要按事件批量判断"是否已转派"，逐个调用 findByEventId 会产生 N+1 查询。
   *
   * @param eventIds 统一事件号集合
   * @return 这些事件的全部转派记录（按时间升序）
   */
  List<EventTransfer> findByEventIdIn(Collection<String> eventIds);
}
