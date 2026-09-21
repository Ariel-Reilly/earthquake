package cn.disasterguard.emergency.repository;

import cn.disasterguard.emergency.entity.EmergencyPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmergencyPlanRepository extends JpaRepository<EmergencyPlan, Long> {

  @Query("select coalesce(max(e.id), 0) from EmergencyPlan e")
  long findMaxId();
}
