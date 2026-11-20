/**
 * 全局紧急警报的纯判定工具。
 *
 * 本模块不依赖 Vue / 浏览器 / 实时服务，只做"这条预警该不该触发全局警报"
 * 以及"该用哪一级警报（颜色）"的判断，便于用 vitest 单测覆盖。
 *
 * 分级口径（与后端 AI 研判一致）：
 *   - 后端规则引擎按 AI 研判分数区间生成预警等级（ai-service
 *     fusion_engine.py / comprehensive_model.py 的 LEVEL_THRESHOLDS）：
 *       ≥80  CRITICAL → 红色预警
 *       60-80 HIGH    → 橙色预警
 *       35-60 MEDIUM  → 黄色预警
 *       <35   LOW     → 蓝色预警
 *     （backend AiWarningScheduler: CRITICAL→红色预警、HIGH→橙色预警、MEDIUM→黄色预警）
 *   - 红（critical）：满屏红色警报 + 声音 + 闪烁，需手动确认；
 *   - 橙/黄/蓝（warning/caution/info）：对应颜色的普通弹窗，3 秒后自动跳转详情。
 *
 * 说明：`warning.level` / `cssClass` 就是后端按上述 AI 分数区间折算后的等级，
 * 因此前端以等级文字为第一权威；若等级文字缺失，再用 AI 原始等级
 * （aiLevel）或 cssClass 兜底，避免把不同颜色档位混判。
 */

/** 视为"待确认"的状态值：后端中文状态 + 统一事件英文枚举兜底。 */
const PENDING_STATUSES = new Set(['待确认', 'DETECTED'])

/** 红 / 橙 / 黄 / 蓝：与后端 AI 研判分数区间一一对应。 */
const CRITICAL_LEVEL_PATTERN = /红/
const WARNING_LEVEL_PATTERN = /橙/
const CAUTION_LEVEL_PATTERN = /黄/
const INFO_LEVEL_PATTERN = /蓝/

const CRITICAL_CSS_PATTERN = /red/i
const WARNING_CSS_PATTERN = /orange/i
const CAUTION_CSS_PATTERN = /yellow/i
const INFO_CSS_PATTERN = /blue/i

/**
 * AI 研判分数区间（来源：ai-service fusion_engine.py / comprehensive_model.py
 * 的 LEVEL_THRESHOLDS，以及后端 AiWarningScheduler 的等级映射）。
 * 前端保留这份常量用于"分数 → 档位"的兜底换算与文档一致性。
 */
export const AI_RISK_BANDS = Object.freeze([
  { min: 80, severity: 'critical', level: '红色预警', label: '红色警报' },
  { min: 60, severity: 'warning', level: '橙色预警', label: '橙色预警' },
  { min: 35, severity: 'caution', level: '黄色预警', label: '黄色提示' },
  { min: 0, severity: 'info', level: '蓝色预警', label: '蓝色关注' },
])

/** 警报级别枚举：critical 满屏 · warning/caution/info 对应颜色弹窗。 */
export const ALARM_SEVERITY = Object.freeze({
  CRITICAL: 'critical',
  WARNING: 'warning',
  CAUTION: 'caution',
  INFO: 'info',
})

/** 级别 → 中文短标签（弹窗徽标用）。 */
const SEVERITY_LABELS = Object.freeze({
  critical: '红色警报',
  warning: '橙色预警',
  caution: '黄色提示',
  info: '蓝色关注',
})

/** AI 等级（CRITICAL/HIGH/MEDIUM/LOW）→ 警报级别兜底映射。 */
const AI_LEVEL_SEVERITY = Object.freeze({
  CRITICAL: ALARM_SEVERITY.CRITICAL,
  HIGH: ALARM_SEVERITY.WARNING,
  MEDIUM: ALARM_SEVERITY.CAUTION,
  LOW: ALARM_SEVERITY.INFO,
})

/**
 * 取预警的稳定 ID 字符串（后端可能下发 number 或 string）。
 *
 * @param {object} warning 预警对象
 * @returns {string} 归一化后的 ID，无法解析时返回空串
 */
export function normalizeWarningId(warning) {
  const raw = warning?.id ?? warning?.warningId
  if (raw === null || raw === undefined || raw === '') {
    return ''
  }
  return String(raw)
}

/**
 * 按 AI 研判分数换算警报级别。
 *
 * @param {number|string|undefined} score 0-100 的 AI 研判分数
 * @returns {'critical'|'warning'|'caution'|'info'|null} 级别（无效分数返回 null）
 */
export function severityFromAiScore(score) {
  if (score === null || score === undefined || score === '' || typeof score === 'boolean') {
    return null
  }
  const value = Number(score)
  if (!Number.isFinite(value)) {
    return null
  }
  const band = AI_RISK_BANDS.find((item) => value >= item.min)
  return band ? band.severity : null
}

/**
 * 取级别的中文短标签。
 *
 * @param {string} severity 级别
 * @returns {string} 中文标签
 */
export function severityLabelOf(severity) {
  return SEVERITY_LABELS[severity] ?? '预警提示'
}

