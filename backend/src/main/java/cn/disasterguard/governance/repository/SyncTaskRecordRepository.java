package cn.disasterguard.governance.repository;

import cn.disasterguard.governance.entity.SyncTaskRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 数据同步任务数据访问（String 主键，无自增）。 */
@Repository
public interface SyncTaskRecordRepository extends JpaRepository<SyncTaskRecord, String> {

  /** 列表页与种子数据一致的确定性排序（SYNC-001, SYNC-002, ...）。 */
  List<SyncTaskRecord> findByOrderByIdAsc();
}
