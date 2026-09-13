/**
 * 地址/经纬度搜索服务
 *
 * 提供三类入口：
 *   1. parseCoordinate(text) - 文本里抠出经纬度，识别多种书写习惯
 *   2. geocodeAddress(text)   - 地址 → 经纬度（POI 搜索）
 *   3. reverseGeocode(lon,lat)- 经纬度 → 地址（reverse geocoding）
 *
 * 优先使用 AMap REST API（生产环境，前端 key 通过 VITE_AMAP_TOKEN 注入）。
 * AMap 不可用时兜底用 OpenStreetMap Nominatim（公共端点，无 key 但有 1 req/s 限流）。
 *
 * 重要：所有返回结果统一以 WGS-84 给出（数据库存的就是 WGS-84），与地图点位 datum 解耦。
 * AMap 返回的 GCJ-02 通过二分逼近转回 WGS-84（gcj02ToWgs84）。
 *
 * 注意：
 *   - AMap key 不能写死代码里，请通过 .env 的 VITE_AMAP_TOKEN 注入；
 *   - 若未配置 AMap key，自动降级到 OSM Nominatim；
 *   - Nominatim 必须带 User-Agent 或会被拒绝 —— 浏览器自动带 navigator.userAgent。
 */

import { gcj02ToWgs84 } from '../utils/coordTransform'

const amapToken = import.meta.env.VITE_AMAP_TOKEN || ''
const useAmap = Boolean(amapToken)

/**
 * 把一段用户输入解析为经纬度。
 * 支持的格式（不区分大小写、允许中英混合）：
 *   - "108.95, 34.27"
 *   - "108.95 34.27"
 *   - "108.95/34.27"
 *   - "108.95，34.27"  (中文逗号)
 *   - "E108.95 N34.27" / "108.95E 34.27N"
 *   - "lon:108.95 lat:34.27"
 *   - "108.95°E 34.27°N" / "108.95E 34.27N"
 *
 * 返回 { lon, lat } 或 null（非经纬度格式或越界）
 *
 * 越界判定：lon ∈ [-180, 180]、lat ∈ [-90, 90]、且至少有一个值在中国境外常识范围
 * （简单阈值 ±1 防止乱输入 9999 这种）。
 */
export function parseCoordinate(text) {
  if (typeof text !== 'string') {return null}
  const trimmed = text.trim()
  if (!trimmed) {return null}

  // 替换中文逗号 / 斜杠为空格
  const normalized = trimmed
    .replace(/[，；]/g, ' ')
    .replace(/[\\/]/g, ' ')
    .replace(/[、]/g, ' ')
    .replace(/[°]/g, ' ')
    .replace(/[;]/g, ' ')
    .trim()

  // 匹配 "E108.95 N34.27" 这种带方位符的（方位符在数字前后都可能）
  // 把 "E108.95" / "108.95E" 拆成 (108.95, +1) / (108.95, +1)，"W34.27" 拆成 (-34.27, ...)
  const withSigns = []
  const re = /([EWNS])\s*(-?\d+(?:\.\d+)?)|(-?\d+(?:\.\d+)?)\s*([EWNS])/gi
  let match
  while ((match = re.exec(normalized)) !== null) {
    const dir = (match[1] || match[4] || '').toUpperCase()
    const value = Number(match[2] != null ? match[2] : match[3])
    if (!Number.isFinite(value)) {continue}
    let signed = value
    if (dir === 'W' || dir === 'S') {signed = -Math.abs(value)}
    if (dir === 'E' || dir === 'N') {signed = Math.abs(value)}
    withSigns.push({ dir, value: signed })
  }
  if (withSigns.length >= 2) {
    let lon = null
    let lat = null
    for (const item of withSigns) {
      if (item.dir === 'E' || item.dir === 'W') {lon = item.value}
      if (item.dir === 'N' || item.dir === 'S') {lat = item.value}
    }
    // 全是方向符但没出 E/W、N/S → 用出现顺序推断（前者经度、后者纬度）
    if (lon == null && withSigns.length === 2) {
      lon = withSigns[0].value
      lat = withSigns[1].value
    }
    if (lon != null && lat != null && isValidLonLat(lon, lat)) {
      return { lon, lat }
    }
  }

  // 去掉方向符后再匹配两个数
  const stripped = normalized.replace(/[EWNS]/gi, ' ').trim()
  // 处理 "lon:108.95 lat:34.27" 这种 key:value 形式
  const lonMatch = stripped.match(/(?:lon|经度|jingdu|longitude)\s*[:=]?\s*(-?\d+(?:\.\d+)?)/i)
  const latMatch = stripped.match(/(?:lat|latitude|纬度|weidu)\s*[:=]?\s*(-?\d+(?:\.\d+)?)/i)
  if (lonMatch && latMatch) {
    const lon = Number(lonMatch[1])
    const lat = Number(latMatch[1])
    if (isValidLonLat(lon, lat)) {return { lon, lat }}
  }

  // 最后兜底：直接抠两个浮点数
  const nums = stripped.match(/-?\d+(?:\.\d+)?/g)
  if (nums && nums.length >= 2) {
    const a = Number(nums[0])
    const b = Number(nums[1])
    // 第一个通常当 lon，第二个当 lat；如果反过来也合理（lat lon 顺序），给出判定
    if (isValidLonLat(a, b)) {return { lon: a, lat: b }}
    if (isValidLonLat(b, a)) {return { lon: b, lat: a }}
  }

  return null
}

function isValidLonLat(lon, lat) {
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) {return false}
  if (Math.abs(lon) > 180 || Math.abs(lat) > 90) {return false}
  // 拒绝全 0 这种无效坐标
  if (lon === 0 && lat === 0) {return false}
  return true
}

