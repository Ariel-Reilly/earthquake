package cn.geosentry.task.repository;

import cn.geosentry.task.entity.InspectionTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InspectionTaskRepository extends JpaRepository<InspectionTask, Long>, JpaSpecificationExecutor<InspectionTask> {
  boolean existsByTaskCode(String taskCode);
}
