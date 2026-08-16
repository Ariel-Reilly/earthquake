package cn.geosentry.device.repository;

import cn.geosentry.device.entity.DeviceMetric;
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
}
