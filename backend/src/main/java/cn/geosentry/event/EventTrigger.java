package cn.geosentry.event;

/**
 * 状态机触发事件（Event = 状态之间的"动作"）。
 *
 * 与 EventState 一一对应：每个 event 表示一种状态切换的意图，
 * StateMachineConfig 会按 transition 配置决定是否允许该切换。
 */
public enum EventTrigger {
  /** AI 自动识别到新事件（来自 ai-service 综合研判） */
  AI_DETECT,
  /** 人工现场上报 */
  MANUAL_REPORT,
  /** 认领，开始人工确认 */
  START_CONFIRM,
  /** 签认通过 */
  CONFIRM,
  /** 复核不予立项 */
  REJECT,
  /** 标记为误报（保留档案但不进入预警） */
  DISMISS,
  /** 发布预警 */
  ISSUE_WARNING,
  /** 触发应急事件（橙/红预警自动联动） */
  TRIGGER_EMERGENCY,
  /** 关闭事件（处置完成 / 自动解除） */
  CLOSE,
  /** 撤销关闭，重新进入活动态 */
  REOPEN,
}
