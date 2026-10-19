/**
 * 设备地图视图服务（v1.9.0 / P1 设备地图与轨迹回放）。
 *
 * 数据源优先级：
 *   1) 后端 Java /api/device-map/* （真实）
 *   2) 内置 mock 生成器（兜底演示）
 *
 * 调用示例：
 *   const map = await fetchDeviceMap()
 *   const trj = await fetchDeviceTrajectory('DRONE-001')
 *
 * mock 模式下：
 *   - fetchDeviceMap() 返回 12 台设备（约 4 台标记为移动）
 *   - fetchDeviceTrajectory(code) 自动生成一条 24h 的轨迹
 *   - 多次调用同样的 deviceCode 结果完全一致（确定性）
 */
import { withQuery } from '../../../utils/query.js'

const DEFAULT_API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api'

const DEVICE_MAP_ENDPOINT = '/device-map'
const TRAJECTORY_ENDPOINT = (code) => `/device-map/trajectory/${encodeURIComponent(code)}`
const REGENERATE_ENDPOINT = '/device-map/trajectory/regenerate'

/**
 * @typedef {Object} DeviceMapItem
 * @property {string} deviceCode
 * @property {string} deviceName
 * @property {string} deviceType
 * @property {string} [mobilityKind]
 * @property {string} onlineStatus
 * @property {number} [batteryLevel]
 * @property {string} [signalLevel]
 * @property {string} [hazardCode]
 * @property {string} [regionCode]
 * @property {number} longitude
 * @property {number} latitude
 * @property {number} [speedKmh]
 * @property {number} [headingDeg]
 * @property {string} [lastReportAt]
 * @property {boolean} mobile
 */

/**
 * @typedef {Object} DeviceMapResponse
 * @property {DeviceMapItem[]} devices
 * @property {{ total:number, online:number, offline:number, fault:number, mobile:number, fixed:number, trajectoryDevices:number }} stats
 * @property {string} generatedAt
 */

/**
 * @typedef {Object} TrajectoryPoint
 * @property {number} longitude
 * @property {number} latitude
 * @property {string} recordedAt
 * @property {number} [speedKmh]
 * @property {number} [headingDeg]
 * @property {number} [batteryLevel]
 * @property {string} [source]
 */

/**
 * 拉取设备地图视图（设备列表 + 最新位置 + 统计）。
 *
 * @param {string} [baseUrl]  可选覆盖 API base（便于测试）
 * @returns {Promise<DeviceMapResponse>}
 */
export async function fetchDeviceMap(baseUrl) {
  try {
    const url = `${baseUrl || DEFAULT_API_BASE}${DEVICE_MAP_ENDPOINT}`
    const resp = await fetchWithAuth(url, { method: 'GET' })
    if (resp.ok) {
      const payload = await resp.json().catch(() => null)
      if (payload && payload.code === 0 && payload.data) {
        return payload.data
      }
    }
  } catch (_e) {
    // ignore, fallback to mock
  }
  return generateMockDeviceMap()
}

/**
 * 拉取单设备轨迹（按时间范围，最多 maxPoints 个点）。
 *
 * @param {string} deviceCode
 * @param {{ start?: string, end?: string, maxPoints?: number }} [options]
 * @param {string} [baseUrl]
 * @returns {Promise<{ deviceCode:string, totalPoints:number, points: TrajectoryPoint[], mock: boolean }>}
 */
export async function fetchDeviceTrajectory(deviceCode, options = {}, baseUrl) {
  if (!deviceCode) {
    return { deviceCode: '', totalPoints: 0, points: [], mock: true }
  }
  try {
    const url = `${baseUrl || DEFAULT_API_BASE}${TRAJECTORY_ENDPOINT(deviceCode)}${withQuery({
      start: options.start,
      end: options.end,
      maxPoints: options.maxPoints,
    })}`
    const resp = await fetchWithAuth(url, { method: 'GET' })
    if (resp.ok) {
      const payload = await resp.json().catch(() => null)
      if (payload && payload.code === 0 && payload.data) {
        return { ...payload.data, mock: false }
      }
    }
  } catch (_e) {
    // fallback
  }
  return { deviceCode, totalPoints: 0, points: generateMockTrajectory(deviceCode), mock: true }
}

