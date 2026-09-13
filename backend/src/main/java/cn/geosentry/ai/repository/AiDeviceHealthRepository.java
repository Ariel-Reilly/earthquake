package cn.geosentry.ai.repository;

import cn.geosentry.ai.entity.AiDeviceHealth;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiDeviceHealthRepository extends JpaRepository<AiDeviceHealth, Long> {
  Optional<AiDeviceHealth> findByDeviceCode(String deviceCode);

  /** 按健康分升序（异常在前），给设备列表/排行用。 */
  List<AiDeviceHealth> findAllByOrderByHealthScoreAsc();

  /** 取所有 health_level 在白名单的设备，给概览/统计用。 */
  List<AiDeviceHealth> findByHealthLevelIn(List<String> healthLevels);

  long countByHealthLevel(String healthLevel);
}
