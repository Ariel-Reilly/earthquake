package cn.disasterguard.device.repository;

import cn.disasterguard.device.entity.DeviceTrajectory;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceTrajectoryRepository extends JpaRepository<DeviceTrajectory, Long> {

  /**
   * 单设备轨迹查询（按时间范围，按 recorded_at 升序，方便回放渲染）。
   *
   * @param deviceCode 设备编码
   * @param startTime  起始时间（含），null 表示不限
   * @param endTime    截止时间（含），null 表示不限
   * @param maxPoints  最大返回点数，超过则均匀采样（用 LIMIT + 按时间分组实现）
   */
  @Query(value = "SELECT * FROM device_trajectory " +
                 "WHERE device_code = :deviceCode " +
                 "  AND (:startTime IS NULL OR recorded_at >= :startTime) " +
                 "  AND (:endTime   IS NULL OR recorded_at <= :endTime) " +
                 "ORDER BY recorded_at ASC " +
                 "LIMIT :maxPoints",
         nativeQuery = true)
  List<DeviceTrajectory> findTrajectory(@Param("deviceCode") String deviceCode,
                                        @Param("startTime") OffsetDateTime startTime,
                                        @Param("endTime") OffsetDateTime endTime,
                                        @Param("maxPoints") int maxPoints);

  /**
   * 批量设备最新位置（最近一条采样点，用于地图初始加载）。
   * 取每台设备的最近一条 recorded_at 记录。
   */
  @Query(value = "SELECT DISTINCT ON (device_code) " +
                 "  device_code, longitude, latitude, speed_kmh, heading_deg, " +
                 "  battery_level, source, recorded_at " +
                 "FROM device_trajectory " +
                 "WHERE recorded_at >= :since " +
                 "ORDER BY device_code, recorded_at DESC",
         nativeQuery = true)
  List<Object[]> findLatestPositionsSince(@Param("since") OffsetDateTime since);

  /** 删除指定时间之前的轨迹（用于定时清理过期数据，可选）。 */
  long deleteByRecordedAtBefore(OffsetDateTime cutoff);

  /** 统计某设备的轨迹点数量。 */
  long countByDeviceCode(String deviceCode);
}
