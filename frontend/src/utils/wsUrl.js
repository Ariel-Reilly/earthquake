/**
 * WebSocket 地址规范化工具。
 *
 * 背景：生产 compose 给前端注入的是相对路径 `VITE_WS_URL=/ws`，
 * 而浏览器 `new WebSocket("/ws")` 只接受绝对 URL，会直接抛 SyntaxError；
 * dev 侧推导出的 `ws://127.0.0.1:5173/ws` 也需要经 Vite /ws 代理才能到达后端。
 *
 * 本函数把任意输入（相对路径 / http(s) 绝对地址 / ws(s) 地址）统一成
 * 合法的 ws:// 或 wss:// 绝对地址；无法解析时返回空串，调用方据此降级为轮询。
 *
 * @param {string} raw 原始地址（可能为相对路径）
 * @param {string} [fallbackOrigin] 相对路径的基准 origin，默认取 window.location.origin
 * @returns {string} 规范化后的绝对 WS 地址，解析失败返回空串
 */
export function resolveWebSocketUrl(raw, fallbackOrigin) {
  if (raw === null || raw === undefined) {
    return ''
  }
  const value = String(raw).trim()
  if (!value) {
    return ''
  }
  let origin = fallbackOrigin
  if (!origin) {
    if (typeof window === 'undefined' || !window.location) {
      return ''
    }
    origin = window.location.origin
  }
  try {
    const url = new URL(value, origin)
    if (url.protocol === 'http:') {
      url.protocol = 'ws:'
    } else if (url.protocol === 'https:') {
      url.protocol = 'wss:'
    }
    if (url.protocol !== 'ws:' && url.protocol !== 'wss:') {
      return ''
    }
    return url.toString()
  } catch {
    return ''
  }
}

/**
 * 把已带 query 的 WS 地址追加参数（保留已有 query）。
 *
 * @param {string} url 规范化后的地址
 * @param {Record<string, string>} params 追加参数
 * @returns {string} 追加后的地址
 */
export function appendWsQuery(url, params) {
  if (!url) {
    return ''
  }
  try {
    const parsed = new URL(url)
    for (const [key, value] of Object.entries(params ?? {})) {
      if (value !== undefined && value !== null) {
        parsed.searchParams.set(key, String(value))
      }
    }
    return parsed.toString()
  } catch {
    return url
  }
}
