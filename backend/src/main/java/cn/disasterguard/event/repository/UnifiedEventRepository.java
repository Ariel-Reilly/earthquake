package cn.disasterguard.event.repository;

import cn.disasterguard.event.EventState;
import cn.disasterguard.event.entity.UnifiedEventEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UnifiedEventRepository extends JpaRepository<UnifiedEventEntity, Long> {

  Optional<UnifiedEventEntity> findByEventCode(String eventCode);

  List<UnifiedEventEntity> findByState(EventState state);

  List<UnifiedEventEntity> findByHazardCode(String hazardCode);

  /**
   * 批量按 hazardCode 列表查 unified_event，避免 N+1。
   * 返回的列表可能包含同一 hazardCode 多条（一个灾害点可能关联多个事件），调用方负责去重/取首条。
   */
  List<UnifiedEventEntity> findByHazardCodeIn(Collection<String> hazardCodes);

  List<UnifiedEventEntity> findByWarningCode(String warningCode);

  List<UnifiedEventEntity> findByEmergencyCode(String emergencyCode);

  /** 当前活动事件（未进入终态） */
  List<UnifiedEventEntity> findByStateNotIn(List<EventState> terminalStates);

  /** 统计各状态数量 - 给 /event/stats 接口 */
  long countByState(EventState state);
}
