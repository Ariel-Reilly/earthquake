package cn.disasterguard.warning.repository;

import cn.disasterguard.warning.entity.RiskScoreLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskScoreLogRepository extends JpaRepository<RiskScoreLog, Long> {
}
