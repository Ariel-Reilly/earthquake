package cn.disasterguard.task.repository;

import cn.disasterguard.task.entity.InspectionTask;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface InspectionTaskRepository extends JpaRepository<InspectionTask, Long>, JpaSpecificationExecutor<InspectionTask> {
  boolean existsByTaskCode(String taskCode);
  InspectionTask findByTaskCode(String taskCode);

  /** 按状态计数，避免 findAll + stream count。 */
  long countByStatus(String status);

  /** 按月统计已完成巡检任务数：返回 [yyyy-mm, count]。 */
  @Query(value = "SELECT TO_CHAR(start_time, 'YYYY-MM') AS ym, COUNT(*) AS cnt "
      + "FROM inspection_task WHERE status = '已完成' AND start_time IS NOT NULL GROUP BY 1 ORDER BY 1", nativeQuery = true)
  List<Object[]> countDoneByMonth();
}
