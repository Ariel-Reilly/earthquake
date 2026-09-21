package cn.disasterguard.report.repository;

import cn.disasterguard.report.entity.ReportTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, Long> {

  @Query("select coalesce(max(e.id), 0) from ReportTemplate e")
  long findMaxId();
}
