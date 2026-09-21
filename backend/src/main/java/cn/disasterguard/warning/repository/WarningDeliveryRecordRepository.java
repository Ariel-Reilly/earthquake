package cn.disasterguard.warning.repository;

import cn.disasterguard.warning.entity.WarningDeliveryRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** 预警送达记录数据访问（行表，按预警 id 分组查询）。 */
@Repository
public interface WarningDeliveryRecordRepository
    extends JpaRepository<WarningDeliveryRecord, Long> {

  /** 按预警 id 取全部送达记录，按记录 id 升序（与原内存 List 顺序一致）。 */
  List<WarningDeliveryRecord> findByRecordIdOrderByIdAsc(Long recordId);

  @Query("select coalesce(max(e.id), 0) from WarningDeliveryRecord e")
  long findMaxId();
}
