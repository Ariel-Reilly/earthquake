package cn.disasterguard.ruleengine;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.notification.dto.NotificationRequestDto;
import cn.disasterguard.notification.NotificationRouter;
import cn.disasterguard.ruleengine.dto.*;
import cn.disasterguard.ruleengine.entity.FieldDict;
import cn.disasterguard.ruleengine.entity.Rule;
import cn.disasterguard.ruleengine.entity.RuleExecutionLog;
import cn.disasterguard.ruleengine.repository.FieldDictRepository;
import cn.disasterguard.ruleengine.repository.RuleExecutionLogRepository;
import cn.disasterguard.ruleengine.repository.RuleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 规则引擎服务
 */
@Service
public class RuleEngineService {

  private static final Logger log = LoggerFactory.getLogger(RuleEngineService.class);

  private final RuleRepository ruleRepository;
  private final RuleExecutionLogRepository logRepository;
  private final FieldDictRepository fieldDictRepository;
  private final RuleCompiler compiler;
  private final RuleEvaluator evaluator;
  private final NotificationRouter notificationRouter;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher eventPublisher;

  // 缓存已编译的规则
  private volatile Map<Long, RuleCompiler.ConditionNode> compiledRules = new HashMap<>();

  public RuleEngineService(
      RuleRepository ruleRepository,
      RuleExecutionLogRepository logRepository,
      FieldDictRepository fieldDictRepository,
      RuleCompiler compiler,
      RuleEvaluator evaluator,
      NotificationRouter notificationRouter,
      ObjectMapper objectMapper,
      ApplicationEventPublisher eventPublisher) {
    this.ruleRepository = ruleRepository;
    this.logRepository = logRepository;
    this.fieldDictRepository = fieldDictRepository;
    this.compiler = compiler;
    this.evaluator = evaluator;
    this.notificationRouter = notificationRouter;
    this.objectMapper = objectMapper;
    this.eventPublisher = eventPublisher;

    // 启动时加载所有规则
    reloadRules();
  }

  /**
   * 规则变更事件监听
   */
  @org.springframework.context.event.EventListener
  public void onRuleChanged(RuleChangedEvent event) {
    log.info("规则变更，刷新规则缓存");
    reloadRules();
  }

  /**
   * 重新加载所有规则
   */
  public void reloadRules() {
    List<Rule> enabledRules = ruleRepository.findAllEnabledOrderByPriority();
    Map<Long, RuleCompiler.ConditionNode> newCache = new HashMap<>();

    for (Rule rule : enabledRules) {
      try {
        RuleCompiler.ConditionNode node = null;

        // 优先使用 JSON 条件
        if (rule.getConditions() != null && !rule.getConditions().isBlank()) {
          RuleDto.RuleConditions conditions = objectMapper.readValue(
              rule.getConditions(),
              RuleDto.RuleConditions.class);
          node = compiler.buildConditionTree(conditions);
        }
        // 其次使用 DSL 文本
        else if (rule.getDslText() != null && !rule.getDslText().isBlank()) {
          node = compiler.parseDsl(rule.getDslText());
        }

        if (node != null) {
          newCache.put(rule.getId(), node);
        }
      } catch (Exception e) {
        log.error("编译规则失败: {} - {}", rule.getName(), e.getMessage());
      }
    }

    this.compiledRules = Collections.unmodifiableMap(newCache);
    log.info("规则缓存已更新，共加载 {} 条启用规则", newCache.size());
  }

  /**
   * 获取规则列表
   */
  public PageResponse<RuleDto> list(String disasterType, String level, Boolean enabled, int page, int size) {
    List<Rule> rules = ruleRepository.findByFilters(disasterType, level, enabled);
    int total = rules.size();

    int from = (page - 1) * size;
    int to = Math.min(from + size, total);
    List<Rule> pageRules = from < total ? rules.subList(from, to) : List.of();

    List<RuleDto> dtos = pageRules.stream().map(this::toDto).toList();

    return new PageResponse<>(dtos, total, page, size, (total + size - 1) / size);
  }

  /**
   * 获取规则详情
   */
  public RuleDto getById(Long id) {
    Rule rule = ruleRepository.findById(id)
        .orElseThrow(() -> new RuntimeException("规则不存在: " + id));
    return toDto(rule);
  }

