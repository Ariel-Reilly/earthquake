package cn.geosentry.ruleengine.repository;

import cn.geosentry.ruleengine.entity.Rule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RuleRepository extends JpaRepository<Rule, Long> {

  Optional<Rule> findByRuleCode(String ruleCode);

  List<Rule> findByEnabled(Boolean enabled);

  @Query("SELECT r FROM Rule r WHERE " +
         "(:disasterType IS NULL OR r.disasterType = :disasterType) AND " +
         "(:level IS NULL OR r.level = :level) AND " +
         "(:enabled IS NULL OR r.enabled = :enabled) " +
         "ORDER BY r.priority DESC, r.createdAt DESC")
  List<Rule> findByFilters(
      @Param("disasterType") String disasterType,
      @Param("level") String level,
      @Param("enabled") Boolean enabled);

  List<Rule> findByDisasterTypeAndEnabled(String disasterType, Boolean enabled);

  @Query("SELECT r FROM Rule r WHERE " +
         "r.enabled = true " +
         "ORDER BY r.priority DESC, r.createdAt ASC")
  List<Rule> findAllEnabledOrderByPriority();
}
