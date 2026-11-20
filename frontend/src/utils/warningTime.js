/**
 * 预警展示时间（A6-5 决策：跟随浏览器本地时区）。
 *
 * 背景（2026-11-14 第四轮复测）：后端 `WarningService.toDto` 用
 *   `warning.getTriggeredAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))`
 * 生成 `WarningDto.time`。JPA 返回的 OffsetDateTime 是 UTC，后端又拿不到浏览器时区，
 * 于是这个字符串比北京时间早 8 小时、跨午夜还会显示成前一天；前端若原样渲染
 * `warning.time` 就与 A6-5「跟随浏览器本地时区」冲突。
 *
 * 修法：优先用 `triggeredAt`（ISO 绝对时刻，如 2026-11-14T06:15:29Z）按**浏览器本地时区**
 * 重新格式化，保持原有 "MM-dd HH:mm" 视觉格式不变；只有缺失 / 无效 triggeredAt 的
 * 旧数据或 mock 数据才回退到后端 `time` 快照（兼容，不造新值）。
 *
 * 不采用「后端按 ZoneId.systemDefault()/Asia/Shanghai 格式化」：那是固定服务器时区，
 * 换时区浏览器又会错，且把格式化结果写死在 DTO 里前端无法再按浏览器时区调整。
 */
const pad = (n) => String(n).padStart(2, '0')

/** 后端 OffsetDateTime 序列化形态：2026-11-14T06:43:15.074227Z / +08:00。 */
const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T/

/**
 * @param {object} warning 含 triggeredAt（ISO）与/或 time（"MM-dd HH:mm"）的预警对象
 * @param {string} [timeZone] 可选 IANA 时区，供单测 / SSR 显式指定；
 *        不传时一律用浏览器本地 getter（A6-5「跟随浏览器时区」）。
 * @returns {string} "MM-dd HH:mm"，无可用值时 '—'
 */
export function formatWarningTime(warning, timeZone) {
  const iso = warning?.triggeredAt
  if (iso) {
    const d = new Date(iso)
    if (!Number.isNaN(d.getTime())) {
      if (timeZone) {
        const parts = new Intl.DateTimeFormat('en-US', {
          timeZone,
          hourCycle: 'h23',
          month: '2-digit',
          day: '2-digit',
          hour: '2-digit',
          minute: '2-digit',
        }).formatToParts(d)
        const get = (type) => parts.find((p) => p.type === type)?.value ?? ''
        return `${get('month')}-${get('day')} ${get('hour')}:${get('minute')}`
      }
      return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
    }
  }
  return warning?.time ?? '—'
}

/**
 * 时间线节点时间：兼容三类值，供「预警发布 / 确认 / 派发 / 处理反馈 / 关闭」共用。
 *
 * - ISO 绝对时刻字符串（后端 `confirmedAt` / `dispatchedAt` / `closedAt` 等
 *   OffsetDateTime）：按浏览器本地时区格式化为 "MM-dd HH:mm"，不再直出 UTC 原文；
 * - 预警 / 历史对象：走 `formatWarningTime`，优先 `triggeredAt`、回退 `time`；
 * - 已是 "MM-dd HH:mm" 的展示串或「已确认」等状态文案：原样保留，不造新值。
 *
 * @param {string|object} value
 * @param {string} [fallback] 空值时使用的占位文案
 * @param {string} [timeZone] 可选 IANA 时区，供单测显式指定
 * @returns {string}
 */
export function formatTimelineTime(value, fallback = '—', timeZone) {
  if (value && typeof value === 'object') {
    return formatWarningTime(value, timeZone)
  }
  if (value === null || value === undefined || value === '') {
    return fallback
  }
  const text = String(value)
  if (!ISO_INSTANT.test(text)) {
    return text
  }
  // 无效的 ISO 也不能变成 '—'：把原文同时作为 time 回退值
  return formatWarningTime({ triggeredAt: text, time: text }, timeZone)
}
