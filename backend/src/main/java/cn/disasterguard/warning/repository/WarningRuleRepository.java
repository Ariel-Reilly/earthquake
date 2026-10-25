package cn.disasterguard.warning.repository;

import cn.disasterguard.warning.entity.WarningRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarningRuleRepository extends JpaRepository<WarningRule, Long> {
  List<WarningRule> findByEnabledTrue();
  boolean existsByRuleCode(String ruleCode);
  long countByEnabled(boolean enabled);
}
