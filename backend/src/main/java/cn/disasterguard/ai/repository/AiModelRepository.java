package cn.disasterguard.ai.repository;

import cn.disasterguard.ai.entity.AiModel;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** AI 模型注册表数据访问，对应 {@code ai_model} 表。 */
@Repository
public interface AiModelRepository extends JpaRepository<AiModel, Long> {

  Optional<AiModel> findByModelCode(String modelCode);

  List<AiModel> findByOrderByIdAsc();

  List<AiModel> findByStatus(String status);

  List<AiModel> findByModelType(String modelType);
}
