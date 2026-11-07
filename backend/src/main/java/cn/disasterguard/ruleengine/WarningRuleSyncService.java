package cn.disasterguard.ruleengine;

import cn.disasterguard.ruleengine.entity.Rule;
import cn.disasterguard.ruleengine.repository.RuleRepository;
import cn.disasterguard.warning.entity.WarningRule;
import cn.disasterguard.warning.repository.WarningRuleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则管理页 ↔ 预警引擎 的双向桥（R10 P1-5）。
 *
 * <p><b>问题</b>：页面走 {@code /api/rules} 读写的是 {@code rule} 表，
 * 而真正求值的 {@code RuleEngineScheduler} 读的是 {@code warning_rule} 表。
 * 两张表同编号不同语义（{@code rule.R004=设备离线}、{@code warning_rule.R004=倾角>5}），
 * 于是「在页面上把阈值 50 改成 30」对预警行为零影响，属于"页面有交互、库里有落库、
 * 业务闭环断了"的典型。
 *
 * <p><b>方案</b>：把 {@code warning_rule} 定为唯一真相源。
 * <ul>
 *   <li>启动时 {@link #syncAllFromWarningRule()} 把 warning_rule 镜像进 rule 表 →
 *       页面看到的编号、条件、阈值就是线上正在跑的那一套，不再有两套语义；</li>
 *   <li>页面增删改后 {@link #applyToWarningRule(Rule)} 把 rule 表的结构化条件编译成
 *       表达式写回 warning_rule → 改完下一轮调度即生效；</li>
 *   <li>删除规则不真删 warning_rule（{@code warning_event.rule_code} 有外键），
 *       改为停用，历史预警仍可追溯。</li>
 * </ul>
 *
 * <p>本类只依赖两个 Repository，不依赖 RuleEngineService，避免与
 * {@code RuleInitializer → RuleEngineService} 形成循环。
 */
@Service
public class WarningRuleSyncService {

  private static final Logger log = LoggerFactory.getLogger(WarningRuleSyncService.class);

  /** 页面等级编码 → 库中中文等级。 */
  private static final Map<String, String> LEVEL_TO_CHINESE = Map.of(
      "red", "红色预警",
      "orange", "橙色预警",
      "yellow", "黄色预警",
      "blue", "蓝色预警");

  /** 条件运算符编码 → 表达式符号。 */
  private static final Map<String, String> OP_TO_SYMBOL = Map.ofEntries(
      Map.entry("gt", ">"),
      Map.entry(">", ">"),
      Map.entry("gte", ">="),
      Map.entry(">=", ">="),
      Map.entry("lt", "<"),
      Map.entry("<", "<"),
      Map.entry("lte", "<="),
      Map.entry("<=", "<="),
      Map.entry("eq", "=="),
      Map.entry("==", "=="),
      Map.entry("=", "=="),
      Map.entry("neq", "!="),
      Map.entry("ne", "!="),
      Map.entry("!=", "!="));

  private final RuleRepository ruleRepository;
  private final WarningRuleRepository warningRuleRepository;
  private final ObjectMapper objectMapper;
  /** 逐行独立事务：单条镜像失败不能把整批（尤其已成功的行）一起回滚。 */
  private final org.springframework.transaction.support.TransactionTemplate rowTx;

  public WarningRuleSyncService(RuleRepository ruleRepository,
                                WarningRuleRepository warningRuleRepository,
                                ObjectMapper objectMapper,
                                org.springframework.transaction.PlatformTransactionManager txManager) {
    this.ruleRepository = ruleRepository;
    this.warningRuleRepository = warningRuleRepository;
    this.objectMapper = objectMapper;
    this.rowTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
    this.rowTx.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  // ------------------------------------------------------------------ 启动同步

  /**
   * 用 warning_rule（真相源）覆盖 rule 表内容，保证页面展示即线上生效规则。
   *
   * <p>返回同步条数。内容没变化的行不写库，避免每次启动都刷新 updated_at/version。
   *
   * <p>⚠️ 不加 @Transactional：每行用 REQUIRES_NEW 独立提交。第一版写成整批一个事务，
   * 结果一条 jsonb 绑定报错就把整个事务标成 aborted，后面 5 条全部"命令被忽略"，
   * 最终 commit 抛 UnexpectedRollbackException，一行都没同步成功。
   */
  public int syncAllFromWarningRule() {
    List<WarningRule> sources = warningRuleRepository.findAll();
    if (sources.isEmpty()) {
      log.warn("warning_rule 为空，跳过 rule 表镜像同步（规则管理页将显示为空）");
      return 0;
    }
    int changed = 0;
    for (WarningRule source : sources) {
      try {
        Boolean applied = rowTx.execute(status -> {
          Rule target = ruleRepository.findByRuleCode(source.getRuleCode()).orElseGet(() -> {
            Rule created = new Rule();
            created.setRuleCode(source.getRuleCode());
            created.setVersion(1);
            created.setPriority(0);
            created.setCreatedBy("SYSTEM");
            // rule.actions 是 JSON NOT NULL（schema.sql:701）。R10 P2-6 起 data.sql
            // 不再单独播种 rule 表，新库首启由本服务建行，必须给一个合法占位动作，
            // 否则整批 INSERT 会被 NOT NULL 拦下、规则管理页显示为空。
            created.setActions("{\"type\":\"CREATE_EVENT\"}");
            return created;
          });
          if (!mirrorInto(source, target)) {
            return false;
          }
          target.setUpdatedAt(OffsetDateTime.now());
          ruleRepository.save(target);
          return true;
        });
        if (Boolean.TRUE.equals(applied)) {
          changed++;
        }
      } catch (Exception ex) {
        log.warn("镜像规则失败 code={}: {}", source.getRuleCode(), ex.getMessage());
      }
    }
    if (changed > 0) {
      log.info("规则管理页已与预警引擎对齐：{} 条（共 {} 条生效规则）", changed, sources.size());
    }
    return changed;
  }

  /** 把一条 warning_rule 写进 rule 实体；返回是否有字段变化。 */
  private boolean mirrorInto(WarningRule source, Rule target) {
    String levelCode = toLevelCode(source.getWarningLevel());
    String conditionsJson = toConditionsJson(source.getExpression());
    boolean dirty = false;
    if (!equalsSafe(target.getName(), source.getRuleName())) {
      target.setName(source.getRuleName());
      dirty = true;
    }
    if (!equalsSafe(target.getDisasterType(), source.getDisasterType())) {
      target.setDisasterType(source.getDisasterType());
      dirty = true;
    }
    if (!equalsSafe(target.getLevel(), levelCode)) {
      target.setLevel(levelCode);
      dirty = true;
    }
    if (!equalsSafe(target.getConditions(), conditionsJson)) {
      target.setConditions(conditionsJson);
      dirty = true;
    }
    if (!equalsSafe(target.getDslText(), source.getExpression())) {
      target.setDslText(source.getExpression());
      dirty = true;
    }
    if (!equalsSafe(target.getEnabled(), Boolean.TRUE.equals(source.getEnabled()))) {
      target.setEnabled(Boolean.TRUE.equals(source.getEnabled()));
      dirty = true;
    }
    return dirty;
  }

  // ------------------------------------------------------------------ 页面写入同步

  /** 页面保存后的下发结果，供接口层回报"是否已生效"。 */
  public record ApplyResult(boolean applied, String expression, String message) {
  }

  /**
   * 把 rule 表里的一条规则编译成表达式并下发到 warning_rule。
   *
   * <p>编译不出来（条件为空、含 contains/in 等求值器不支持的运算符）时**不下发**，
   * 并如实返回原因 —— 宁可页面显示"未生效"，也不要把看不懂的条件当成恒真规则写进引擎。
   */
  @Transactional
  public ApplyResult applyToWarningRule(Rule rule) {
    if (rule == null || rule.getRuleCode() == null || rule.getRuleCode().isBlank()) {
      return new ApplyResult(false, null, "规则缺少编号，未下发到预警引擎");
    }
    Compiled compiled = compile(rule.getConditions());
    if (compiled == null) {
      log.warn("规则 {} 的条件无法编译为表达式，继续保留在模板库但不下发预警引擎", rule.getRuleCode());
      return new ApplyResult(false, null, "条件为空或含不支持的运算符，未下发预警引擎");
    }
    WarningRule target = warningRuleRepository.findByRuleCode(rule.getRuleCode())
        .orElseGet(() -> {
          WarningRule created = new WarningRule();
          created.setRuleCode(rule.getRuleCode());
          created.setCreatedAt(OffsetDateTime.now());
          return created;
        });
    target.setRuleName(rule.getName() == null || rule.getName().isBlank()
        ? rule.getRuleCode() : rule.getName());
    target.setMetricCode(compiled.metricCode());
    target.setExpression(compiled.expression());
    target.setWarningLevel(toChineseLevel(rule.getLevel()));
    target.setDisasterType(rule.getDisasterType());
    target.setEnabled(Boolean.TRUE.equals(rule.getEnabled()));
    target.setUpdatedAt(OffsetDateTime.now());
    warningRuleRepository.save(target);
    log.info("规则已下发预警引擎 code={} expression=\"{}\" level={} enabled={}",
        rule.getRuleCode(), compiled.expression(), target.getWarningLevel(), target.getEnabled());
    return new ApplyResult(true, compiled.expression(), null);
  }

  /**
   * 页面删除规则：warning_rule 只停用不删除。
   *
   * <p>{@code warning_event.rule_code} 外键指向 warning_rule，硬删会让历史预警失去归属。
   */
  @Transactional
  public void disableInWarningRule(String ruleCode) {
    if (ruleCode == null || ruleCode.isBlank()) {
      return;
    }
    warningRuleRepository.findByRuleCode(ruleCode).ifPresent(target -> {
      if (Boolean.TRUE.equals(target.getEnabled())) {
        target.setEnabled(false);
        target.setUpdatedAt(OffsetDateTime.now());
        warningRuleRepository.save(target);
        log.info("规则 {} 已从预警引擎停用（页面删除，保留历史预警归属）", ruleCode);
      }
    });
  }

  // ------------------------------------------------------------------ 表达式 <-> 条件

  /** 编译结果：表达式 + 主指标（表达式首项字段）。 */
  private record Compiled(String expression, String metricCode) {
  }

  private Compiled compile(String conditionsJson) {
    if (conditionsJson == null || conditionsJson.isBlank()) {
      return null;
    }
    try {
      JsonNode root = objectMapper.readTree(conditionsJson);
      String logic = firstText(root, "operator", "logic");
      boolean or = "OR".equalsIgnoreCase(logic);
      JsonNode items = root.has("items") ? root.get("items")
          : root.has("conditions") ? root.get("conditions") : null;
      if (items == null || !items.isArray() || items.isEmpty()) {
        return null;
      }
      List<String> clauses = new ArrayList<>();
      String metricCode = null;
      for (JsonNode item : items) {
        String field = firstText(item, "field", "metric");
        String op = firstText(item, "op", "operator", "comparator");
        JsonNode valueNode = item.has("value") ? item.get("value") : item.get("threshold");
        if (field == null || op == null || valueNode == null) {
          return null;
        }
        String symbol = OP_TO_SYMBOL.get(op.toLowerCase(Locale.ROOT));
        if (symbol == null) {
          // contains / in 等求值器不支持，直接判为不可下发
          return null;
        }
        String value = valueNode.isNumber() ? formatNumber(valueNode.decimalValue())
            : valueNode.asText();
        if (value == null || value.isBlank()) {
          return null;
        }
        clauses.add(field + " " + symbol + " " + value);
        if (metricCode == null) {
          metricCode = field;
        }
      }
      String joiner = or ? " OR " : " AND ";
      return new Compiled(String.join(joiner, clauses), metricCode);
    } catch (Exception ex) {
      log.warn("解析规则条件失败: {}", ex.getMessage());
      return null;
    }
  }

  /** 表达式 → 页面用的条件 JSON（{operator, items[]}）。解析不了时返回 null。 */
  private String toConditionsJson(String expression) {
    if (expression == null || expression.isBlank()) {
      return null;
    }
    try {
      boolean or = expression.contains(" OR ");
      String[] clauses = expression.split(or ? "\\s+OR\\s+" : "\\s+AND\\s+");
      List<Map<String, Object>> items = new ArrayList<>();
      for (String clause : clauses) {
        String[] tokens = clause.trim().split("\\s+");
        if (tokens.length != 3) {
          return null;
        }
        String op = symbolToOp(tokens[1]);
        if (op == null) {
          return null;
        }
        Object value;
        try {
          value = new BigDecimal(tokens[2]);
        } catch (NumberFormatException e) {
          value = tokens[2];
        }
        items.add(Map.of("field", tokens[0], "op", op, "value", value));
      }
      return objectMapper.writeValueAsString(
          Map.of("operator", or ? "OR" : "AND", "items", items));
    } catch (Exception ex) {
      log.debug("表达式转条件失败 expr={}: {}", expression, ex.getMessage());
      return null;
    }
  }

  private static String symbolToOp(String symbol) {
    return switch (symbol) {
      case ">" -> "gt";
      case ">=" -> "gte";
      case "<" -> "lt";
      case "<=" -> "lte";
      case "==" -> "eq";
      case "!=" -> "neq";
      default -> null;
    };
  }

  private static String formatNumber(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
  }

  private static String firstText(JsonNode node, String... keys) {
    if (node == null) {
      return null;
    }
    for (String key : keys) {
      JsonNode child = node.get(key);
      if (child != null && !child.isNull() && !child.asText().isBlank()) {
        return child.asText();
      }
    }
    return null;
  }

  /** 中文等级 → 页面编码。 */
  public static String toLevelCode(String chineseLevel) {
    if (chineseLevel == null) {
      return "yellow";
    }
    for (Map.Entry<String, String> entry : LEVEL_TO_CHINESE.entrySet()) {
      if (chineseLevel.contains(entry.getValue().substring(0, 2))) {
        return entry.getKey();
      }
    }
    return "yellow";
  }

  /** 页面编码 → 中文等级（预警表存的是中文）。 */
  public static String toChineseLevel(String code) {
    if (code == null || code.isBlank()) {
      return "黄色预警";
    }
    return LEVEL_TO_CHINESE.getOrDefault(code.toLowerCase(Locale.ROOT), "黄色预警");
  }

  /**
   * 批量查询"哪些编号已进入预警引擎"，供列表页标注生效状态。
   *
   * @return key=rule_code，value=当前引擎里的表达式
   */
  public Map<String, String> effectiveExpressions(List<String> ruleCodes) {
    if (ruleCodes == null || ruleCodes.isEmpty()) {
      return Map.of();
    }
    return warningRuleRepository.findByRuleCodeIn(ruleCodes).stream()
        .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
        .collect(Collectors.toMap(WarningRule::getRuleCode,
            r -> Optional.ofNullable(r.getExpression()).orElse(""),
            (a, b) -> a));
  }

  private static boolean equalsSafe(Object a, Object b) {
    return a == null ? b == null : a.equals(b);
  }
}
