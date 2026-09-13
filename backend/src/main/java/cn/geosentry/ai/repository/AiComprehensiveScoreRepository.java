package cn.geosentry.ai.repository;

import cn.geosentry.ai.entity.AiComprehensiveScore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiComprehensiveScoreRepository extends JpaRepository<AiComprehensiveScore, Long> {
  Optional<AiComprehensiveScore> findByHazardCode(String hazardCode);

  /** 按分数倒序取 TOP N（不传 offset/limit 时仍按 Pageable 翻页）。 */
  @Query("SELECT a FROM AiComprehensiveScore a ORDER BY a.riskScore DESC, a.analyzedAt DESC")
  List<AiComprehensiveScore> findAllOrderByScoreDesc(Pageable pageable);

  /** 按等级筛（用于按等级分组）。 */
  List<AiComprehensiveScore> findByRiskLevel(String riskLevel);

  /** 取指定等级之上的所有 hazard（用于联动预警/巡检）。 */
  @Query("SELECT a FROM AiComprehensiveScore a WHERE a.riskLevel IN :levels")
  List<AiComprehensiveScore> findByRiskLevelIn(@Param("levels") List<String> levels);
}
