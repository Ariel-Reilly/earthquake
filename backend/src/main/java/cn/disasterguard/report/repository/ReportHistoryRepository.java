package cn.disasterguard.report.repository;

import cn.disasterguard.report.entity.ReportHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReportHistoryRepository extends JpaRepository<ReportHistory, Long> {

  @Query("select coalesce(max(e.id), 0) from ReportHistory e")
  long findMaxId();
}