  /**
   * 创建规则
   */
  @Transactional
  public RuleDto create(RuleCreateRequest request, String operator) {
    Rule rule = new Rule();
    rule.setRuleCode(generateRuleCode());
    applyRequest(rule, request);
    rule.setCreatedBy(operator);
    rule = ruleRepository.save(rule);

    // 刷新缓存
    reloadRules();

    // 发布变更事件
    publishRuleChanged(rule.getId(), "CREATED");

    return toDto(rule);
  }

  /**
   * 更新规则
   */
  @Transactional
  public RuleDto update(Long id, RuleCreateRequest request) {
    Rule rule = ruleRepository.findById(id)
        .orElseThrow(() -> new RuntimeException("规则不存在: " + id));

    applyRequest(rule, request);
    rule.setVersion(rule.getVersion() + 1);
    rule.setUpdatedAt(OffsetDateTime.now());
    rule = ruleRepository.save(rule);

    // 刷新缓存
    reloadRules();

    // 发布变更事件
    publishRuleChanged(rule.getId(), "UPDATED");

    return toDto(rule);
  }

  /**
   * 删除规则
   */
  @Transactional
  public void delete(Long id) {
    if (!ruleRepository.existsById(id)) {
      throw new RuntimeException("规则不存在: " + id);
    }
    ruleRepository.deleteById(id);

    // 刷新缓存
    reloadRules();

    // 发布变更事件
    publishRuleChanged(id, "DELETED");
  }

  /**
   * 启用/停用规则
   */
  @Transactional
  public RuleDto toggle(Long id) {
    Rule rule = ruleRepository.findById(id)
        .orElseThrow(() -> new RuntimeException("规则不存在: " + id));

    rule.setEnabled(!rule.getEnabled());
    rule.setUpdatedAt(OffsetDateTime.now());
    rule = ruleRepository.save(rule);

    // 刷新缓存
    reloadRules();

    // 发布变更事件
    publishRuleChanged(rule.getId(), rule.getEnabled() ? "ENABLED" : "DISABLED");

    return toDto(rule);
  }

  /**
   * 测试规则
   */
  public RuleTestResponse test(RuleTestRequest request, Map<String, Object> inputData) {
    RuleCompiler.ConditionNode node = null;
    String ruleName = "测试规则";
    Rule rule = null;

    if (request.ruleId() != null) {
      rule = ruleRepository.findById(request.ruleId())
          .orElseThrow(() -> new RuntimeException("规则不存在: " + request.ruleId()));
      ruleName = rule.getName();
      node = compiledRules.get(request.ruleId());

      if (node == null && rule.getConditions() != null) {
        try {
          RuleDto.RuleConditions conditions = objectMapper.readValue(
              rule.getConditions(), RuleDto.RuleConditions.class);
          node = compiler.buildConditionTree(conditions);
        } catch (Exception e) {
          log.error("解析规则条件失败: {}", e.getMessage());
        }
      }
    } else if (request.conditions() != null) {
      node = compiler.buildConditionTree(toCanonicalConditions(request.conditions()));
    }

    if (node == null) {
      return new RuleTestResponse(false, "未找到可评估的条件", List.of(), List.of(), List.of());
    }

    // 评估条件
    RuleEvaluator.EvaluationResult evalResult = evaluator.evaluate(node, inputData);

    List<RuleTestResponse.RuleActionResult> actionResults = List.of();
    if (evalResult.passed()) {
      // 执行动作并收集每个动作的结果
      actionResults = executeActionsForTest(rule, null, null);
    }

    return new RuleTestResponse(
        evalResult.passed(),
        evalResult.message(),
        evalResult.matchedConditions(),
        evalResult.failedConditions(),
        actionResults
    );
  }

  /**
   * 评估数据并触发规则
   */
  public void evaluateAndTrigger(Map<String, Object> data, String disasterType, String level) {
    List<Rule> rules = ruleRepository.findByDisasterTypeAndEnabled(disasterType, true);

    for (Rule rule : rules) {
      RuleCompiler.ConditionNode node = compiledRules.get(rule.getId());
      if (node == null) continue;

      RuleEvaluator.EvaluationResult result = evaluator.evaluate(node, data);

      // 记录执行日志
      logExecution(rule.getId(), result, data);

      if (result.passed()) {
        log.info("规则触发: {} - {}", rule.getName(), result.message());
        executeActions(rule, disasterType, level);
      }
    }
  }

