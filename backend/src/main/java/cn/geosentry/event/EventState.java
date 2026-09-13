package cn.geosentry.event;

/**
 * 统一事件状态枚举（v1.7.0+ P3-4 状态机顶层状态）。
 *
 * 设计目标：把 hazard_point / warning_event / emergency_event 三张表的"status"列
 * 收敛到同一张状态机的有限状态集合。前端 / TodoCenter / 消息中心都基于这套状态
 * 聚合展示。
 *
 * 流转图（StateMachine 配置见 EventStateMachineConfig.java）：
 *
 *   DETECTED ──AI_DETECT/MANUAL_REPORT──> CONFIRMING
 *   CONFIRMING ──CONFIRM──> CONFIRMED ──ISSUE_WARNING──> WARNING_ISSUED
 *      │                                       │
 *      │                                       └──TRIGGER_EMERGENCY──> EMERGENCY_ACTIVE
 *      │                                                                  │
 *      ├──REJECT──> REJECTED (终态)                                          │
 *      └──DISMISS──> CLOSED  ◀── CLOSE ───────────────────────┘
 *
 * 任意活动态都可以 CLOSE 进入 CLOSED，REOPEN 从 CLOSED 回到 WARNING_ISSUED 或
 * EMERGENCY_ACTIVE（按当前绑定的最大阶段决定）。
 */
public enum EventState {
  /** 监测发现 / AI 推送 / 人工上报 - 待复核 */
  DETECTED,
  /** 人工确认进行中（已认领、待签认） */
  CONFIRMING,
  /** 已确认 - 准备发起预警 */
  CONFIRMED,
  /** 预警已发布 - 进入公众通知阶段 */
  WARNING_ISSUED,
  /** 应急事件进行中（橙/红预警自动联动） */
  EMERGENCY_ACTIVE,
  /** 已关闭（终态：处置完成 / 误报 / 自动解除） */
  CLOSED,
  /** 已驳回（终态：经复核不予立项） */
  REJECTED;

  /** 是否终态（CLOSE / REJECT 后不再流转） */
  public boolean isTerminal() {
    return this == CLOSED || this == REJECTED;
  }

  /** 是否可触发应急 */
  public boolean canEscalate() {
    return this == CONFIRMED || this == WARNING_ISSUED;
  }
}
