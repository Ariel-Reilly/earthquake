package cn.disasterguard.audit.repository;

import cn.disasterguard.audit.entity.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
}
