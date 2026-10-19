/**
 * 国家气象数据服务（中央气象台 / NSMC / CMA 数据网）
 *
 * 数据来源（全部为官方公开资源，无需 API key）：
 *   1) 卫星云图  — NSMC 风云四号 A/B XML 清单，取最新一帧 JPG（中国区域 / 全圆盘）
 *      http://img.nsmc.org.cn/PORTAL/NSMC/XML/FY4A/...
 *   2) 雷达回波  — NMC 全国雷达拼图（预渲染 PNG），中央气象台 image.nmc.cn 静态产品
 *   3) 24h 降水  — NMC 24 小时降水量实况（预渲染 PNG）
 *   4) 24h 降雪  — NMC 24 小时积雪深度（预渲染 PNG）
 *   5) 干旱监测  — NMC 农业干旱综合监测（预渲染 PNG）
 *
 * 实现取舍：
 *   - CMA 数据网 BIN 瓦片（RADAR_L3_MST_CREF）原本是更精准的"实时"雷达，但前端要
 *     写 256x256 大端 int 解码器并自己栅格化渲染才能用；成本太高，业务场景不需要。
 *     退而求其次用 NMC 预渲染 PNG：每 6 分钟更新一次，覆盖中国全境。
 *   - 卫星云图每 15 分钟更新，雷达/降水每 6 分钟更新（这是 NMC 的发布间隔，不是
 *     我们的轮询频率）。前端缓存 6/15 分钟。
 *   - OL 的 ImageLayer 加载图片走 <img>，不需要 CORS（仅纯显示，不会触发 canvas
 *     readback，所以不会因 tainted canvas 而炸）。XML 清单走 fetch 文本流，必须
 *     经过 CORS 代理；这里先用可选的代理，代理挂了再退化到已知"取最新"的硬编码
 *     时间戳策略（仅 NMC PNG 产品）。
 *   - 默认代理：https://api.allorigins.win/raw?url=...（免费，但有速率限制；生产
 *     建议自建代理或在前端用 Vite plugin / dev proxy）。
 */

const NMC_BASE = 'https://image.nmc.cn'
const NSMC_MANIFEST_BASE = 'http://img.nsmc.org.cn/PORTAL/NSMC/XML'

// 是否走后端代理（cn.disasterguard.weather.WeatherController）。
// 默认与 VITE_USE_BACKEND 同步：true → 走 /api/weather/*；false → 直连 NSMC/NMC。
// 后端模式下，浏览器无需关心 CORS（图片源 URL 仍由后端返回，浏览器直接访问官方域）；
// 仅当官方源对浏览器域拒绝时才会回退到 /api/weather/image/{kind} 代理。
const USE_BACKEND = (() => {
  try {
    const v = import.meta.env?.VITE_USE_BACKEND
    if (v == null) {return false}
    return String(v).toLowerCase() === 'true' || v === true || v === 1
  } catch (_) {
    return false
  }
})()
const BACKEND_BASE = (() => {
  try {
    const base = import.meta.env?.VITE_API_BASE_URL || '/api'
    return String(base).replace(/\/$/, '').replace(/\/api$/, '') + '/api/weather'
  } catch (_) {
    return '/api/weather'
  }
})()

// CORS 代理（按优先级数组，逐一尝试直到成功）
const CORS_PROXIES = [
  '',
  'https://api.allorigins.win/raw?url=',
  'https://api.codetabs.com/v1/proxy?quest=',
  'https://corsproxy.io/?',
]

const _cache = new Map()

function cacheGet(key) {
  const entry = _cache.get(key)
  if (!entry) {return null}
  if (Date.now() - entry.ts > entry.ttl) {
    _cache.delete(key)
    return null
  }
  return entry.value
}

function cacheSet(key, value, ttl = 5 * 60 * 1000) {
  _cache.set(key, { ts: Date.now(), ttl, value })
  return value
}

