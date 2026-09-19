package cn.disasterguard.notification.repository;

import cn.disasterguard.notification.entity.NotificationRecord;
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

  /**
   * 多维过滤查询。
   *
   * <p>过滤参数：
   * <ul>
   *   <li>{@code bizType} — 业务类型（精确匹配）</li>
   *   <li>{@code disasterType} — 灾害类型（精确匹配）</li>
   *   <li>{@code level} — 严重等级（精确匹配）</li>
   *   <li>{@code status} — 通知原始状态（精确匹配，例如 SENT / FAILED / PENDING），
   *       与 {@code readFilter} 同时只生效一个：先按 status 精确匹配，再回退 readFilter</li>
   *   <li>{@code readFilter} — true 仅已读 / false 仅未读 / null 不过滤；
   *       与 {@code status} 同时给出时以 {@code status} 为准</li>
   * </ul>
   */
  @Query(value = "SELECT r FROM NotificationRecord r WHERE " +
         "(:bizType IS NULL OR r.bizType = :bizType) AND " +
         "(:disasterType IS NULL OR r.disasterType = :disasterType) AND " +
         "(:level IS NULL OR r.level = :level) AND " +
         "(:status IS NOT NULL OR :readFilter IS NULL OR " +
         "  (:readFilter = TRUE  AND r.status = 'READ') OR " +
         "  (:readFilter = FALSE AND r.status <> 'READ')) AND " +
         "(:status IS NULL OR r.status = :status)",
         countQuery = "SELECT COUNT(r) FROM NotificationRecord r WHERE " +
         "(:bizType IS NULL OR r.bizType = :bizType) AND " +
         "(:disasterType IS NULL OR r.disasterType = :disasterType) AND " +
         "(:level IS NULL OR r.level = :level) AND " +
         "(:status IS NOT NULL OR :readFilter IS NULL OR " +
         "  (:readFilter = TRUE  AND r.status = 'READ') OR " +
         "  (:readFilter = FALSE AND r.status <> 'READ')) AND " +
         "(:status IS NULL OR r.status = :status)")
  Page<NotificationRecord> findByFilters(
      @Param("bizType") String bizType,
      @Param("disasterType") String disasterType,
      @Param("level") String level,
      @Param("readFilter") Boolean readFilter,
      @Param("status") String status,
      Pageable pageable);

  List<NotificationRecord> findByBizIdAndStatus(String bizId, String status);

  long countByStatus(String status);

  long countByBizTypeAndStatus(String bizType, String status);
}
