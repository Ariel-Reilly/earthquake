package cn.geosentry.notification.repository;

import cn.geosentry.notification.entity.NotificationRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, Long> {

  Page<NotificationRecord> findByBizType(String bizType, Pageable pageable);

  Page<NotificationRecord> findByBizId(String bizId, Pageable pageable);

  @Query("SELECT r FROM NotificationRecord r WHERE " +
         "(:bizType IS NULL OR r.bizType = :bizType) AND " +
         "(:disasterType IS NULL OR r.disasterType = :disasterType) AND " +
         "(:level IS NULL OR r.level = :level) AND " +
         "(:status IS NULL OR r.status = :status)")
  Page<NotificationRecord> findByFilters(
      @Param("bizType") String bizType,
      @Param("disasterType") String disasterType,
      @Param("level") String level,
      @Param("status") String status,
      Pageable pageable);

  List<NotificationRecord> findByBizIdAndStatus(String bizId, String status);

  long countByStatus(String status);

  long countByBizTypeAndStatus(String bizType, String status);
}
