package cn.disasterguard.ai.service;

import cn.disasterguard.ai.entity.AiAnalysisResult;
import cn.disasterguard.ai.repository.AiAnalysisResultRepository;
import cn.disasterguard.ai.dto.AiAnalysisRecordDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 结果持久化服务。
 *
 * <p>AI 推理服务不可用或未启用时，记录告警日志并返回空结果，
 * 保证主流程（巡检上报、预警等）不受影响（降级策略）。
 */
@Service
public class AiAnalysisService {

  private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

  private final AiAnalysisResultRepository repository;
  private final ObjectMapper objectMapper;

  public AiAnalysisService(AiAnalysisResultRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  public List<AiAnalysisRecordDto> list(String businessType, String businessCode) {
    return repository.findByBusinessTypeAndBusinessCodeOrderByCreatedAtDesc(businessType, businessCode)
        .stream().map(this::toDto).toList();
  }

  @Transactional
  public AiAnalysisRecordDto saveRecord(String businessType, String businessCode, String modelName, Object result) {
    String json;
    try {
      json = objectMapper.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      log.warn("序列化 AI 结果失败，使用 toString 兜底: {}", e.getMessage());
      json = String.valueOf(result);
    }
    AiAnalysisResult entity = new AiAnalysisResult();
    entity.setBusinessType(businessType);
    entity.setBusinessCode(businessCode);
    entity.setModelName(modelName);
    entity.setResultJson(json);
    entity.setConfidence(extractConfidence(result));
    entity.setCreatedAt(OffsetDateTime.now());
    return toDto(repository.save(entity));
  }

  @Transactional
  public void delete(Long id) {
    repository.deleteById(id);
  }

  private Double extractConfidence(Object result) {
    if (result == null) {
      return null;
    }
    try {
      var node = objectMapper.valueToTree(result);
      var confidence = node.get("confidence");
      return confidence == null || confidence.isNull() ? null : confidence.asDouble();
    } catch (Exception e) {
      return null;
    }
  }

  private AiAnalysisRecordDto toDto(AiAnalysisResult r) {
    return new AiAnalysisRecordDto(
        r.getId(), r.getBusinessType(), r.getBusinessCode(),
        r.getModelName(), r.getResultJson(), r.getConfidence(), r.getCreatedAt()
    );
  }
}
