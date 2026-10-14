/**
 * 灾害类型（灾种）单一数据源。
 *
 * 背景：R3 审查发现同一套灾种编码曾在 5 处各写一份且互相矛盾
 * （NoticeTemplatePage 5 项 / HistoryHazardsPage 7 项 / WarningCreatePage 7 项 /
 * 后端 WarningService#HAZARD_TYPE_CODES 5 条 / sys_dict.disaster_type 10 项），
 * 出现"前端有、字典没有"（subsidence）和"字典有、前端没有"
 * （typhoon / drought / waterlogging / ground_fissure）的双向漂移。
 *
 * 本模块以**后端 sys_dict.disaster_type 为准**（见 backend/src/main/resources/data.sql），
 * 全前端统一引用，禁止在页面里再各写一份 hazardOptions。
 *
 * 约定：
 *   - value = 英文码，用于与后端交互（unified_event.hazard_type / 规则引擎 / DTO）
 *   - label = 中文展示文案
 */

/** @type {ReadonlyArray<{value: string, label: string}>} */
export const HAZARD_TYPES = Object.freeze([
  { value: "landslide", label: "滑坡" },
  { value: "collapse", label: "崩塌" },
  { value: "debris_flow", label: "泥石流" },
  { value: "subsidence", label: "地面沉降" },
  { value: "ground_fissure", label: "地裂缝" },
  { value: "earthquake", label: "地震" },
  { value: "flood", label: "洪水" },
  { value: "fire", label: "火灾" },
  { value: "typhoon", label: "台风" },
  { value: "drought", label: "干旱" },
  { value: "waterlogging", label: "内涝" },
])

/** value → label，供展示层使用；未知码原样返回（不要静默吞掉）。 */
export const HAZARD_TYPE_LABELS = Object.freeze(
  Object.fromEntries(HAZARD_TYPES.map((o) => [o.value, o.label])),
)

/**
 * 中文标签 → 英文码。用于归一化后端 / 存量数据中可能残留的中文值。
 * 已是英文码时原样返回，未收录时返回 fallback。
 */
const LABEL_TO_CODE = Object.freeze(
  Object.fromEntries(HAZARD_TYPES.map((o) => [o.label, o.value])),
)

/** 兼容历史写法：'地面塌陷' 曾与 '地面沉降' 混用。 */
const ALIASES = Object.freeze({
  地面塌陷: "subsidence",
  塌陷: "collapse",
  城市内涝: "waterlogging",
})

/**
 * 归一化灾种：中文标签 → 英文码，英文码原样返回。
 *
 * @param {unknown} v 待归一化的值
 * @param {string} [fallback=''] 无法识别时的回退值
 * @returns {string}
 */
export function normalizeHazardType(v, fallback = "") {
  if (typeof v !== "string") {
    return fallback
  }
  const raw = v.trim()
  if (!raw) {
    return fallback
  }
  if (LABEL_TO_CODE[raw]) {
    return LABEL_TO_CODE[raw]
  }
  if (ALIASES[raw]) {
    return ALIASES[raw]
  }
  // 已是英文码（含字典中暂未收录的新码）时原样返回
  return raw
}

/**
 * 取展示文案：英文码 → 中文标签。
 * 未知码原样返回，避免界面出现空白或 undefined。
 */
export function hazardTypeLabel(v) {
  if (typeof v !== "string" || !v) {
    return v ?? "—"
  }
  return HAZARD_TYPE_LABELS[v] ?? v
}
