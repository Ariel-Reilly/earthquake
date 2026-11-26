/**
 * 预警等级的唯一口径（前端）。
 *
 * 权威映射在后端 `WarningService#cssClass`：红→red、橙→orange、黄→yellow、其余→blue。
 * 库表 `warning_event.warning_level` 存的是中文（红色预警 / 橙色预警 / 黄色预警 / 蓝色预警），
 * 推送对象 `WarningDto.cssClass` 存的是英文 key —— 前端两种入参都会遇到。
 *
 * 此前前端各页各写一份 `level.includes('红')` 判定，至少有 8 套，且互不一致：
 *   - realtimeService 只判「橙」，红色预警被兜底成 blue；
 *   - mapService 把红/橙合成同一色（红橙不可辨），蓝色与未知等级同色；
 *   - MobileCommandPage 以英文 key 查中文数据，等级文案恒为「未知」、颜色恒为灰。
 * 逻辑统一到这里；各页仍保留自己的色板（地图/大屏/移动端配色按场景调过，属有意差异）。
 */

/** 等级从高到低。顺序即排序权重来源。 */
export const WARNING_LEVEL_TONES = ['red', 'orange', 'yellow', 'blue']

const TONE_BY_PATTERN = [
  ['red', /红/],
  ['orange', /橙/],
  ['yellow', /黄/],
  ['blue', /蓝/],
]

const LABELS = {
  red: { full: '红色预警', short: '红色' },
  orange: { full: '橙色预警', short: '橙色' },
  yellow: { full: '黄色预警', short: '黄色' },
  blue: { full: '蓝色预警', short: '蓝色' },
}

/** 与 tokens.css 语义色对齐的默认色板（Morandi）。页面可按场景覆盖。 */
export const WARNING_LEVEL_COLORS = {
  red: '#b76565',
  orange: '#c9a157',
  yellow: '#d4b48b',
  blue: '#8ba3c4',
}

/** 地图点位用的低饱和色板：要在浅底卫星/矢量图上可辨，又不能盖过品牌绿。 */
export const WARNING_LEVEL_MAP_COLORS = {
  red: '#c0544a',
  orange: '#cd927e',
  yellow: '#c4a86e',
  blue: '#4f8f99',
}

/**
 * 任意形态的等级入参 → 规范 tone（red|orange|yellow|blue）。
 * 支持：中文等级文案（红色预警 / 橙 / 黄色）、英文 key（RED / red / redAlert）、后端 cssClass。
 * 无法识别（含空值）一律回落 blue —— 与后端 `cssClass` 的兜底一致。
 *
 * @param {unknown} value
 * @returns {'red'|'orange'|'yellow'|'blue'}
 */
export function normalizeWarningLevel(value) {
  const raw = String(value ?? '').trim()
  if (!raw) return 'blue'

  const lowered = raw.toLowerCase().replace(/[\s_-]/g, '')
  for (const tone of WARNING_LEVEL_TONES) {
    if (lowered === tone || lowered === `${tone}alert` || lowered === `${tone}warning`) {
      return tone
    }
  }
  for (const [tone, pattern] of TONE_BY_PATTERN) {
    if (pattern.test(raw)) return tone
  }
  return 'blue'
}

/**
 * 等级文案。默认返回完整文案「红色预警」，`{ short: true }` 返回「红色」。
 *
 * @param {unknown} value
 * @param {{ short?: boolean }} [options]
 */
export function warningLevelLabel(value, { short = false } = {}) {
  const label = LABELS[normalizeWarningLevel(value)]
  return short ? label.short : label.full
}

/**
 * 排序权重：红 4 / 橙 3 / 黄 2 / 蓝 1。
 * 与 `normalizeWarningLevel` 的兜底不同，无法识别时返回 0（不参与排序），
 * 以免「未知等级」被当成蓝色排进列表。
 *
 * @param {unknown} value
 * @returns {0|1|2|3|4}
 */
export function warningLevelRank(value) {
  const raw = String(value ?? '').trim()
  if (!raw) return 0
  const tone = normalizeWarningLevel(raw)
  const recognized =
    TONE_BY_PATTERN.some(([, pattern]) => pattern.test(raw)) ||
    WARNING_LEVEL_TONES.includes(raw.toLowerCase().replace(/[\s_-]/g, ''))
  if (!recognized) return 0
  return WARNING_LEVEL_TONES.length - WARNING_LEVEL_TONES.indexOf(tone)
}

/**
 * 等级色。`palette` 可选：'ui'（默认，见 WARNING_LEVEL_COLORS）或 'map'。
 *
 * @param {unknown} value
 * @param {'ui'|'map'} [palette]
 */
export function warningLevelColor(value, palette = 'ui') {
  const table = palette === 'map' ? WARNING_LEVEL_MAP_COLORS : WARNING_LEVEL_COLORS
  return table[normalizeWarningLevel(value)]
}

/** CSS class（red/orange/yellow/blue），供 `:class="warningLevelTone(x)"` 直接使用。 */
export function warningLevelTone(value) {
  return normalizeWarningLevel(value)
}
