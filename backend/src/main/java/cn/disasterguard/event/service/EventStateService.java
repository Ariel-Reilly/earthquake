package cn.disasterguard.event.service;

import cn.disasterguard.event.EventState;
import cn.disasterguard.event.EventStateMachineConfig;
import cn.disasterguard.event.EventTrigger;
import cn.disasterguard.event.entity.UnifiedEventEntity;
import cn.disasterguard.event.repository.UnifiedEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一事件状态服务（v1.7.0+ P3-4）。
 *
 * 核心职责：
 *   1. 把 hazard_point / warning_event / emergency_event 三表的状态变更收敛到
 *      一个统一的 UnifiedEventEntity 上
 *   2. 用 Spring StateMachine 校验"当前状态 + 触发事件"是否合法
 *   3. 持久化新状态到 unified_event.state，并联动更新三方表的 status 列
 *
 * 用法：
 * <pre>
 *   // 1) 创建事件（AI 推送或人工上报）
 *   String eventCode = eventStateService.createFromAiDetection("HZD-001", "位移突变");
 *
 *   // 2) 推进状态
 *   eventStateService.transition(eventCode, EventTrigger.CONFIRM, operator);
 *
 *   // 3) 查询状态
 *   EventState current = eventStateService.getCurrentState(eventCode);
 * </pre>
 */
@Service
public class EventStateService {

  private static final Logger log = LoggerFactory.getLogger(EventStateService.class);

  private final UnifiedEventRepository repository;
  private final StateMachineFactory<EventState, EventTrigger> stateMachineFactory;

  public EventStateService(
      UnifiedEventRepository repository,
      StateMachineFactory<EventState, EventTrigger> stateMachineFactory
  ) {
    this.repository = repository;
    this.stateMachineFactory = stateMachineFactory;
  }

  /**
   * 创建统一事件 - 由 AI 自动识别触发。
   *
   * @param hazardCode 灾害点编号
   * @param title      事件标题
   * @return 新事件号（UFE-...）
   */
  @Transactional
  public String createFromAiDetection(String hazardCode, String title) {
    return create(hazardCode, title, "AI_AUTO", EventState.DETECTED);
  }

  /**
   * 创建统一事件 - 由人工现场上报触发。
   */
  @Transactional
  public String createFromManualReport(String hazardCode, String title, String operator) {
    return create(hazardCode, title, "MANUAL", EventState.DETECTED);
  }

  private String create(String hazardCode, String title, String source, EventState initialState) {
    OffsetDateTime now = OffsetDateTime.now();

    // R4 P0-5 修复：hazard_point 是投影自 unified_event 的**自动可写视图**，
    // HazardService 先 repository.save(point) 就已经在 unified_event 落了一行
    // kind='HAZARD' 的隐患点记录（event_code 由列默认值生成、state 默认 DETECTED）。
    //
    // 修复前这里无条件再插一行，会造成两个后果（后者是硬错误）：
    //   1. 同一隐患点在确认队列 / 待办中心出现两条 —— 那些查询按 state 过滤、
    //      完全不看 kind（见 ConfirmationQueueController:104、TodoCenterController:52）；
    //   2. **直接失败**：hazard_key 是 GENERATED 列（kind='HAZARD' 时取 hazard_code），
    //      而 uq_unified_hazard_key 是唯一索引，第二行必然抛
    //      `duplicate key value violates unique constraint`（已在本机库实测）。
    //
    // 种子数据是"一隐患点一行"的模型（unified_event 中 5 行均为 kind='HAZARD'
    // 且同时带 event_code 与 hazard_code），故此处改为复用已有行、只补齐事件字段。
    UnifiedEventEntity existing = null;
    if (hazardCode != null && !hazardCode.isBlank()) {
      for (UnifiedEventEntity e : repository.findByHazardCode(hazardCode)) {
        if ("HAZARD".equals(e.getKind())) {
          existing = e;
          break;
        }
      }
    }

    if (existing != null) {
      if (existing.getEventCode() == null || existing.getEventCode().isBlank()) {
        existing.setEventCode(generateEventCode());
      }
      existing.setState(initialState);
      if (existing.getTitle() == null || existing.getTitle().isBlank()) {
        existing.setTitle(title);
      }
      existing.setSource(source);
      existing.setCurrentStateSince(now);
      existing.setLastTransitionAt(now);
      existing.setLastTrigger("CREATE");
      existing.setUpdatedAt(now);
      repository.save(existing);
      log.info("UnifiedEvent 复用隐患点已有行: code={}, state={}, hazard={}",
          existing.getEventCode(), existing.getState(), hazardCode);
      return existing.getEventCode();
    }

    UnifiedEventEntity entity = new UnifiedEventEntity();
    entity.setEventCode(generateEventCode());
    entity.setState(initialState);
    // 本方法只服务于隐患点事件（参数即 hazardCode），显式声明判别列，
    // 不再依赖 unified_event.kind 的列默认值。
    entity.setKind("HAZARD");
    entity.setHazardCode(hazardCode);
    entity.setTitle(title);
    entity.setSource(source);
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    entity.setCurrentStateSince(now);
    entity.setLastTransitionAt(now);
    entity.setLastTrigger("CREATE");
    repository.save(entity);
    log.info("UnifiedEvent created: code={}, state={}, hazard={}", entity.getEventCode(), entity.getState(), hazardCode);
    return entity.getEventCode();
  }

