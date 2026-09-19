package cn.disasterguard.ruleengine.repository;

import cn.disasterguard.ruleengine.entity.RuleExecutionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RuleExecutionLogRepository extends JpaRepository<RuleExecutionLog, Long> {

  Page<RuleExecutionLog> findByRuleIdOrderByExecutedAtDesc(Long ruleId, Pageable pageable);

  Page<RuleExecutionLog> findAllByOrderByExecutedAtDesc(Pageable pageable);
}
