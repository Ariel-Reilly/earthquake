package cn.geosentry.event;

import java.util.EnumSet;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

/**
 * 统一事件状态机配置（v1.7.0+ P3-4）。
 *
 * State：EventState（7 个状态）
 * Event：EventTrigger（10 个事件）
 *
 * 流转规则：
 *
 *   DETECTED ─AI_DETECT/MANUAL_REPORT─> CONFIRMING
 *   DETECTED ─DISMISS──────────────────> CLOSED
 *   CONFIRMING ─CONFIRM──────────────> CONFIRMED
 *   CONFIRMING ─REJECT───────────────> REJECTED
 *   CONFIRMING ─DISMISS──────────────> CLOSED
 *   CONFIRMED ─ISSUE_WARNING─────────> WARNING_ISSUED
 *   CONFIRMED ─TRIGGER_EMERGENCY─────> EMERGENCY_ACTIVE
 *   CONFIRMED ─DISMISS───────────────> CLOSED
 *   WARNING_ISSUED ─TRIGGER_EMERGENCY─> EMERGENCY_ACTIVE
 *   WARNING_ISSUED ─CLOSE────────────> CLOSED
 *   EMERGENCY_ACTIVE ─CLOSE──────────> CLOSED
 *   CLOSED ─REOPEN────────────────────> WARNING_ISSUED（重开回到发布后）
 *
 * 终态（CLOSED / REJECTED）除 REOPEN 外不再接受其他 event。
 *
 * 注意：本配置为"工厂模式"——每次发送 event 都创建一个新实例，
 * 避免跨请求状态污染。状态持久化由 EventStateService 在 unified_event 表中完成。
 */
@Configuration
@EnableStateMachineFactory
public class EventStateMachineConfig
    extends StateMachineConfigurerAdapter<EventState, EventTrigger> {

  @Override
  public void configure(StateMachineStateConfigurer<EventState, EventTrigger> states)
      throws Exception {
    states
      .withStates()
      .initial(EventState.DETECTED)
      .states(EnumSet.allOf(EventState.class))
      .end(EventState.CLOSED)
      .end(EventState.REJECTED);
  }

  @Override
  public void configure(StateMachineTransitionConfigurer<EventState, EventTrigger> transitions)
      throws Exception {
    transitions
      // 监测发现阶段
      .withExternal()
        .source(EventState.DETECTED).target(EventState.CONFIRMING)
        .event(EventTrigger.AI_DETECT)
        .and()
      .withExternal()
        .source(EventState.DETECTED).target(EventState.CONFIRMING)
        .event(EventTrigger.MANUAL_REPORT)
        .and()
      .withExternal()
        .source(EventState.DETECTED).target(EventState.CLOSED)
        .event(EventTrigger.DISMISS)
        .and()

      // 人工确认阶段
      .withExternal()
        .source(EventState.CONFIRMING).target(EventState.CONFIRMED)
        .event(EventTrigger.CONFIRM)
        .and()
      .withExternal()
        .source(EventState.CONFIRMING).target(EventState.REJECTED)
        .event(EventTrigger.REJECT)
        .and()
      .withExternal()
        .source(EventState.CONFIRMING).target(EventState.CLOSED)
        .event(EventTrigger.DISMISS)
        .and()

      // 已确认 → 预警/应急
      .withExternal()
        .source(EventState.CONFIRMED).target(EventState.WARNING_ISSUED)
        .event(EventTrigger.ISSUE_WARNING)
        .and()
      .withExternal()
        .source(EventState.CONFIRMED).target(EventState.EMERGENCY_ACTIVE)
        .event(EventTrigger.TRIGGER_EMERGENCY)
        .and()
      .withExternal()
        .source(EventState.CONFIRMED).target(EventState.CLOSED)
        .event(EventTrigger.DISMISS)
        .and()

      // 预警已发布 → 应急 / 关闭
      .withExternal()
        .source(EventState.WARNING_ISSUED).target(EventState.EMERGENCY_ACTIVE)
        .event(EventTrigger.TRIGGER_EMERGENCY)
        .and()
      .withExternal()
        .source(EventState.WARNING_ISSUED).target(EventState.CLOSED)
        .event(EventTrigger.CLOSE)
        .and()

      // 应急进行 → 关闭
      .withExternal()
        .source(EventState.EMERGENCY_ACTIVE).target(EventState.CLOSED)
        .event(EventTrigger.CLOSE)
        .and()

      // 关闭 → 重开
      .withExternal()
        .source(EventState.CLOSED).target(EventState.WARNING_ISSUED)
        .event(EventTrigger.REOPEN);
  }
}
