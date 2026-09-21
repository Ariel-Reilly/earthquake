package cn.disasterguard.report.repository;

import cn.disasterguard.report.entity.ReportSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReportSubscriptionRepository extends JpaRepository<ReportSubscription, Long> {

  @Query("select coalesce(max(e.id), 0) from ReportSubscription e")
  long findMaxId();
}
