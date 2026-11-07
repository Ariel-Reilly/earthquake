package cn.disasterguard.device.repository;

import cn.disasterguard.device.entity.DeviceMetric;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceMetricRepository extends JpaRepository<DeviceMetric, Long> {

  /** 各设备各指标的最近一条记录（供规则引擎取最新值）。 */
  @Query(value = """
      SELECT DISTINCT ON (device_code, metric_code) *
      FROM device_metric
      WHERE device_code IN (:deviceCodes)
      ORDER BY device_code, metric_code, recorded_at DESC
      """, nativeQuery = true)
  List<DeviceMetric> findLatestByDeviceCodes(@Param("deviceCodes") List<String> deviceCodes);

  /**
   * 各设备各指标的最近一条记录，但只在 {@code staleMinutes} 分钟内的数据才算数。
   *
   * <p>规则引擎专用：{@link #findLatestByDeviceCodes} 没有时间窗口，设备停机或
   * 换场景后，几小时甚至几天前的读数仍会被当作「当前值」参与阈值研判，
   * 造成陈旧数据误报（例如二次演示前未清理，一启动就复用上一场的高水位）。
   *
   * @param staleMinutes 新鲜度窗口（分钟），必须为正数
   */
  @Query(value = """
      SELECT DISTINCT ON (device_code, metric_code) *
      FROM device_metric
      WHERE device_code IN (:deviceCodes)
        AND recorded_at > NOW() - make_interval(mins => :staleMinutes)
      ORDER BY device_code, metric_code, recorded_at DESC
      """, nativeQuery = true)
  List<DeviceMetric> findLatestByDeviceCodesSince(@Param("deviceCodes") List<String> deviceCodes,
                                                 @Param("staleMinutes") int staleMinutes);

  /**
   * 某灾害点下各指标的最新一条记录（跨该点所有设备）。
   *
   * <p>用于 AI 反馈提交时自动附带真实特征快照，让"人工复核结论"能沉淀成训练样本。
   */
  @Query(value = """
      SELECT DISTINCT ON (metric_code) *
      FROM device_metric
      WHERE hazard_code = :hazardCode
      ORDER BY metric_code, recorded_at DESC
      """, nativeQuery = true)
  List<DeviceMetric> findLatestByHazardCode(@Param("hazardCode") String hazardCode);

  List<DeviceMetric> findByDeviceCodeAndMetricCodeOrderByRecordedAtDesc(String deviceCode, String metricCode);

  /** 获取最近 N 条指标值（按时间升序），供 AI 时序预测。 */
  @Query(value = """
      SELECT m.value FROM device_metric m
      WHERE m.device_code = :deviceCode
        AND m.metric_code = :metricCode
      ORDER BY m.recorded_at DESC
      LIMIT :limit
      """, nativeQuery = true)
  List<Double> findRecentValues(@Param("deviceCode") String deviceCode,
                                @Param("metricCode") String metricCode,
                                @Param("limit") int limit);

  /** 清理早于 cutoff 的指标样本（保留最近 N 天），避免 device_metric 无限膨胀。 */
  @Modifying
  @Query("DELETE FROM DeviceMetric m WHERE m.recordedAt < :cutoff")
  int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff);

  /**
   * 聚合某设备某指标在指定时间窗口内的累计值（用于 rainfall_24h / rainfall_7d 真实计算）。
   */
  @Query(value = "SELECT SUM(m.value) FROM device_metric m "
      + "WHERE m.device_code = :deviceCode AND m.metric_code = :metricCode "
      + "AND m.recorded_at >= NOW() - (:windowHours || ' hours')::interval",
      nativeQuery = true)
  Double sumRecentHours(@Param("deviceCode") String deviceCode,
                        @Param("metricCode") String metricCode,
                        @Param("windowHours") int windowHours);

  /**
   * 聚合某灾害点下所有关联设备某指标的累计值（跨设备 SUM）。
   */
  @Query(value = "SELECT SUM(m.value) FROM device_metric m "
      + "JOIN monitoring_device d ON d.device_code = m.device_code "
      + "WHERE d.hazard_code = :hazardCode AND m.metric_code = :metricCode "
      + "AND m.recorded_at >= NOW() - (:windowHours || ' hours')::interval",
      nativeQuery = true)
  Double sumByHazardRecentHours(@Param("hazardCode") String hazardCode,
                                @Param("metricCode") String metricCode,
                                @Param("windowHours") int windowHours);

  /**
   * 按月统计设备指标上报量：返回 [yyyy-mm, count]。
   * 直接在 DB 侧聚合，避免将全表加载到 JVM。
   */
  @Query(value = "SELECT TO_CHAR(recorded_at, 'YYYY-MM') AS ym, COUNT(*) AS cnt "
      + "FROM device_metric WHERE recorded_at IS NOT NULL GROUP BY 1 ORDER BY 1", nativeQuery = true)
  List<Object[]> countByMonth();
}
