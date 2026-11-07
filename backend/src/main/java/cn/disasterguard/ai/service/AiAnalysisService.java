package cn.disasterguard.ai.service;

import cn.disasterguard.ai.entity.AiAnalysisResult;
import cn.disasterguard.ai.repository.AiAnalysisResultRepository;
import cn.disasterguard.ai.repository.AiModelRepository;
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
  private final AiModelRepository aiModelRepository;

  public AiAnalysisService(AiAnalysisResultRepository repository, ObjectMapper objectMapper,
                           AiModelRepository aiModelRepository) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.aiModelRepository = aiModelRepository;
  }

  /**
   * 把 AI 侧返回的模型标识归一化为 {@code ai_model.model_code}。
   *
   * <p>AI 服务返回的是<b>版本化型号</b>（如 {@code xgboost-fusion-v3}），而
   * {@code ai_analysis_result.model_name} 有外键指向 {@code ai_model(model_code)}，
   * 注册表里登记的是<b>逻辑模型编码</b>（{@code xgboost_fusion}）。
   * 直接落库会违反 fk_ai_result_model，导致 saveRecord 抛异常，
   * 进而让「AI 融合评分」整条链路被判定为不可用、退化成 RULE_ONLY。
   *
   * <p>归一化规则：小写 + 连字符转下划线 + 去掉尾部 {@code _v<N>} 版本后缀。
   * 仍未登记时返回 {@code null}（该列可空，ON DELETE SET NULL），
   * 记录本身照常写入，原始型号保留在 result_json 里。
   */
  private String resolveModelCode(String model) {
    if (model == null || model.isBlank()) {
      return null;
    }
    if (aiModelRepository.findByModelCode(model).isPresent()) {
      return model;
    }
    String normalized = model.toLowerCase().replace('-', '_').replaceAll("_v\\d+$", "");
    if (!normalized.equals(model) && aiModelRepository.findByModelCode(normalized).isPresent()) {
      return normalized;
    }
    log.warn("AI 模型 [{}] 未在 ai_model 登记，分析记录不关联模型（原始型号保留在 result_json）", model);
    return null;
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
    String resolvedModel = resolveModelCode(modelName);
    // R10 P2-12：模型未登记时 model_name 只能写 NULL，溯源信息就丢了。
    // 把原始型号写进 result_json 的 _modelName 字段，页面/排查时仍能看到是哪个版本产出的。
    if (resolvedModel == null && modelName != null && !modelName.isBlank()) {
      json = annotateRawModel(json, modelName);
    }
    entity.setModelName(resolvedModel);
    entity.setResultJson(json);
    entity.setConfidence(extractConfidence(result));
    entity.setCreatedAt(OffsetDateTime.now());
    return toDto(repository.save(entity));
  }

  @Transactional
  public void delete(Long id) {
    repository.deleteById(id);
  }

  /**
   * 把原始模型型号写进结果 JSON 的 {@code _modelName} 字段（P2-12）。
   *
   * <p>只在 {@code model_name} 外键列填不上时才调用，属于"外键失败但不丢溯源"的兜底。
   * 结果不是 JSON 对象时原样返回，不为了塞字段破坏原始内容。
   */
  private String annotateRawModel(String json, String modelName) {
    try {
      var node = objectMapper.readTree(json);
      if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
        obj.put("_modelName", modelName);
        return objectMapper.writeValueAsString(obj);
      }
    } catch (Exception e) {
      log.debug("结果 JSON 无法附加 _modelName: {}", e.getMessage());
    }
    return json;
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
