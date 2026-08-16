package cn.geosentry.warning.repository;

import cn.geosentry.warning.entity.RiskScoreLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskScoreLogRepository extends JpaRepository<RiskScoreLog, Long> {
}
