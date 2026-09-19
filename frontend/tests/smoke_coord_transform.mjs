/**
 * Smoke test for coordTransform + parseCoordinate.
 * 用 Node 直接 require 编进 ESM 不现实，所以本文件用 vite-node 风格的纯函数形式，
 * 仅依赖 utils/coordTransform.js（纯函数）和 geocodeService.js 中的 parseCoordinate。
 *
 * 跑法：在 src/utils/coordTransform.js 和 src/services/geocodeService.js 同目录下
 *        node ../../scripts/_smoke_map.mjs（实际是在临时脚本里跑）。
 */
import { wgs84ToGcj02, gcj02ToWgs84, outOfChina } from '../src/utils/coordTransform.js'

const cases = []

function expect(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  cases.push({ label, ok, actual, expected })
  console.log(`${ok ? '✓' : '✗'} ${label}  →  ${JSON.stringify(actual)}${ok ? '' : '   ❗expected=' + JSON.stringify(expected)}`)
}

// ---- coordTransform ----

// 1. 境外坐标应当原样返回（WGS-84 == GCJ-02）
expect('境外纽约 WGS→GCJ 不偏移', wgs84ToGcj02(-74.006, 40.7128), [-74.006, 40.7128])
expect('境外纽约 GCJ→WGS 不偏移', gcj02ToWgs84(-74.006, 40.7128), [-74.006, 40.7128])

// 2. 境内坐标应当存在 50–500m 偏移（单向）
const [gLon, gLat] = wgs84ToGcj02(108.95, 34.27)
const offsetKm = Math.hypot(gLon - 108.95, gLat - 34.27) * 111
console.log(`  ℹ 西安 108.95,34.27 偏移量 ≈ ${offsetKm.toFixed(3)}°×111 ≈ ${(offsetKm).toFixed(2)}km`)
if (offsetKm < 0.05 || offsetKm > 1.5) {
  console.log('  ❗ 偏移量超出合理范围（50m–1500m）')
}

// 3. GCJ→WGS→GCJ 来回应当基本还原
const [wLon, wLat] = gcj02ToWgs84(gLon, gLat)
const residual = Math.hypot(wLon - 108.95, wLat - 34.27)
expect('GCJ→WGS→GCJ 反向收敛', residual < 1e-6, true)

// 4. outOfChina 边界
expect('境内点 not outOfChina', outOfChina(108.95, 34.27), false)
expect('境外点 outOfChina', outOfChina(-74, 40), true)

console.log('\n' + (cases.every((c) => c.ok) ? '✅ All passed' : `❌ ${cases.filter((c) => !c.ok).length} failed`))

// ---- parseCoordinate (从 geocodeService.js 复制实现，单测) ----
function isValidLonLat(lon, lat) {
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) return false
  if (Math.abs(lon) > 180 || Math.abs(lat) > 90) return false
  if (lon === 0 && lat === 0) return false
  return true
}
function parseCoordinate(text) {
  if (typeof text !== 'string') return null
  const trimmed = text.trim()
  if (!trimmed) return null
  let normalized = trimmed.replace(/[，；]/g, ' ').replace(/[\\/]/g, ' ').replace(/[、]/g, ' ').replace(/[°]/g, ' ').replace(/[;]/g, ' ').trim()
  const withSigns = []
  const re = /([EWNS])\s*(-?\d+(?:\.\d+)?)|(-?\d+(?:\.\d+)?)\s*([EWNS])/gi
  let match
  while ((match = re.exec(normalized)) !== null) {
    const dir = (match[1] || match[4] || '').toUpperCase()
    const value = Number(match[2] != null ? match[2] : match[3])
    if (!Number.isFinite(value)) continue
    let signed = value
    if (dir === 'W' || dir === 'S') signed = -Math.abs(value)
    if (dir === 'E' || dir === 'N') signed = Math.abs(value)
    withSigns.push({ dir, value: signed })
  }
  if (withSigns.length >= 2) {
    let lon = null, lat = null
    for (const item of withSigns) {
      if (item.dir === 'E' || item.dir === 'W') lon = item.value
      if (item.dir === 'N' || item.dir === 'S') lat = item.value
    }
    if (lon == null && withSigns.length === 2) { lon = withSigns[0].value; lat = withSigns[1].value }
    if (lon != null && lat != null && isValidLonLat(lon, lat)) return { lon, lat }
  }
  let stripped = normalized.replace(/[EWNS]/gi, ' ').trim()
  const lonMatch = stripped.match(/(?:lon|经度|jingdu|longitude)\s*[:=]?\s*(-?\d+(?:\.\d+)?)/i)
  const latMatch = stripped.match(/(?:lat|latitude|纬度|weidu)\s*[:=]?\s*(-?\d+(?:\.\d+)?)/i)
  if (lonMatch && latMatch) {
    const lon = Number(lonMatch[1])
    const lat = Number(latMatch[1])
    if (isValidLonLat(lon, lat)) return { lon, lat }
  }
  const nums = stripped.match(/-?\d+(?:\.\d+)?/g)
  if (nums && nums.length >= 2) {
    const a = Number(nums[0])
    const b = Number(nums[1])
    if (isValidLonLat(a, b)) return { lon: a, lat: b }
    if (isValidLonLat(b, a)) return { lon: b, lat: a }
  }
  return null
}

console.log('\n—— parseCoordinate ——')
expect('108.95, 34.27', parseCoordinate('108.95, 34.27'), { lon: 108.95, lat: 34.27 })
expect('108.95  34.27', parseCoordinate('108.95  34.27'), { lon: 108.95, lat: 34.27 })
expect('108.95/34.27', parseCoordinate('108.95/34.27'), { lon: 108.95, lat: 34.27 })
expect('108.95，34.27', parseCoordinate('108.95，34.27'), { lon: 108.95, lat: 34.27 })
expect('E108.95 N34.27', parseCoordinate('E108.95 N34.27'), { lon: 108.95, lat: 34.27 })
expect('108.95E 34.27N', parseCoordinate('108.95E 34.27N'), { lon: 108.95, lat: 34.27 })
expect('108.95°E 34.27°N', parseCoordinate('108.95°E 34.27°N'), { lon: 108.95, lat: 34.27 })
expect('W74.0 N40.7', parseCoordinate('W74.0 N40.7'), { lon: -74, lat: 40.7 })
expect('lon:108.95 lat:34.27', parseCoordinate('lon:108.95 lat:34.27'), { lon: 108.95, lat: 34.27 })
expect('经度108.95 纬度34.27', parseCoordinate('经度108.95 纬度34.27'), { lon: 108.95, lat: 34.27 })
expect('乱输入 返回 null', parseCoordinate('hello world'), null)
expect('空字符串 返回 null', parseCoordinate(''), null)
expect('只有一个数字 返回 null', parseCoordinate('108.95'), null)
expect('超过 180° 返回 null', parseCoordinate('200, 30'), null)
expect('0,0 返回 null（避免误识别）', parseCoordinate('0,0'), null)

console.log('\n' + (cases.every((c) => c.ok) ? '✅ All passed' : `❌ ${cases.filter((c) => !c.ok).length} failed`))