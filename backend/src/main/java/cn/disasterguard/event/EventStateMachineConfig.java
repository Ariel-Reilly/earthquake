package cn.disasterguard.event;

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
 *   DETECTED ─START_CONFIRM───────────> CONFIRMING（人工认领）
 *   DETECTED ─CONFIRM─────────────────> CONFIRMED（A3 修复：管理员直接确认）
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
 *   REJECTED ─REOPEN──────────────────> CONFIRMING（驳回后重新进入确认）
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
    // B5-1 单测发现的 P1 缺陷（2026-11-13）：CLOSED / REJECTED 原先被声明为
    // Spring StateMachine 的 .end()（机器终止态）。一旦进入，状态机实例直接终止，
    // 下面配置的 REOPEN 出口永远不会被接受 —— 关闭/驳回的事件永久死锁
    // （R3 P1-17 曾补了 REOPEN 转换，但没去掉 .end()，修复并不完整）。
    //
    // 业务"终态"由 EventState.isTerminal() 判定即可；配置层面把它们保留为普通状态，
    // 使 REOPEN 真正可用，同时由于没有配置其他出口，非法流转依旧被 sendEvent 拒绝。
    states
      .withStates()
      .initial(EventState.DETECTED)
      .states(EnumSet.allOf(EventState.class));
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
      // R3 P0-4：认领（DETECTED → CONFIRMING）。此前缺失该转换，
      // ConfirmationQueueController 的认领接口使用 START_CONFIRM 恒被拒绝，
      // 永远返回 400 "认领失败：状态流转被拒绝"。
      .withExternal()
        .source(EventState.DETECTED).target(EventState.CONFIRMING)
        .event(EventTrigger.START_CONFIRM)
        .and()
      // 【A3 修复】管理员直接确认（跳过确认队列，DETECTED → CONFIRMED）
      .withExternal()
        .source(EventState.DETECTED).target(EventState.CONFIRMED)
        .event(EventTrigger.CONFIRM)
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
        .event(EventTrigger.REOPEN)
        .and()
      // 驳回 → 重开（R3 P1-17 修复：此前 REJECTED 被声明为终态却无任何出口，
      // 驳回的事件永久死锁无法重新处理。文档 §终态 亦要求 REOPEN 为其出口。）
      .withExternal()
        .source(EventState.REJECTED).target(EventState.CONFIRMING)
        .event(EventTrigger.REOPEN);
  }
}
