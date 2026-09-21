package cn.disasterguard.device.repository;

import cn.disasterguard.device.entity.OtaTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** OTA 升级任务数据访问（String 主键，无自增）。 */
@Repository
public interface OtaTaskRepository extends JpaRepository<OtaTask, String> {}
