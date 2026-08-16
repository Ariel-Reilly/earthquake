package cn.geosentry.device.repository;

import cn.geosentry.device.entity.MonitoringDevice;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MonitoringDeviceRepository extends JpaRepository<MonitoringDevice, Long>, JpaSpecificationExecutor<MonitoringDevice> {
  boolean existsByDeviceCode(String deviceCode);
  MonitoringDevice findByDeviceCode(String deviceCode);
  /** 查找所有当前标记为在线但最后上报时间早于给定阈值的设备（支持分页分批）。 */
  List<MonitoringDevice> findByOnlineStatusAndLastReportAtBefore(String onlineStatus, OffsetDateTime cutoff, Pageable pageable);
}
