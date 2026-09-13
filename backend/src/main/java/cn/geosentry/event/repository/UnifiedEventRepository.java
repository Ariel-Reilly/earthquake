package cn.geosentry.event.repository;

import cn.geosentry.event.EventState;
import cn.geosentry.event.entity.UnifiedEventEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UnifiedEventRepository extends JpaRepository<UnifiedEventEntity, Long> {

  Optional<UnifiedEventEntity> findByEventCode(String eventCode);

  List<UnifiedEventEntity> findByState(EventState state);

  List<UnifiedEventEntity> findByHazardCode(String hazardCode);

  List<UnifiedEventEntity> findByWarningCode(String warningCode);

  List<UnifiedEventEntity> findByEmergencyCode(String emergencyCode);

  /** 当前活动事件（未进入终态） */
  List<UnifiedEventEntity> findByStateNotIn(List<EventState> terminalStates);

  /** 统计各状态数量 - 给 /event/stats 接口 */
  long countByState(EventState state);
}