async function fetchTextWithCorsFallback(url) {
  let lastErr
  for (const proxy of CORS_PROXIES) {
    const target = proxy ? (proxy + encodeURIComponent(url)) : url
    try {
      const res = await fetch(target, { mode: 'cors', credentials: 'omit' })
      if (!res.ok) {
        lastErr = new Error(`HTTP ${res.status}`)
        continue
      }
      const text = await res.text()
      return text
    } catch (e) {
      lastErr = e
    }
  }
  throw lastErr || new Error('All CORS proxies failed')
}

async function fetchXml(url) {
  const text = await fetchTextWithCorsFallback(url)
  const doc = new DOMParser().parseFromString(text, 'text/xml')
  return doc
}

async function fetchXmlViaProxyThenDirect(manifestUrl) {
  try {
    return await fetchXml(manifestUrl)
  } catch (_e) {
    // 退路：直接 fetch（无 CORS 头会被拒，但试一下也无妨）
    const res = await fetch(manifestUrl, { mode: 'cors', credentials: 'omit' })
    if (!res.ok) {throw new Error(`HTTP ${res.status}`)}
    const text = await res.text()
    return new DOMParser().parseFromString(text, 'text/xml')
  }
}

/**
 * NSMC XML 清单 → 取第一张（最新）图像的 url 与发布时间。
 */
async function fetchNsmcLatest(manifestPath) {
  const cacheKey = `nsmc:${manifestPath}`
  const cached = cacheGet(cacheKey)
  if (cached) {return cached}
  const xmlUrl = `${NSMC_MANIFEST_BASE}/${manifestPath}`
  const doc = await fetchXmlViaProxyThenDirect(xmlUrl)
  const node = doc.querySelector('image')
  if (!node) {throw new Error('NSMC manifest empty')}
  const url = node.getAttribute('url')
  const time = node.getAttribute('time') || ''
  const result = { url, time, fetchedAt: new Date().toISOString() }
  cacheSet(cacheKey, result, 15 * 60 * 1000)
  return result
}

/**
 * NMC 静态产品图：探测"近 6 小时"可访问的最新一帧 PNG/JPG。
 * 不同 kind 对应中央气象台不同的预渲染产品，URL 模式已知。
 */
async function fetchNmcProductLatest(kind) {
  const cacheKey = `nmc:product:${kind}`
  const cached = cacheGet(cacheKey)
  if (cached) {return cached}
  const now = new Date()
  for (let i = 0; i < 96; i++) {
    const t = new Date(now.getTime() - i * 6 * 60 * 1000)
    const yy = String(t.getFullYear())
    const mo = String(t.getMonth() + 1).padStart(2, '0')
    const dd = String(t.getDate()).padStart(2, '0')
    const hh = String(t.getHours()).padStart(2, '0')
    const mn = String(t.getMinutes()).padStart(2, '0')
    const candidate = buildNmcProductUrl(kind, yy, mo, dd, hh, mn)
    if (!candidate) {throw new Error(`Unknown NMC product kind: ${kind}`)}
    // 对纯图片的探测直接 fetch 走图片模式（用 HEAD，但 mode:no-cors 让浏览器不校验 CORS）
    try {
      // <img> 探测是最稳的（浏览器天然容忍跨域图片，且能判断尺寸是否有效）
      const ok = await new Promise((resolve) => {
        const img = new Image()
        let resolved = false
        const done = (v) => {
          if (resolved) {return}
          resolved = true
          resolve(v)
        }
        img.onload = () => done(img.naturalWidth > 32 && img.naturalHeight > 32)
        img.onerror = () => done(false)
        setTimeout(() => done(false), 4000)
        img.src = candidate
      })
      if (ok) {
        const result = {
          url: candidate,
          timestamp: `${yy}-${mo}-${dd} ${hh}:${mn}`,
          fetchedAt: new Date().toISOString(),
        }
        cacheSet(cacheKey, result, 30 * 60 * 1000)
        return result
      }
    } catch (_e) {
      // continue
    }
  }
  throw new Error(`NMC product ${kind}: no available slot in 6h`)
}

