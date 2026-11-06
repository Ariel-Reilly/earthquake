package cn.disasterguard.emergency.repository;

import cn.disasterguard.emergency.entity.EmergencyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EmergencyEventRepository extends JpaRepository<EmergencyEvent, Long>, JpaSpecificationExecutor<EmergencyEvent> {
  boolean existsByWarningCodeAndStatusNot(String warningCode, String status);
}