  /**
   * 触发状态流转。
   *
   * @return true 表示流转成功；false 表示被状态机拒绝（非法跳转）
   */
  @Transactional
  public boolean transition(String eventCode, EventTrigger trigger, String operator, String reason) {
    UnifiedEventEntity entity = repository.findByEventCode(eventCode).orElse(null);
    if (entity == null) {
      log.warn("transition: event not found code={}", eventCode);
      return false;
    }

    // 构造一次性 state machine 并恢复到当前状态
    StateMachine<EventState, EventTrigger> sm = stateMachineFactory.getStateMachine();
    sm.stop();
    sm.getStateMachineAccessor().doWithAllRegions(access ->
      access.resetStateMachineReactively(
        new DefaultStateMachineContext<>(entity.getState(), null, null, null)
      )
    );
    sm.start();

    // 发送触发事件
    boolean accepted = sm.sendEvent(trigger);
    if (!accepted) {
      log.warn("transition rejected: code={}, from={}, trigger={}", eventCode, entity.getState(), trigger);
      return false;
    }

    // 读取新状态
    EventState newState = sm.getState().getId();
    OffsetDateTime now = OffsetDateTime.now();
    entity.setState(newState);
    entity.setUpdatedAt(now);
    entity.setCurrentStateSince(now);
    entity.setLastTransitionAt(now);
    entity.setLastTrigger(trigger.name());

    // 记录当前持有人：认领、确认、驳回、转派都要能追溯到具体的人。
    // 之前只在终态写入，导致「我已认领」列表和转派来源人（fromUser）永远取不到值。
    entity.setClosedBy(operator);
    // 关闭/驳回原因只有终态才有意义
    if (newState.isTerminal()) {
      entity.setCloseReason(reason);
    }
    repository.save(entity);
    log.info("UnifiedEvent transitioned: code={}, {} -> {}, trigger={}", eventCode, entity.getState(), newState, trigger);
    return true;
  }

  /**
   * 把 hazard_point / warning_event / emergency_event 的状态变更桥接到统一事件上。
   * 三个原 Service 在写入自身表后调用本方法保持 unified_event.state 同步。
   */
  @Transactional
  public void linkWarning(String eventCode, String warningCode) {
    repository.findByEventCode(eventCode).ifPresent(e -> {
      e.setWarningCode(warningCode);
      e.setUpdatedAt(OffsetDateTime.now());
      repository.save(e);
    });
  }

  @Transactional
  public void linkEmergency(String eventCode, String emergencyCode) {
    repository.findByEventCode(eventCode).ifPresent(e -> {
      e.setEmergencyCode(emergencyCode);
      e.setUpdatedAt(OffsetDateTime.now());
      repository.save(e);
    });
  }

  public EventState getCurrentState(String eventCode) {
    return repository.findByEventCode(eventCode).map(UnifiedEventEntity::getState).orElse(null);
  }

  public UnifiedEventEntity findByEventCode(String eventCode) {
    return repository.findByEventCode(eventCode).orElse(null);
  }

