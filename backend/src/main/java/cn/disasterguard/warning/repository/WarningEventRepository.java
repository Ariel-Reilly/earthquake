package cn.disasterguard.warning.repository;

import cn.disasterguard.warning.entity.WarningEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarningEventRepository extends JpaRepository<WarningEvent, Long>, JpaSpecificationExecutor<WarningEvent> {
  boolean existsByWarningCode(String warningCode);

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

  /** 活跃预警预览：排除已关闭/已忽略/已解除等终态，按触发时间倒序（NULL-safe）。 */
  @Query("SELECT w FROM WarningEvent w WHERE w.status IS NULL OR w.status NOT IN :excludedStatuses "
      + "ORDER BY w.triggeredAt DESC")
  List<WarningEvent> findOpenWarningsOrderByTriggeredAtDesc(@Param("excludedStatuses") List<String> excludedStatuses);

  /** 按等级分组计数：返回 [warning_level, count]。 */
  @Query(value = "SELECT COALESCE(NULLIF(warning_level, ''), '未分级') AS level, COUNT(*) AS cnt "
      + "FROM warning_event GROUP BY 1", nativeQuery = true)
  List<Object[]> countByLevel();

  /** 按月统计预警触发数：返回 [yyyy-mm, count]，时间范围由调用方决定。 */
  @Query(value = "SELECT TO_CHAR(triggered_at, 'YYYY-MM') AS ym, COUNT(*) AS cnt "
      + "FROM warning_event WHERE triggered_at IS NOT NULL GROUP BY 1 ORDER BY 1", nativeQuery = true)
  List<Object[]> countByMonth();
}
