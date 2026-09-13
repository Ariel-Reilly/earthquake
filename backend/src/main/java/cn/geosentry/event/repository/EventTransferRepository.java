package cn.geosentry.event.repository;

import cn.geosentry.event.entity.EventTransfer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventTransferRepository extends JpaRepository<EventTransfer, Long> {
  List<EventTransfer> findByEventId(String eventId);
}