  /**
   * 给 /event/stats 接口用：各状态计数
   */
  public Map<EventState, Long> countByState() {
    Map<EventState, Long> result = new java.util.EnumMap<>(EventState.class);
    for (EventState s : EventState.values()) {
      result.put(s, repository.countByState(s));
    }
    return result;
  }

  public List<UnifiedEventEntity> listActive() {
    return repository.findByStateNotIn(List.of(EventState.CLOSED, EventState.REJECTED));
  }

  /**
   * v1.7.0+ P3-4：按 hazard_code 反查 unified_event，给 HazardService 装配 eventCode / eventState 使用。
   * 正常情况下每个 hazard_code 只对应一条事件记录（创建时 1:1 写入）。
   */
  public List<UnifiedEventEntity> findByHazardCode(String hazardCode) {
    if (hazardCode == null || hazardCode.isBlank()) {
      return List.of();
    }
    return repository.findByHazardCode(hazardCode);
  }

  /**
   * 批量按 hazard_code 集合查 unified_event。
   * 返回 Map<hazardCode, 第一条事件>，方便调用方直接装配 DTO（每个 hazardCode 取首条即可）。
   * 返回的 Map 是不可变空集 / 不可变拷贝，不会回写到 repository。
   */
  public java.util.Map<String, UnifiedEventEntity> findByHazardCodeIn(java.util.Collection<String> hazardCodes) {
    if (hazardCodes == null || hazardCodes.isEmpty()) {
      return java.util.Map.of();
    }
    List<UnifiedEventEntity> rows = repository.findByHazardCodeIn(hazardCodes);
    java.util.LinkedHashMap<String, UnifiedEventEntity> out = new java.util.LinkedHashMap<>();
    for (UnifiedEventEntity e : rows) {
      // 一个 hazardCode 可能对应多条事件（极少数情况），保留第一条
      out.putIfAbsent(e.getHazardCode(), e);
    }
    return out;
  }

  /**
   * 按"事件类型"过滤列表。type ∈ {HAZARD, WARNING, EMERGENCY}。
   * 当前实现在内存里过滤（数据量小），后续可下沉到 Repository 的 @Query。
   *
   * @param type      类型过滤；null/空 = 全部
   * @param keyword   事件号/标题模糊匹配
   * @param state     状态过滤；null = 全部
   */
  public List<UnifiedEventEntity> listByType(String type, String keyword, EventState state) {
    List<UnifiedEventEntity> all = repository.findAll();
    return all.stream()
        .filter(e -> matchesType(e, type))
        .filter(e -> state == null || e.getState() == state)
        .filter(e -> matchesKeyword(e, keyword))
        .sorted((a, b) -> {
          OffsetDateTime ta = a.getUpdatedAt() == null ? a.getCreatedAt() : a.getUpdatedAt();
          OffsetDateTime tb = b.getUpdatedAt() == null ? b.getCreatedAt() : b.getUpdatedAt();
          if (ta == null && tb == null) return 0;
          if (ta == null) return 1;
          if (tb == null) return -1;
          return tb.compareTo(ta);
        })
        .toList();
  }

  private boolean matchesType(UnifiedEventEntity e, String type) {
    if (type == null || type.isBlank()) return true;
    return switch (type.toUpperCase()) {
      case "HAZARD" -> e.getHazardCode() != null && !e.getHazardCode().isBlank();
      case "WARNING" -> e.getWarningCode() != null && !e.getWarningCode().isBlank();
      case "EMERGENCY" -> e.getEmergencyCode() != null && !e.getEmergencyCode().isBlank();
      default -> true;
    };
  }

  private boolean matchesKeyword(UnifiedEventEntity e, String keyword) {
    if (keyword == null || keyword.isBlank()) return true;
    String k = keyword.trim().toLowerCase();
    return contains(e.getEventCode(), k)
        || contains(e.getTitle(), k)
        || contains(e.getHazardCode(), k)
        || contains(e.getWarningCode(), k);
  }

  private boolean contains(String value, String keyword) {
    return value != null && value.toLowerCase().contains(keyword);
  }

  private String generateEventCode() {
    String date = OffsetDateTime.now().toLocalDate().toString().replace("-", "");
    String suffix = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    return "UFE-" + date + "-" + suffix;
  }
}