function buildNmcProductUrl(kind, yy, mm, dd, hh, mn) {
  switch (kind) {
    case 'satellite': {
      // 中央气象台云图（中国区域，FY-4 真彩合成）
      // URL 模式：image.nmc.cn/product/YYYY/MM/DD/WXCL/medium/SEVP_NSMC_WXCL_ASC_E99_ACHN_LNO_PY_YYYYMMDDHHMM00000.JPG
      // 这里给一个"近时次"地址；时效性由 fetchNmcProductLatest 的循环探测覆盖
      return `${NMC_BASE}/product/${yy}/${mm}/${dd}/WXCL/medium/SEVP_NSMC_WXCL_ASC_E99_ACHN_LNO_PY_${yy}${mm}${dd}${hh}${mn}00000.JPG`
    }
    case 'radar': {
      // 中央气象台全国雷达拼图（基本反射率）
      // URL 模式：image.nmc.cn/product/YYYY/MM/DD/RDP/medium/SEVP_NSMC_RDP_ACHN_LNO_PY_YYYYMMDDHHMM00000.JPG
      return `${NMC_BASE}/product/${yy}/${mm}/${dd}/RDP/medium/SEVP_NSMC_RDP_ACHN_LNO_PY_${yy}${mm}${dd}${hh}${mn}00000.JPG`
    }
    case 'precip24h': {
      // 24 小时降水量实况
      return `${NMC_BASE}/product/${yy}/${mm}/${dd}/RAIN24/medium/SEVP_NSMC_RAIN24_ACHN_LNO_PY_${yy}${mm}${dd}${hh}00000000.JPG`
    }
    case 'snow24h': {
      // 24 小时积雪深度
      return `${NMC_BASE}/product/${yy}/${mm}/${dd}/SDP/medium/SEVP_NSMC_SDP_ACHN_LNO_PY_${yy}${mm}${dd}1200000000.JPG`
    }
    case 'drought': {
      // 农业干旱综合监测
      return `${NMC_BASE}/product/${yy}/${mm}/${dd}/AGRI_DROUGHT/medium/SEVP_NSMC_AGRI_DROUGHT_ACHN_LNO_PY_${yy}${mm}${dd}1200000000.JPG`
    }
    default:
      return null
  }
}


/**
 * 卫星云图：风云四号 A 星 中国区域 多通道合成图。
 * 返回 { url, time, fetchedAt }
 */
export async function getSatelliteCloudLatest() {
  return fetchNsmcLatest('FY4A/FY4A_AGRI_IMG_REGI_MTCC_GLL.xml')
}

/**
 * 全圆盘卫星云图：覆盖东亚 + 西太平洋。
 */
export async function getSatelliteCloudDiskLatest() {
  return fetchNsmcLatest('FY4A/FY4A_AGRI_IMG_DISK_MTCC_NOM.xml')
}

/**
 * 雷达拼图（中央气象台全国拼图，NMC 预渲染 PNG）。
 */
export async function getRadarLatest() {
  return fetchNmcProductLatest('radar')
}

/**
 * 24 小时降水量实况。
 */
export async function getPrecipitation24hLatest() {
  return fetchNmcProductLatest('precip24h')
}

/**
 * 24 小时积雪深度。
 */
export async function getSnow24hLatest() {
  return fetchNmcProductLatest('snow24h')
}

/**
 * 干旱综合监测。
 */
export async function getDroughtLatest() {
  return fetchNmcProductLatest('drought')
}

/**
 * 一次性拉取五项最新可用信息，便于在地图初始化时一次性 overlay。
 * 任一项失败不抛错，返回的字段会标记 ok=false。
 */
