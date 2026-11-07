package cn.disasterguard.warning.repository;

import cn.disasterguard.warning.entity.WarningEvent;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WarningEventRepository extends JpaRepository<WarningEvent, Long>, JpaSpecificationExecutor<WarningEvent> {
  boolean existsByWarningCode(String warningCode);

  /** 按隐患编码列出预警，按触发时间倒序（供隐患详情页关联展示）。 */
  List<WarningEvent> findByHazardCodeOrderByTriggeredAtDesc(String hazardCode);

  /**
   * 批量查询：一次性取出指定规则+灾害点组合中所有未关闭预警。
   * NULL-safe：status 为 NULL 时也视作"未关闭"（SQL 中 NULL NOT IN (...) 结果是 NULL，必须显式处理）。
   */
  @Query("SELECT w FROM WarningEvent w WHERE w.ruleCode IN :ruleCodes "
      + "AND w.hazardCode IN :hazardCodes "
      + "AND (w.status IS NULL OR w.status NOT IN :excludedStatuses)")
  List<WarningEvent> findOpenWarnings(@Param("ruleCodes") List<String> ruleCodes,
                                      @Param("hazardCodes") List<String> hazardCodes,
                                      @Param("excludedStatuses") List<String> excludedStatuses);

  /** 活跃预警计数：状态为 NULL 或不等于终态的都计入（NULL-safe）。 */
  @Query("SELECT COUNT(w) FROM WarningEvent w WHERE w.status IS NULL OR w.status NOT IN :excludedStatuses")
  long countActiveWarnings(@Param("excludedStatuses") List<String> excludedStatuses);

  /**
   * R10 P1-4：规则再次命中同一条未关闭预警时累加复现次数并刷新最近命中时间。
   *
   * <p>用批量 UPDATE 而不是先查后改，避免规则引擎每轮把整行实体加载进一级缓存。
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query("UPDATE WarningEvent w SET w.occurCount = COALESCE(w.occurCount, 0) + 1, "
      + "w.lastSeenAt = :now WHERE w.id = :id")
  int bumpOccurrence(@Param("id") Long id, @Param("now") OffsetDateTime now);

  /**
   * R10 P1-4：找出「超过静默窗口仍未再命中」的未关闭预警，用于自动解除。
   *
   * <p>只处理系统生成的预警（source 为空或非 MANUAL）：人工上报的预警必须由人确认/解除，
   * 否则现场报上来的风险会被系统悄悄关掉。
   */
  @Query("SELECT w FROM WarningEvent w WHERE (w.status IS NULL OR w.status NOT IN :excludedStatuses) "
      + "AND (w.source IS NULL OR w.source <> 'MANUAL') "
      + "AND COALESCE(w.lastSeenAt, w.triggeredAt) < :cutoff")
  List<WarningEvent> findStaleOpenWarnings(@Param("excludedStatuses") List<String> excludedStatuses,
                                           @Param("cutoff") OffsetDateTime cutoff);

  /** 活跃预警预览：排除已关闭/已忽略/已解除等终态，按触发时间倒序（NULL-safe）。 */
  @Query("SELECT w FROM WarningEvent w WHERE w.status IS NULL OR w.status NOT IN :excludedStatuses "
      + "ORDER BY w.triggeredAt DESC")
  List<WarningEvent> findOpenWarningsOrderByTriggeredAtDesc(@Param("excludedStatuses") List<String> excludedStatuses);

  /** 按等级分组计数：返回 [warning_level, count]，值为「红色预警」这类中文等级。 */
  @Query(value = "SELECT COALESCE(NULLIF(warning_level, ''), '未分级') AS level, COUNT(*) AS cnt "
      + "FROM warning_event GROUP BY 1", nativeQuery = true)
  List<Object[]> countByLevel();

  /**
   * 按状态分组计数：返回 [status, count]，值为「待确认 / 处理中 / 已关闭」这类中文状态。
   * 注意与 {@link #countByLevel()} 区分——此前 stats() 误用等级分组去取状态计数，导致恒为 0。
   */
  @Query(value = "SELECT COALESCE(NULLIF(status, ''), '未知') AS st, COUNT(*) AS cnt "
      + "FROM warning_event GROUP BY 1", nativeQuery = true)
  List<Object[]> countByStatus();

  /** 按月统计预警触发数：返回 [yyyy-mm, count]，时间范围由调用方决定。 */
  @Query(value = "SELECT TO_CHAR(triggered_at, 'YYYY-MM') AS ym, COUNT(*) AS cnt "
      + "FROM warning_event WHERE triggered_at IS NOT NULL GROUP BY 1 ORDER BY 1", nativeQuery = true)
  List<Object[]> countByMonth();
}