  /**
   * 获取执行日志
   */
  public PageResponse<RuleExecutionLogDto> logs(Long ruleId, int page, int size) {
    Page<RuleExecutionLog> logPage;
    if (ruleId != null) {
      logPage = logRepository.findByRuleIdOrderByExecutedAtDesc(ruleId, PageRequest.of(page - 1, size));
    } else {
      logPage = logRepository.findAllByOrderByExecutedAtDesc(PageRequest.of(page - 1, size));
    }

    // 获取规则名称映射
    Map<Long, String> ruleNames = ruleRepository.findAll().stream()
        .collect(HashMap::new, (m, r) -> m.put(r.getId(), r.getName()), Map::putAll);

    List<RuleExecutionLogDto> dtos = logPage.getContent().stream()
        .map(log -> toLogDto(log, ruleNames.get(log.getRuleId())))
        .toList();

    return new PageResponse<>(dtos, logPage.getTotalElements(), page, size, logPage.getTotalPages());
  }

  /**
   * 获取字段字典
   */
  public List<FieldDictDto> getFieldDicts() {
    return fieldDictRepository.findAllByOrderByField().stream()
        .map(this::toFieldDictDto)
        .toList();
  }

  /**
   * 回滚规则到指定版本
   */
  @Transactional
  public RuleDto rollback(Long id, Integer targetVersion) {
    // TODO: 实现版本回滚逻辑
    throw new UnsupportedOperationException("版本回滚功能待实现");
  }

  // ===== 私有方法 =====

  private void applyRequest(Rule rule, RuleCreateRequest request) {
    rule.setName(request.name());
    rule.setDisasterType(request.disasterType());
    rule.setLevel(request.level());
    rule.setPriority(request.priority() != null ? request.priority() : 0);
    rule.setEnabled(request.enabled() != null ? request.enabled() : true);

    // 序列化条件
    if (request.conditions() != null) {
      try {
        rule.setConditions(objectMapper.writeValueAsString(request.conditions()));
        // 同时生成 DSL 文本
        RuleCompiler.ConditionNode node = compiler.buildConditionTree(toCanonicalConditions(request.conditions()));
        rule.setDslText(compiler.toDsl(node));
      } catch (Exception e) {
        log.error("序列化条件失败: {}", e.getMessage());
      }
    }

    // 序列化动作
    if (request.actions() != null) {
      try {
        rule.setActions(objectMapper.writeValueAsString(request.actions()));
      } catch (Exception e) {
        log.error("序列化动作失败: {}", e.getMessage());
      }
    }

    rule.setDslText(request.dslText());
  }

  private void executeActions(Rule rule, String disasterType, String level) {
    if (rule == null || rule.getActions() == null) {
      return;
    }

    try {
      List<RuleDto.RuleAction> actions = objectMapper.readValue(
          rule.getActions(),
          new TypeReference<List<RuleDto.RuleAction>>() {});

      for (RuleDto.RuleAction action : actions) {
        executeAction(action, rule.getName(), disasterType, level);
      }
    } catch (Exception e) {
      log.error("执行动作失败: {}", e.getMessage());
    }
  }

  /**
   * 用于规则测试：执行规则动作并返回每个动作的执行结果（成功/失败/原因），不抛出。
   * 当 rule 或 rule.getActions() 为空时返回空列表，调用方据此判断是否需要结果。
   */
  private List<RuleTestResponse.RuleActionResult> executeActionsForTest(Rule rule, String disasterType, String level) {
    List<RuleTestResponse.RuleActionResult> results = new ArrayList<>();
    if (rule == null || rule.getActions() == null) {
      return results;
    }
    List<RuleDto.RuleAction> actions;
    try {
      actions = objectMapper.readValue(
          rule.getActions(),
          new TypeReference<List<RuleDto.RuleAction>>() {});
    } catch (Exception e) {
      log.error("解析规则动作失败: {}", e.getMessage());
      return results;
    }
    for (RuleDto.RuleAction action : actions) {
      try {
        executeAction(action, rule.getName(), disasterType, level);
        results.add(new RuleTestResponse.RuleActionResult(action.type(), true, "执行成功", null));
      } catch (Exception e) {
        log.warn("测试执行动作失败: {} - {}", action.type(), e.getMessage());
        results.add(new RuleTestResponse.RuleActionResult(action.type(), false, "执行失败", e.getMessage()));
      }
    }
    return results;
  }

