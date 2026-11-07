package cn.disasterguard.warning.repository;

import cn.disasterguard.warning.entity.WarningRule;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarningRuleRepository extends JpaRepository<WarningRule, Long> {
  List<WarningRule> findByEnabledTrue();
  boolean existsByRuleCode(String ruleCode);
  long countByEnabled(boolean enabled);

  /** R10 P1-5：规则管理页与预警引擎共用同一套 rule_code，按编号精确查找。 */
  Optional<WarningRule> findByRuleCode(String ruleCode);

  /** 列表页一次性取回全部生效规则的表达式，避免逐行查询。 */
  List<WarningRule> findByRuleCodeIn(Collection<String> ruleCodes);
}
