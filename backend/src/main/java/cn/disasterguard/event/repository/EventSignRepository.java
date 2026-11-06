package cn.disasterguard.event.repository;

import cn.disasterguard.event.entity.EventSign;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSignRepository extends JpaRepository<EventSign, Long> {
  List<EventSign> findByEventId(String eventId);
  List<EventSign> findBySignId(String signId);
}
