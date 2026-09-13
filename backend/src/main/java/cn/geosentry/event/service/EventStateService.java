package cn.geosentry.event.service;

import cn.geosentry.event.EventState;
import cn.geosentry.event.EventStateMachineConfig;
import cn.geosentry.event.EventTrigger;
import cn.geosentry.event.entity.UnifiedEventEntity;
import cn.geosentry.event.repository.UnifiedEventRepository;
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
    UnifiedEventEntity entity = new UnifiedEventEntity();
    entity.setEventCode(generateEventCode());
    entity.setState(initialState);
    entity.setHazardCode(hazardCode);
    entity.setTitle(title);
    entity.setSource(source);
    OffsetDateTime now = OffsetDateTime.now();
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

    // 终态写入关闭/驳回原因
    if (newState.isTerminal()) {
      entity.setClosedBy(operator);
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

  private String generateEventCode() {
    String date = OffsetDateTime.now().toLocalDate().toString().replace("-", "");
    String suffix = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    return "UFE-" + date + "-" + suffix;
  }
}