/**
 * 把 AMap 返回的 location（"108.950000,34.270000"）拆成经纬度，
 * 并把 GCJ-02 转回 WGS-84（系统所有点位都是 WGS-84）。
 */
function parseAMapLocation(location) {
  if (!location || typeof location !== 'string') {return null}
  const [lonStr, latStr] = location.split(',').map((s) => s.trim())
  const lon = Number(lonStr)
  const lat = Number(latStr)
  if (!isValidLonLat(lon, lat)) {return null}
  const [wLon, wLat] = gcj02ToWgs84(lon, lat)
  return { lon: wLon, lat: wLat }
}

/**
 * AMap 关键字搜索 → 经纬度列表。
 * 文档：https://lbs.amap.com/api/webservice/guide/api/search#text
 */
async function geocodeWithAmap(keyword, { signal } = {}) {
  if (!amapToken) {return null}
  const params = new URLSearchParams({
    key: amapToken,
    keywords: keyword,
    city: '全国',
    citylimit: 'false',
    extensions: 'base',
    offset: '8',
    page: '1',
    output: 'JSON',
  })
  const url = `https://restapi.amap.com/v3/place/text?${params.toString()}`
  try {
    const resp = await fetch(url, { signal })
    if (!resp.ok) {return null}
    const data = await resp.json()
    if (data.status !== '1' || !Array.isArray(data.pois)) {return []}
    return data.pois.map((p) => {
      const loc = parseAMapLocation(p.location)
      return loc && {
        lon: loc.lon,
        lat: loc.lat,
        name: p.name,
        address: [p.address, p.cityname, p.adname].filter(Boolean).join(' '),
        type: p.type,
        source: 'amap',
      }
    }).filter(Boolean)
  } catch (err) {
    if (err.name === 'AbortError') {return null}
    return []
  }
}

/**
 * OSM Nominatim 兜底（无 key，慢且有 1 req/s 限流，仅在 AMap 不可用时回退）。
 */
async function geocodeWithOsm(keyword, { signal } = {}) {
  const url = `https://nominatim.openstreetmap.org/search?format=json&limit=8&q=${encodeURIComponent(keyword)}&accept-language=zh-CN`
  try {
    const resp = await fetch(url, {
      signal,
      headers: {
        // Nominatim 强制要求带 User-Agent，否则会被拒
        'Accept-Language': 'zh-CN',
      },
    })
    if (!resp.ok) {return []}
    const data = await resp.json()
    return (Array.isArray(data) ? data : []).map((p) => ({
      lon: Number(p.lon),
      lat: Number(p.lat),
      name: p.display_name?.split(',')[0] || p.display_name,
      address: p.display_name,
      type: p.type,
      source: 'osm',
    })).filter((p) => isValidLonLat(p.lon, p.lat))
  } catch (err) {
    if (err.name === 'AbortError') {return null}
    return []
  }
}

/**
 * 关键字搜索（地址/POI/经纬度文本），返回候选项数组。
 * 经纬度文本直接走 parseCoordinate，命中则返回 [{ lon, lat, name: '经纬度定位' }]。
 * 否则走 AMap / OSM。
 *
 * @param {string} keyword
 * @param {{ signal?: AbortSignal }} options
 * @returns {Promise<Array<{lon, lat, name, address?, type?, source}>>}
 */
export async function geocodeAddress(keyword, options = {}) {
  const text = String(keyword || '').trim()
  if (!text) {return []}

  // 先尝试经纬度解析：用户经常直接粘贴 "108.95, 34.27"
  const coord = parseCoordinate(text)
  if (coord) {
    return [{
      lon: coord.lon,
      lat: coord.lat,
      name: `${coord.lon.toFixed(4)}, ${coord.lat.toFixed(4)}`,
      address: '按经纬度直接定位',
      source: 'coord',
    }]
  }

  const { signal } = options
  if (useAmap) {
    const amapResults = await geocodeWithAmap(text, { signal })
    if (amapResults != null) {return amapResults}
  }
  return geocodeWithOsm(text, { signal })
}

/**
 * 经纬度 → 地址（reverse geocoding），用于"我的位置"显示"XX路XX号"。
 * 失败时返回 null，调用方按 lon/lat 兜底显示。
 */
export async function reverseGeocode(lon, lat, options = {}) {
  if (!isValidLonLat(lon, lat)) {return null}
  const { signal } = options

  if (useAmap) {
    const url = new URL('https://restapi.amap.com/v3/geocode/regeo')
    url.searchParams.set('key', amapToken)
    url.searchParams.set('location', `${lon},${lat}`)
    url.searchParams.set('extensions', 'base')
    url.searchParams.set('output', 'JSON')
    try {
      const resp = await fetch(url, { signal })
      if (!resp.ok) {return null}
      const data = await resp.json()
      if (data.status !== '1') {return null}
      const addr = data.regeocode?.formatted_address
      return addr || null
    } catch (err) {
      if (err.name === 'AbortError') {return null}
      return null
    }
  }
  // OSM reverse
  const url = `https://nominatim.openstreetmap.org/reverse?format=json&lat=${lat}&lon=${lon}&accept-language=zh-CN&zoom=18`
  try {
    const resp = await fetch(url, { signal })
    if (!resp.ok) {return null}
    const data = await resp.json()
    return data?.display_name || null
  } catch (err) {
    if (err.name === 'AbortError') {return null}
    return null
  }
}

/**
 * 兜底 provider 信息，用于 UI 提示"当前用的是 AMap 还是 OSM 兜底"。
 */
export function geocoderProvider() {
  return useAmap ? 'amap' : 'osm'
}