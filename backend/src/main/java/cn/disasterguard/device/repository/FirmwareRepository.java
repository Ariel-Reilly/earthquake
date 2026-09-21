package cn.disasterguard.device.repository;

import cn.disasterguard.device.entity.Firmware;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 固件数据访问（String 主键，无自增）。 */
@Repository
public interface FirmwareRepository extends JpaRepository<Firmware, String> {}
