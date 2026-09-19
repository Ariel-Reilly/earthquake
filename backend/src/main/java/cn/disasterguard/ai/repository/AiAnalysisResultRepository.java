package cn.disasterguard.ai.repository;

import cn.disasterguard.ai.entity.AiAnalysisResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiAnalysisResultRepository extends JpaRepository<AiAnalysisResult, Long> {
  List<AiAnalysisResult> findByBusinessTypeAndBusinessCodeOrderByCreatedAtDesc(String businessType, String businessCode);
}