/**
 * 判断一条预警的警报级别（仅待确认的预警才分级）。
 *
 * 判定顺序：
 *   1) 等级文字（红/橙/黄/蓝）——后端按 AI 分数区间折算后的权威值；
 *   2) AI 原始等级 aiLevel（CRITICAL/HIGH/MEDIUM/LOW）；
 *   3) cssClass（red/orange/yellow/blue）。
 * 这样即使后端某条数据的 cssClass 与 level 不一致，也不会把颜色档位混判。
 *
 * @param {object} warning 归一化前/后的预警对象均可
 * @returns {'critical'|'warning'|'caution'|'info'|null} 级别（不需要警报时 null）
 */
export function alarmSeverityOf(warning) {
  if (!warning || typeof warning !== 'object') {
    return null
  }
  const status = String(warning.status ?? '').trim()
  if (!PENDING_STATUSES.has(status)) {
    return null
  }
  const level = String(warning.level ?? warning.riskLevel ?? '')
  if (CRITICAL_LEVEL_PATTERN.test(level)) {
    return ALARM_SEVERITY.CRITICAL
  }
  if (WARNING_LEVEL_PATTERN.test(level)) {
    return ALARM_SEVERITY.WARNING
  }
  if (CAUTION_LEVEL_PATTERN.test(level)) {
    return ALARM_SEVERITY.CAUTION
  }
  if (INFO_LEVEL_PATTERN.test(level)) {
    return ALARM_SEVERITY.INFO
  }

  const aiLevel = String(warning.aiLevel ?? '')
    .trim()
    .toUpperCase()
  if (AI_LEVEL_SEVERITY[aiLevel]) {
    return AI_LEVEL_SEVERITY[aiLevel]
  }

  const cssClass = String(warning.cssClass || warning.class || '')
  if (CRITICAL_CSS_PATTERN.test(cssClass)) {
    return ALARM_SEVERITY.CRITICAL
  }
  if (WARNING_CSS_PATTERN.test(cssClass)) {
    return ALARM_SEVERITY.WARNING
  }
  if (CAUTION_CSS_PATTERN.test(cssClass)) {
    return ALARM_SEVERITY.CAUTION
  }
  if (INFO_CSS_PATTERN.test(cssClass)) {
    return ALARM_SEVERITY.INFO
  }
  return null
}

/**
 * 判断一条预警是否值得触发全局警报（待确认 + 红/橙/黄/蓝）。
 *
 * @param {object} warning 归一化前/后的预警对象均可
 * @returns {boolean} 是否触发
 */
export function isAlarmWorthy(warning) {
  return alarmSeverityOf(warning) !== null
}

/**
 * 把一批预警按级别拆成"满屏严重警报"和"普通弹窗警报"两组。
 *
 * @param {Array<object>} warnings 预警列表
 * @returns {{ critical: Array<object>, popup: Array<object> }} 分级结果
 */
export function partitionAlarmWarnings(warnings) {
  const result = { critical: [], popup: [] }
  if (!Array.isArray(warnings)) {
    return result
  }
  for (const warning of warnings) {
    const severity = alarmSeverityOf(warning)
    if (severity === ALARM_SEVERITY.CRITICAL) {
      result.critical.push({ ...warning, severity })
    } else if (severity) {
      result.popup.push({ ...warning, severity })
    }
  }
  return result
}

/**
 * 从实时事件 payload 中取出预警数据。
 *
 * realtimeService 的 payload 形态：
 *   - { type: 'snapshot' | 'poll' | 'warning-poll', warnings: [...] }
 *   - { type: 'warning', data: {...} }  （WebSocket 单条增量）
 *
 * @param {object} payload 实时事件
 * @returns {Array<object>} 预警数组，取不到时返回空数组
 */
export function extractWarnings(payload) {
  if (!payload || typeof payload !== 'object') {
    return []
  }
  if (Array.isArray(payload.warnings)) {
    return payload.warnings
  }
  if (payload.type === 'warning' && payload.data) {
    return [payload.data]
  }
  return []
}

/**
 * 从一批预警中挑出"首次出现且值得警报"的项，同时把所有出现过的 ID
 * 记入 knownIds —— 见过但未弹过的（已确认/已关闭）也要登记，避免它们
 * 之后状态变化时被误判为"新预警"。
 *
 * 返回项会附带 `severity` 字段（critical/warning/caution/info），
 * 供弹窗组件选择满屏红色或对应颜色的普通弹窗。
 *
 * @param {Set<string>} knownIds 已见过的预警 ID 集合（原地修改）
 * @param {Array<object>} warnings 本轮拿到的预警列表
 * @returns {Array<object>} 需要触发全局警报的新预警（保持原有顺序）
 */
export function pickNewAlarmWarnings(knownIds, warnings) {
  if (!(knownIds instanceof Set) || !Array.isArray(warnings) || warnings.length === 0) {
    return []
  }
  const fresh = []
  for (const warning of warnings) {
    const id = normalizeWarningId(warning)
    if (!id) {
      continue
    }
    if (knownIds.has(id)) {
      continue
    }
    knownIds.add(id)
    const severity = alarmSeverityOf(warning)
    if (severity) {
      fresh.push({ ...warning, severity })
    }
  }
  return fresh
}