export async function getAllWeatherProductsLatest() {
  const [satellite, satelliteDisk, radar, precip24h, snow24h, drought] = await Promise.allSettled([
    getSatelliteCloudLatest(),
    getSatelliteCloudDiskLatest(),
    getRadarLatest(),
    getPrecipitation24hLatest(),
    getSnow24hLatest(),
    getDroughtLatest(),
  ])
  return {
    satellite: wrapResult(satellite),
    satelliteDisk: wrapResult(satelliteDisk),
    radar: wrapResult(radar),
    precip24h: wrapResult(precip24h),
    snow24h: wrapResult(snow24h),
    drought: wrapResult(drought),
    fetchedAt: new Date().toISOString(),
  }
}

function wrapResult(settled) {
  if (settled.status === 'fulfilled') {
    return { ...settled.value, ok: true }
  }
  return { ok: false, error: settled.reason?.message || String(settled.reason) }
}

/*
 *  后端代理接入（cn.disasterguard.weather.WeatherController）
 *  - VITE_USE_BACKEND=true 时优先走 /api/weather/products
 *  - 后端返回结构与前端"自拉"模式兼容：每个 product 含 url/timestamp / 等字段
 *  - 图片源仍由官方域提供；前端 <img> 直连，与 NSMC/NMC 一致
  */

function normalizeBackendProduct(p) {
  if (!p) {return p}
  return {
    ok: !!p.ok,
    url: p.url || '',
    timestamp: p.timestamp || '',
    error: p.error || '',
    fetchedAt: p.fetchedAt || new Date().toISOString(),
    source: p.source || '',
    label: p.label || '',
    description: p.description || '',
    extent: p.extent
      ? [p.extent.minLon ?? p.extent[0], p.extent.minLat ?? p.extent[1], p.extent.maxLon ?? p.extent[2], p.extent.maxLat ?? p.extent[3]]
      : null,
    refreshIntervalMinutes: p.refreshIntervalMinutes ?? 0,
  }
}

async function readBackendProducts() {
  const url = `${BACKEND_BASE}/products`
  const res = await fetch(url, { credentials: 'include' })
  if (!res.ok) {
    throw new Error(`weather backend HTTP ${res.status}`)
  }
  const payload = await res.json()
  const list = Array.isArray(payload?.data?.products) ? payload.data.products : []
  const products = {}
  for (const p of list) {
    if (p && p.kind) {products[p.kind] = normalizeBackendProduct(p)}
  }
  return {
    products,
    fetchedAt: payload?.data?.fetchedAt || new Date().toISOString(),
    total: payload?.data?.total ?? list.length,
    available: payload?.data?.available ?? list.filter((x) => x.ok).length,
  }
}

async function readBackendLatest(kind) {
  const url = `${BACKEND_BASE}/products/latest?kind=${encodeURIComponent(kind)}`
  const res = await fetch(url, { credentials: 'include' })
  if (!res.ok) {throw new Error(`weather backend HTTP ${res.status}`)}
  const payload = await res.json()
  return normalizeBackendProduct(payload?.data)
}

/**
 * 一次性拉取全部 6 项产品的最新一帧。
 * - 后端模式：单次 REST 调用，后端做了缓存 + 后台预热
 * - 直连模式：并发调用各自的探测函数（前端自己也有缓存）
 * 任一项失败不抛错，返回的字段会标记 ok=false。
 */
export async function getAllProducts() {
  if (USE_BACKEND) {
    try {
      return await readBackendProducts()
    } catch (e) {
      // 后端挂掉时回退到直连模式，保证地图 overlay 不会整体崩
      console.warn('[weather] backend unavailable, fallback to direct:', e?.message)
    }
  }
  const [satellite, satelliteDisk, radar, precip24h, snow24h, drought] = await Promise.allSettled([
    getSatelliteCloudLatest(),
    getSatelliteCloudDiskLatest(),
    getRadarLatest(),
    getPrecipitation24hLatest(),
    getSnow24hLatest(),
    getDroughtLatest(),
  ])
  const products = {
    satellite: wrapResult(satellite),
    satelliteDisk: wrapResult(satelliteDisk),
    radar: wrapResult(radar),
    precip24h: wrapResult(precip24h),
    snow24h: wrapResult(snow24h),
    drought: wrapResult(drought),
  }
  return { products, fetchedAt: new Date().toISOString(), total: 6, available: Object.values(products).filter((p) => p.ok).length }
}

