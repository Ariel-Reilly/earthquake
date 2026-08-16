package cn.geosentry.warning.repository;

import cn.geosentry.warning.entity.WarningEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WarningEventRepository extends JpaRepository<WarningEvent, Long>, JpaSpecificationExecutor<WarningEvent> {
  boolean existsByWarningCode(String warningCode);

  /** 同一规则 + 同一灾害点，是否存在未闭环预警（状态 != 已关闭）。 */
  boolean existsByRuleCodeAndHazardCodeAndStatusNot(String ruleCode, String hazardCode, String status);
}