/**
 * 触发后端重新生成轨迹（仅后端生效；mock 模式下空操作）。
 */
export async function regenerateTrajectories(baseUrl) {
  try {
    const url = `${baseUrl || DEFAULT_API_BASE}${REGENERATE_ENDPOINT}`
    const resp = await fetchWithAuth(url, { method: 'POST' })
    if (resp.ok) {
      const payload = await resp.json().catch(() => null)
      if (payload && payload.code === 0) {return payload.data}
    }
  } catch (_e) {
    // ignore
  }
  return null
}

/**
 * 把设备列表按在线状态着色。返回给前端用于 marker 颜色选择。
 */
export function statusColor(onlineStatus) {
  if (onlineStatus === '在线') {return '#16a34a'}
  if (onlineStatus === '离线') {return '#94a3b8'}
  if (onlineStatus === '故障') {return '#dc2626'}
  return '#64748b'
}

/**
 * 把移动子类型映射为图标。返回 SVG 字符串或 emoji，UI 自行渲染。
 */
export function mobilityIcon(mobilityKind) {
  if (mobilityKind === 'drone') {return 'UAV'}
  if (mobilityKind === 'vehicle') {return 'CAR'}
  if (mobilityKind === 'handheld') {return 'HAND'}
  return 'DEV'
}

// 内部：fetch + JWT 注入

function fetchWithAuth(url, options = {}) {
  const token = (() => {
    try { return localStorage.getItem('disasterguard_token') || '' } catch (_e) { return '' }
  })()
  const headers = {
    Accept: 'application/json',
    ...(token && token.split('.').length === 3 ? { Authorization: `Bearer ${token}` } : {}),
    ...(options.headers || {}),
  }
  return fetch(url, { ...options, headers, credentials: 'include' })
}

// Mock 数据（确定性：同样的 deviceCode 总得到同样的轨迹）