/** 拉取单个产品最新帧。后端模式下走 /api/weather/products/latest；否则走原有探测函数。 */
export async function getProductLatest(kind) {
  if (USE_BACKEND) {
    try {
      return await readBackendLatest(kind)
    } catch (e) {
      console.warn('[weather] backend latest failed, fallback to direct:', e?.message)
    }
  }
  switch (kind) {
    case 'satellite': return getSatelliteCloudLatest()
    case 'satelliteDisk': return getSatelliteCloudDiskLatest()
    case 'radar': return getRadarLatest()
    case 'precip24h': return getPrecipitation24hLatest()
    case 'snow24h': return getSnow24hLatest()
    case 'drought': return getDroughtLatest()
    default: throw new Error(`unknown weather product kind: ${kind}`)
  }
}

/** 当前是否启用后端代理（仅供 UI 调试提示用）。 */
export function isWeatherBackendEnabled() {
  return USE_BACKEND
}

/*
 *  地理范围（每张图在中国区域等经纬度坐标系下的外接矩形）
 *
 *  - FY-4A 中国区域云图：约 0–60°N, 70–140°E，等经纬度投影
 *  - FY-4A 全圆盘：约 60°S–60°N, 60°E–170°W，标称投影
 *  - NMC 雷达/降水/降雪/干旱：中国大陆及周边，标准等经纬度
 *
 *  这些范围用于 OpenLayers ImageLayer 的 imageExtent 配置。
  */

export const EXTENT_CHINA_FY4A = [70, 0, 140, 60]
export const EXTENT_FY4A_DISK = [60, -60, 220, 60]
export const EXTENT_CHINA_NMC = [73, 16, 135, 54]

/*
 *  图例配置
 *  各 overlay 的色档说明，给 UI 上的图例渲染用。
  */
export const WEATHER_LEGENDS = {
  radar: {
    title: '雷达回波',
    unit: 'dBZ',
    grades: [
      { color: '#6c4ad6', label: '≥ 45 dBZ 强降水' },
      { color: '#1d83e0', label: '35–45 dBZ 中等降水' },
      { color: '#27b07e', label: '20–35 dBZ 小雨' },
      { color: '#cad44e', label: '5–20 dBZ 微降水' },
    ],
  },
  precip24h: {
    title: '24h 降水量',
    unit: 'mm',
    grades: [
      { color: '#a80000', label: '≥ 100 mm 暴雨' },
      { color: '#d63b3b', label: '50–100 mm 大雨' },
      { color: '#e78c3b', label: '25–50 mm 中雨' },
      { color: '#dec331', label: '10–25 mm 小雨' },
      { color: '#3b78d6', label: '0.1–10 mm 微量' },
    ],
  },
  snow24h: {
    title: '24h 积雪',
    unit: 'cm',
    grades: [
      { color: '#ffffff', label: '≥ 20 cm 大雪' },
      { color: '#cce0ff', label: '10–20 cm 中雪' },
      { color: '#a8c4f0', label: '5–10 cm 小雪' },
      { color: '#dde6f0', label: '微量积雪' },
    ],
  },
  drought: {
    title: '农业干旱',
    unit: '等级',
    grades: [
      { color: '#a80000', label: '特旱' },
      { color: '#e57b21', label: '重旱' },
      { color: '#f0c045', label: '中旱' },
      { color: '#f5e692', label: '轻旱' },
    ],
  },
  satellite: {
    title: '风云四号 云图',
    unit: '可见光 + 红外合成',
    grades: [{ color: '#00000000', label: '白色越亮 = 云顶越高 / 越厚' }],
  },
}