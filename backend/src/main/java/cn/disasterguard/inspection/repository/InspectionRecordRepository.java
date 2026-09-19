package cn.disasterguard.inspection.repository;

import cn.disasterguard.inspection.entity.InspectionRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Long> {
  List<InspectionRecord> findByTaskCodeOrderByCreatedAtDesc(String taskCode);
  List<InspectionRecord> findByHazardCodeOrderByCreatedAtDesc(String hazardCode);
}