function generateMockDeviceMap() {
  const nowIso = new Date().toISOString()
  // 12 台设备，4 台移动（drone/vehicle/handheld/vehicle）
  const presets = [
    { deviceCode: 'GPS-001', deviceName: '北斗一号 监测站', deviceType: '固定倾斜仪', onlineStatus: '在线', region: 'CHENGDU-N', lon: 104.0612, lat: 30.5728, battery: 95, signal: '强' },
    { deviceCode: 'GPS-002', deviceName: '北斗二号 监测站', deviceType: '固定雨量计', onlineStatus: '在线', region: 'CHENGDU-S', lon: 104.0809, lat: 30.5493, battery: 88, signal: '强' },
    { deviceCode: 'GPS-003', deviceName: '北斗三号 监测站', deviceType: '固定GNSS', onlineStatus: '离线', region: 'CHENGDU-W', lon: 104.0321, lat: 30.5801, battery: 12, signal: '弱' },
    { deviceCode: 'DRONE-001', deviceName: '巡查无人机-01', deviceType: '固定翼无人机', mobilityKind: 'drone', onlineStatus: '在线', region: 'MOBILE', lon: 104.068, lat: 30.567, battery: 76, signal: '强' },
    { deviceCode: 'DRONE-002', deviceName: '巡查无人机-02', deviceType: '四旋翼无人机', mobilityKind: 'drone', onlineStatus: '在线', region: 'MOBILE', lon: 104.075, lat: 30.580, battery: 62, signal: '中' },
    { deviceCode: 'VEHICLE-001', deviceName: '应急通信车-A', deviceType: '应急通信车', mobilityKind: 'vehicle', onlineStatus: '在线', region: 'MOBILE', lon: 104.052, lat: 30.556, battery: 90, signal: '强' },
    { deviceCode: 'VEHICLE-002', deviceName: '指挥车-B', deviceType: '现场指挥车', mobilityKind: 'vehicle', onlineStatus: '在线', region: 'MOBILE', lon: 104.069, lat: 30.594, battery: 84, signal: '强' },
    { deviceCode: 'HANDHELD-001', deviceName: '手持终端-技术员甲', deviceType: '手持巡检终端', mobilityKind: 'handheld', onlineStatus: '在线', region: 'MOBILE', lon: 104.058, lat: 30.572, battery: 45, signal: '中' },
    { deviceCode: 'RAIN-001', deviceName: '翻斗雨量计', deviceType: '固定翻斗雨量', onlineStatus: '在线', region: 'CHENGDU-E', lon: 104.1102, lat: 30.5918, battery: 100, signal: '强' },
    { deviceCode: 'RAIN-002', deviceName: '翻斗雨量计', deviceType: '固定翻斗雨量', onlineStatus: '故障', region: 'CHENGDU-W', lon: 104.0301, lat: 30.5420, battery: 0, signal: '无' },
    { deviceCode: 'CAMERA-001', deviceName: '高位监控-岷江边', deviceType: '固定摄像机', onlineStatus: '在线', region: 'CHENGDU-N', lon: 104.0901, lat: 30.6012, battery: null, signal: '强' },
    { deviceCode: 'CAMERA-002', deviceName: '高位监控-龙泉山', deviceType: '固定摄像机', onlineStatus: '在线', region: 'CHENGDU-S', lon: 104.0212, lat: 30.5301, battery: null, signal: '中' },
  ]
  const devices = presets.map((p) => {
    const mobile = !!p.mobilityKind
    return {
      deviceCode: p.deviceCode,
      deviceName: p.deviceName,
      deviceType: p.deviceType,
      mobilityKind: p.mobilityKind || null,
      onlineStatus: p.onlineStatus,
      batteryLevel: p.battery,
      signalLevel: p.signal,
      hazardCode: null,
      regionCode: p.region,
      longitude: p.lon,
      latitude: p.lat,
      speedKmh: mobile ? Math.round(Math.random() * 25 * 10) / 10 : null,
      headingDeg: mobile ? Math.round(Math.random() * 360) : null,
      lastReportAt: nowIso,
      mobile,
    }
  })
  return {
    devices,
    stats: {
      total: devices.length,
      online: devices.filter((d) => d.onlineStatus === '在线').length,
      offline: devices.filter((d) => d.onlineStatus === '离线').length,
      fault: devices.filter((d) => d.onlineStatus === '故障').length,
      mobile: devices.filter((d) => d.mobile).length,
      fixed: devices.filter((d) => !d.mobile).length,
      trajectoryDevices: devices.filter((d) => d.mobile).length,
    },
    generatedAt: nowIso,
  }
}

function generateMockTrajectory(deviceCode, samples = 60) {
  const seed = hash(deviceCode)
  const baseLon = 104.06 + ((seed >>> 16) & 0xFF) / 255 * 0.08
  const baseLat = 30.57 + ((seed >>> 8) & 0xFF) / 255 * 0.06
  const now = Date.now()
  const stepMs = (24 * 3600 * 1000) / samples

  const points = []
  let lon = baseLon
  let lat = baseLat
  for (let i = 0; i < samples; i++) {
    // 随机游走
    lon += (rand(seed + i) - 0.5) * 0.002
    lat += (rand(seed + i + 1) - 0.5) * 0.0015
    // 拉回起点
    lon = lon * 0.95 + baseLon * 0.05
    lat = lat * 0.95 + baseLat * 0.05
    const t = new Date(now - (samples - i) * stepMs).toISOString()
    points.push({
      longitude: Math.round(lon * 1e6) / 1e6,
      latitude: Math.round(lat * 1e6) / 1e6,
      recordedAt: t,
      speedKmh: Math.round(rand(seed + i + 2) * 25 * 10) / 10,
      headingDeg: Math.round(rand(seed + i + 3) * 360),
      batteryLevel: Math.max(20, Math.round(100 - (i / samples) * 60)),
      source: 'MOCK',
    })
  }
  return points
}

function hash(s) {
  let h = 0
  for (let i = 0; i < (s || '').length; i++) {
    h = ((h << 5) - h) + s.charCodeAt(i)
    h |= 0
  }
  return h >>> 0
}

function rand(n) {
  const x = Math.sin(n) * 10000
  return x - Math.floor(x)
}
