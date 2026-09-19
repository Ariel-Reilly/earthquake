package cn.disasterguard.device.repository;

import cn.disasterguard.device.entity.MonitoringDevice;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface MonitoringDeviceRepository extends JpaRepository<MonitoringDevice, Long>, JpaSpecificationExecutor<MonitoringDevice> {
  boolean existsByDeviceCode(String deviceCode);
  MonitoringDevice findByDeviceCode(String deviceCode);
  /** 按灾害点查关联设备列表（用于 AI 融合因子聚合）。 */
  List<MonitoringDevice> findByHazardCode(String hazardCode);
  /** 查找所有当前标记为在线但最后上报时间早于给定阈值或从未上报的设备（支持分页分批）。 */
  @Query("SELECT d FROM MonitoringDevice d WHERE d.onlineStatus = :onlineStatus AND (d.lastReportAt IS NULL OR d.lastReportAt < :cutoff)")
  List<MonitoringDevice> findOfflineCandidates(@org.springframework.data.repository.query.Param("onlineStatus") String onlineStatus,
                                                @org.springframework.data.repository.query.Param("cutoff") OffsetDateTime cutoff,
                                                Pageable pageable);

  /** 轻量查询：只投影 deviceCode 和 hazardCode 两列，避免全量实体加载。 */
  @Query("SELECT d.deviceCode, d.hazardCode FROM MonitoringDevice d")
  List<Object[]> findDeviceCodeHazardCodePairs();

  /** 统计指定在线状态的设备数（避免 findAll + stream count）。 */
  long countByOnlineStatus(String onlineStatus);
}
