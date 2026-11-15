/**
 * API 契约归一化（前端唯一口径）。
 *
 * 背景：后端分页响应存在三种字段形态 —— records（PageResponse）、items（部分 Controller）、
 * list（少数接口）；页面层各自写兼容分支会导致口径漂移（见 docs/api/CONTRACT.md §1）。
 * 本模块把分页统一为 { records, total, page, size, pages }，纯函数、无副作用，便于单测。
 *
 * 注意：request()（httpClient）已解包 ApiResponse.data，这里接收的是 data 本体。
 * 当前仅新增本模块与单测；页面 / dashboardApi 的迁移在阶段 1 后续小步进行（先不改调用点）。
 */

const LIST_KEYS = ['records', 'items', 'list']

/**
 * 取对象里第一个数组字段（优先级 records > items > list）。
 * @param {object} obj
 * @returns {any[]|null}
 */
function firstArray(obj) {
  for (const key of LIST_KEYS) {
    if (Array.isArray(obj[key])) {
      return obj[key]
    }
  }
  return null
}

function fallbackPage(fallback) {
  return {
    records: fallback,
    total: fallback.length,
    page: 1,
    // 空 fallback 时 size 兜底为 1，避免下游分页器拿 0 做除数。
    size: fallback.length || 1,
    pages: 1,
  }
}

function numberOr(value, fallbackValue) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallbackValue
}

/**
 * 由 total / size 推导总页数；无法推导时返回 1。
 * 兼容「不完整的旧端点」只给 total/size、不给 pages 的情况。
 */
function derivedPages(total, size) {
  return total > 0 && size > 0 ? Math.ceil(total / size) : 1
}

/**
 * 归一化分页响应。
 * @param {*} payload 已解包的数据：对象 / 数组 / null
 * @param {any[]} [fallback=[]] 缺失时的回退列表
 * @returns {{records:any[], total:number, page:number, size:number, pages:number}}
 */
export function normalizePage(payload, fallback = []) {
  if (Array.isArray(payload)) {
    return {
      records: payload,
      total: payload.length,
      page: 1,
      size: payload.length,
      pages: 1,
    }
  }
  if (!payload || typeof payload !== 'object') {
    return fallbackPage(fallback)
  }
  const list = firstArray(payload)
  if (!list) {
    return fallbackPage(fallback)
  }
  const total = numberOr(payload.total, list.length)
  const rawSize = numberOr(payload.size, list.length)
  // size 为 0 时用列表长度兜底，保证 pages 推导与分页器可用。
  const size = rawSize > 0 ? rawSize : list.length || 1
  const pages = numberOr(payload.pages, derivedPages(total, size))
  return {
    records: list,
    total,
    page: numberOr(payload.page, 1),
    size,
    pages,
  }
}

/**
 * 只取列表（不关心分页元信息）。
 * @param {*} payload 已解包的数据：对象 / 数组 / null
 * @param {any[]} [fallback=[]]
 * @returns {any[]}
 */
export function normalizeList(payload, fallback = []) {
  if (Array.isArray(payload)) {
    return payload
  }
  if (!payload || typeof payload !== 'object') {
    return fallback
  }
  return firstArray(payload) ?? fallback
}
