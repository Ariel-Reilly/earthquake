package cn.disasterguard.governance.repository;

import cn.disasterguard.governance.entity.DataSyncTask;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 数据同步任务数据访问，对应 {@code data_sync_task} 表。 */
@Repository
public interface DataSyncTaskRepository extends JpaRepository<DataSyncTask, Long> {

  List<DataSyncTask> findByOrderByIdAsc();

  List<DataSyncTask> findByLastStatusNot(String lastStatus);
}