  /**
   * 把请求 DTO（RuleTestRequest.RuleConditions / RuleCreateRequest.RuleConditions 等）的条件
   * 规范化成 {@link RuleDto.RuleConditions}（编译器只接受这一种形状）。
   * 借助 Jackson 在两种 record 之间做结构等价转换，避免给编译器写三套重载。
   */
  private RuleDto.RuleConditions toCanonicalConditions(Object conditions) {
    return objectMapper.convertValue(conditions, RuleDto.RuleConditions.class);
  }

  private void executeAction(RuleDto.RuleAction action, String ruleName, String disasterType, String level) {
    String type = action.type();
    Map<String, Object> params = action.params();

    switch (type) {
      case "SEND_NOTIFICATION" -> {
        String templateId = params != null ? (String) params.get("template") : null;
        sendNotification(ruleName, disasterType, level, templateId);
      }
      case "CREATE_EVENT" -> {
        log.info("动作: 创建事件 - {}", ruleName);
      }
      case "PUSH_CONFIRM" -> {
        log.info("动作: 推送人工确认 - {}", ruleName);
      }
      default -> {
        log.warn("未知动作类型: {}", type);
      }
    }
  }

  private void sendNotification(String ruleName, String disasterType, String level, String templateId) {
    NotificationRequestDto request = new NotificationRequestDto(
        "WARNING",
        null,
        disasterType,
        level,
        "【" + level + "预警】规则 " + ruleName + " 触发",
        "监测数据满足预警条件，请及时处理。",
        List.of("admin"),
        null,
        List.of("SMS", "APP"),
        templateId,
        "HIGH",
        Map.of("ruleName", ruleName)
    );
    notificationRouter.route(request, "system");
  }

  private void logExecution(Long ruleId, RuleEvaluator.EvaluationResult result, Map<String, Object> data) {
    try {
      RuleExecutionLog logEntry = new RuleExecutionLog();
      logEntry.setRuleId(ruleId);
      logEntry.setTriggered(result.passed());
      logEntry.setInputData(objectMapper.writeValueAsString(data));
      logEntry.setResult(objectMapper.writeValueAsString(Map.of(
          "message", result.message(),
          "matched", result.matchedConditions(),
          "failed", result.failedConditions()
      )));
      logRepository.save(logEntry);
    } catch (Exception e) {
      RuleEngineService.log.error("记录执行日志失败: {}", e.getMessage());
    }
  }

  private String generateRuleCode() {
    return "RULE-" + System.currentTimeMillis();
  }

  private void publishRuleChanged(Long ruleId, String action) {
    // 发布规则变更事件，触发本服务及其他监听器重新加载规则
    try {
      eventPublisher.publishEvent(new RuleChangedEvent(this, ruleId, action));
    } catch (Exception ex) {
      log.warn("发布规则变更事件失败: {}", ex.getMessage());
    }
  }

  private RuleDto toDto(Rule rule) {
    RuleDto.RuleConditions conditions = null;
    List<RuleDto.RuleAction> actions = null;

    try {
      if (rule.getConditions() != null && !rule.getConditions().isBlank()) {
        conditions = objectMapper.readValue(rule.getConditions(), RuleDto.RuleConditions.class);
      }
      if (rule.getActions() != null && !rule.getActions().isBlank()) {
        actions = objectMapper.readValue(rule.getActions(),
            new TypeReference<List<RuleDto.RuleAction>>() {});
      }
    } catch (Exception e) {
      RuleEngineService.log.error("解析规则失败: {}", e.getMessage());
    }

    return new RuleDto(
        rule.getId(),
        rule.getRuleCode(),
        rule.getName(),
        rule.getDisasterType(),
        rule.getLevel(),
        rule.getPriority(),
        conditions,
        actions,
        rule.getDslText(),
        rule.getVersion(),
        rule.getEnabled(),
        rule.getCreatedBy(),
        rule.getCreatedAt() != null ? rule.getCreatedAt().toString() : null,
        rule.getUpdatedAt() != null ? rule.getUpdatedAt().toString() : null
    );
  }

  private RuleExecutionLogDto toLogDto(RuleExecutionLog log, String ruleName) {
    return new RuleExecutionLogDto(
        log.getId(),
        log.getRuleId(),
        ruleName,
        log.getTriggered(),
        log.getInputData(),
        log.getResult(),
        log.getExecutedAt() != null ? log.getExecutedAt().toString() : null
    );
  }

  private FieldDictDto toFieldDictDto(FieldDict field) {
    return new FieldDictDto(
        field.getId(),
        field.getField(),
        field.getType(),
        field.getSource(),
        field.getUnit(),
        field.getDescription()
    );
  }
}
