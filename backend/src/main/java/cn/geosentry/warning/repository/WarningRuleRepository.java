package cn.geosentry.warning.repository;

import cn.geosentry.warning.entity.WarningRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarningRuleRepository extends JpaRepository<WarningRule, Long> {
  List<WarningRule> findByEnabledTrue();
  boolean existsByRuleCode(String ruleCode);
}
