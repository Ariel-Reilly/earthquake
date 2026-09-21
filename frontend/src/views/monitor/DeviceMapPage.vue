<script setup>
/**
 * 设备地图与轨迹回放（v1.9.0 / P1）。
 *
 * 三栏布局：
 *   - 左：设备列表（按状态/移动性过滤）
 *   - 中：OpenLayers 地图 + 设备标记 + 选中设备的轨迹线
 *   - 右：选中设备详情 + 轨迹时间轴 + 回放控制
 *
 * 数据流：
 *   fetchDeviceMap()  → 设备列表 + 当前快照位置
 *   fetchDeviceTrajectory(code) → 单设备轨迹
 *
 * 自动回放：选中移动设备后，"播放" 按时间正序逐点移动地图上的"当前位置"标记。
 */

import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  Radar,
  AlertCircle,
  Battery,
  BatteryCharging,
  Car,
  ChevronRight,
  CircleHelp,
  Clock,
  Compass,
  Crosshair,
  Cpu,
  Eye,
  EyeOff,
  Filter,
  Gauge,
  Hand,
  Loader2,
  MapPin,
  Pause,
  Plane,
  Play,
  Radio,
  RefreshCw,
  Search,
  Signal,
  Wand2,
  Target,
  Trash2,
  Video,
  X,
  Zap,
} from 'lucide-vue-next'
import {
  fetchDeviceMap,
  fetchDeviceTrajectory,
  regenerateTrajectories,
  statusColor,
  mobilityIcon,
} from '../../services/deviceMapService'

const emit = defineEmits(['notify'])
const loading = ref(true)
const mapRef = ref(null)
const searchKeyword = ref('')
const statusFilter = ref('全部')  // 全部 | 在线 | 离线 | 故障
const mobilityFilter = ref('全部') // 全部 | 固定 | 移动

const deviceMap = ref(null)
const selectedCode = ref('')
const trajectory = ref(null)
const trajectoryLoading = ref(false)
const isPlaying = ref(false)
const playProgress = ref(0)
const playSpeed = ref(4)  // 0.5x | 1x | 2x | 4x | 8x
const showTrajectory = ref(true)
const showHeatmap = ref(false)

const timeRange = ref('24h')  // 1h | 6h | 24h | 7d

// OpenLayers 模块（懒加载）
const ol = reactive({
  loaded: false,
  Map: null,
  View: null,
  TileLayer: null,
  OSM: null,
  VectorLayer: null,
  VectorSource: null,
  Feature: null,
  Point: null,
  LineString: null,
  fromLonLat: null,
  Style: null,
  CircleStyle: null,
  Fill: null,
  Stroke: null,
  Text: null,
  defaults: null,
})

let map = null
let mapView = null
let deviceSource = null
let trajectorySource = null
let currentPosFeature = null
let playTimer = null
let resizeObserver = null

const filteredDevices = computed(() => {
  if (!deviceMap.value) return []
  let list = deviceMap.value.devices
  if (statusFilter.value !== '全部') list = list.filter((d) => d.onlineStatus === statusFilter.value)
  if (mobilityFilter.value === '固定') list = list.filter((d) => !d.mobile)
  if (mobilityFilter.value === '移动') list = list.filter((d) => d.mobile)
  if (searchKeyword.value && searchKeyword.value.trim()) {
    const kw = searchKeyword.value.toLowerCase()
    list = list.filter((d) =>
      (d.deviceName || '').toLowerCase().includes(kw) ||
      (d.deviceCode || '').toLowerCase().includes(kw) ||
      (d.deviceType || '').toLowerCase().includes(kw)
    )
  }
  return list
})

const stats = computed(() => deviceMap.value?.stats || { total: 0, online: 0, offline: 0, fault: 0, mobile: 0, fixed: 0, trajectoryDevices: 0 })

const selectedDevice = computed(() => {
  if (!selectedCode.value) return null
  return deviceMap.value?.devices.find((d) => d.deviceCode === selectedCode.value) || null
})

const trajectoryPoints = computed(() => trajectory.value?.points || [])

// OpenLayers 懒加载
async function loadOpenLayers() {
  if (ol.loaded) return
  const [
    { default: olMap },
    { default: olView },
    { default: Tile },
    OSM,
    { default: VectorLayer },
    { default: VectorSource },
    { default: Feature },
    { default: Point },
    { default: LineString },
    proj,
    styleMod,
    controlMod,
  ] = await Promise.all([
    import('ol/Map'),
    import('ol/View'),
    import('ol/layer/Tile'),
    import('ol/source/OSM'),
    import('ol/layer/Vector'),
    import('ol/source/Vector'),
    import('ol/Feature'),
    import('ol/geom/Point'),
    import('ol/geom/LineString'),
    import('ol/proj'),
    import('ol/style'),
    import('ol/control'),
  ])
  ol.Map = olMap
  ol.View = olView
  ol.TileLayer = Tile
  ol.OSM = OSM
  ol.VectorLayer = VectorLayer
  ol.VectorSource = VectorSource
  ol.Feature = Feature
  ol.Point = Point
  ol.LineString = LineString
  ol.fromLonLat = proj.fromLonLat
  ol.Style = styleMod.Style
  ol.CircleStyle = styleMod.Circle
  ol.Fill = styleMod.Fill
  ol.Stroke = styleMod.Stroke
  ol.Text = styleMod.Text
  ol.defaults = controlMod.defaults
  ol.loaded = true
}

async function initMap() {
  await loadOpenLayers()
  if (!mapRef.value) return
  // 默认中心：成都（demo 数据基本都集中在 104.06 / 30.57）
  mapView = new ol.View({
    center: ol.fromLonLat([104.065, 30.575]),
    zoom: 11,
    minZoom: 4,
    maxZoom: 19,
  })
  deviceSource = new ol.VectorSource()
  trajectorySource = new ol.VectorSource()

  map = new ol.Map({
    target: mapRef.value,
    layers: [
      new ol.TileLayer({ source: new ol.OSM() }),
      new ol.VectorLayer({
        source: trajectorySource,
        zIndex: 5,
      }),
      new ol.VectorLayer({
        source: deviceSource,
        zIndex: 10,
      }),
    ],
    view: mapView,
    controls: ol.defaults({ attribution: false }),
  })
}

function buildDeviceFeatures() {
  if (!deviceSource || !ol.loaded) return
  deviceSource.clear()
  if (!deviceMap.value) return
  for (const d of deviceMap.value.devices) {
    const isSelected = d.deviceCode === selectedCode.value
    const color = statusColor(d.onlineStatus)
    const radius = isSelected ? 9 : (d.mobile ? 7 : 5)

    const feature = new ol.Feature({
      geometry: new ol.Point(ol.fromLonLat([d.longitude, d.latitude])),
      deviceCode: d.deviceCode,
      deviceName: d.deviceName,
    })
    feature.setId(d.deviceCode)
    feature.setStyle(new ol.Style({
      image: new ol.CircleStyle({
        radius,
        fill: new ol.Fill({ color }),
        stroke: new ol.Stroke({
          color: isSelected ? '#f97316' : '#ffffff',
          width: isSelected ? 3 : 1.5,
        }),
      }),
      text: new ol.Text({
        text: d.mobile ? (mobilityIcon(d.mobilityKind) || '移动') : '',
        font: 'bold 9px sans-serif',
        fill: new ol.Fill({ color: '#ffffff' }),
        offsetY: 1,
      }),
    }))
    deviceSource.addFeature(feature)
  }
}

function selectDevice(code, { fitView = true } = {}) {
  selectedCode.value = code
  if (!code) {
    trajectorySource.clear()
    if (currentPosFeature) {
      currentPosFeature = null
    }
    return
  }
  const device = deviceMap.value?.devices.find((d) => d.deviceCode === code)
  if (device && fitView) {
    mapView.animate({
      center: ol.fromLonLat([device.longitude, device.latitude]),
      zoom: Math.max(mapView.getZoom(), 13),
      duration: 600,
    })
  }
  buildDeviceFeatures()
  loadTrajectory(code)
}

async function loadTrajectory(code) {
  if (!code) return
  trajectoryLoading.value = true
  try {
    const trj = await fetchDeviceTrajectory(code, {
      maxPoints: 500,
    })
    trajectory.value = trj
    drawTrajectory(trj.points || [])
  } finally {
    trajectoryLoading.value = false
  }
}

function drawTrajectory(points) {
  if (!trajectorySource || !ol.loaded) return
  trajectorySource.clear()
  if (!points.length) return
  const line = new ol.Feature({
    geometry: new ol.LineString(points.map((p) => ol.fromLonLat([p.longitude, p.latitude]))),
  })
  line.setStyle(new ol.Style({
    stroke: new ol.Stroke({ color: '#f97316', width: 3, lineDash: [6, 4] }),
  }))
  trajectorySource.addFeature(line)

  // 在轨迹上画起点 + 终点标记
  const start = points[0]
  const end = points[points.length - 1]
  trajectorySource.addFeature(new ol.Feature({
    geometry: new ol.Point(ol.fromLonLat([start.longitude, start.latitude])),
  }).setStyle(new ol.Style({
    image: new ol.CircleStyle({ radius: 6, fill: new ol.Fill({ color: '#10b981' }), stroke: new ol.Stroke({ color: '#ffffff', width: 2 }) }),
  })))
  trajectorySource.addFeature(new ol.Feature({
    geometry: new ol.Point(ol.fromLonLat([end.longitude, end.latitude])),
  }).setStyle(new ol.Style({
    image: new ol.CircleStyle({ radius: 6, fill: new ol.Fill({ color: '#dc2626' }), stroke: new ol.Stroke({ color: '#ffffff', width: 2 }) }),
  })))

  // 当前播放位置 feature（默认隐藏）
  currentPosFeature = new ol.Feature({
    geometry: new ol.Point(ol.fromLonLat([end.longitude, end.latitude])),
  })
  currentPosFeature.setStyle(new ol.Style({
    image: new ol.CircleStyle({ radius: 9, fill: new ol.Fill({ color: '#facc15' }), stroke: new ol.Stroke({ color: '#1f2d3d', width: 2 }) }),
    text: new ol.Text({
      text: '当前',
      font: 'bold 10px sans-serif',
      fill: new ol.Fill({ color: '#1f2d3d' }),
      offsetY: -16,
    }),
  }))
  trajectorySource.addFeature(currentPosFeature)

  // 自动 zoom 适配到整条轨迹
  const extent = trajectorySource.getExtent()
  if (extent && extent.every(Number.isFinite)) {
    mapView.fit(extent, { padding: [60, 60, 60, 60], duration: 600, maxZoom: 16 })
  }

  playProgress.value = 1
}

// 数据加载
async function loadAll() {
  loading.value = true
  try {
    const data = await fetchDeviceMap()
    deviceMap.value = data
    buildDeviceFeatures()
  } catch (e) {
    emit?.('notify', '设备地图加载失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function refreshTrajectories() {
  const result = await regenerateTrajectories()
  if (result && result.generated > 0) {
    emit?.('notify', `已生成 ${result.generated} 条轨迹采样点`)
  }
  await loadAll()
}

// 回放控制
function togglePlay() {
  if (!trajectoryPoints.value.length) return
  if (isPlaying.value) {
    pausePlay()
  } else {
    startPlay()
  }
}

function startPlay() {
  if (!trajectoryPoints.value.length) return
  if (playProgress.value >= 1) playProgress.value = 0
  isPlaying.value = true
  const speedMap = { 0.5: 600, 1: 300, 2: 150, 4: 75, 8: 40 }
  const stepMs = speedMap[playSpeed.value] || 200
  const totalSteps = trajectoryPoints.value.length
  let step = Math.floor(playProgress.value * totalSteps)
  const tick = () => {
    if (!isPlaying.value) return
    if (step >= totalSteps) {
      pausePlay()
      return
    }
    const p = trajectoryPoints.value[step]
    if (currentPosFeature && ol.loaded) {
      currentPosFeature.getGeometry().setCoordinates(ol.fromLonLat([p.longitude, p.latitude]))
    }
    step++
    playProgress.value = step / totalSteps
    playTimer = setTimeout(tick, stepMs)
  }
  tick()
}

function pausePlay() {
  isPlaying.value = false
  if (playTimer) {
    clearTimeout(playTimer)
    playTimer = null
  }
}

function resetPlay() {
  pausePlay()
  playProgress.value = 0
  if (currentPosFeature && trajectoryPoints.value[0] && ol.loaded) {
    const p = trajectoryPoints.value[0]
    currentPosFeature.getGeometry().setCoordinates(ol.fromLonLat([p.longitude, p.latitude]))
  }
}

function seekTo(progress) {
  playProgress.value = progress
  if (!trajectoryPoints.value.length || !currentPosFeature || !ol.loaded) return
  const totalSteps = trajectoryPoints.value.length
  const step = Math.floor(progress * totalSteps)
  const p = trajectoryPoints.value[Math.min(step, totalSteps - 1)]
  currentPosFeature.getGeometry().setCoordinates(ol.fromLonLat([p.longitude, p.latitude]))
  if (isPlaying.value) {
    pausePlay()
    startPlay()
  }
}

// 时间范围选择
const timeRangeLabel = computed(() => {
  return {
    '1h': '最近 1 小时',
    '6h': '最近 6 小时',
    '24h': '最近 24 小时',
    '7d': '最近 7 天',
  }[timeRange.value] || '最近 24 小时'
})

function setTimeRange(range) {
  timeRange.value = range
  if (selectedCode.value) loadTrajectory(selectedCode.value)
}

function mobilityBadge(mobile, kind) {
  if (!mobile) return { label: '固定', icon: Radio, color: '#0ea5e9' }
  if (kind === 'drone') return { label: '无人机', icon: Plane, color: '#7c3aed' }
  if (kind === 'vehicle') return { label: '车辆', icon: Car, color: '#ea580c' }
  if (kind === 'handheld') return { label: '手持', icon: Hand, color: '#16a34a' }
  return { label: '移动', icon: Compass, color: '#64748b' }
}

function fmtTime(iso) {
  if (!iso) return '—'
  try {
    const d = new Date(iso)
    const pad = (n) => String(n).padStart(2, '0')
    return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  } catch (_e) { return iso }
}

function batteryColor(level) {
  if (level == null) return '#94a3b8'
  if (level >= 60) return '#16a34a'
  if (level >= 30) return '#f59e0b'
  return '#dc2626'
}

// 生命周期
onMounted(async () => {
  await loadAll()
  await initMap()
  buildDeviceFeatures()
  // 自动适应地图大小
  if (window.ResizeObserver && mapRef.value) {
    resizeObserver = new ResizeObserver(() => map?.updateSize())
    resizeObserver.observe(mapRef.value)
  }
})

onBeforeUnmount(() => {
  pausePlay()
  if (resizeObserver) resizeObserver.disconnect()
  if (map) {
    map.setTarget(null)
    map = null
  }
})
</script>

<template>
  <div class="devicemap-page">
    <!-- Header -->
    <header class="dm-header">
      <div class="dm-title">
        <MapPin :size="22" />
        <div>
        </div>
      </div>
      <div class="dm-actions">
        <button class="dm-btn" :disabled="loading" @click="loadAll">
          <RefreshCw v-if="loading" :size="14" class="spin" />
          <RefreshCw v-else :size="14" />
          <span>刷新</span>
        </button>
        <button class="dm-btn dm-btn-ghost" @click="refreshTrajectories">
          <Wand2 :size="14" />
          <span>回填轨迹</span>
        </button>
      </div>
    </header>

    <!-- Stat cards -->
    <section class="dm-stats">
      <article class="dm-stat" v-for="card in [
        { label: '总设备数', value: stats.total,           icon: Cpu,         color: '#0ea5e9' },
        { label: '在线',     value: stats.online,          icon: Radar,        color: '#16a34a' },
        { label: '离线',     value: stats.offline,         icon: X,           color: '#94a3b8' },
        { label: '故障',     value: stats.fault,           icon: AlertCircle, color: '#dc2626' },
        { label: '移动设备', value: stats.mobile,          icon: Plane,       color: '#7c3aed' },
        { label: '已记录轨迹', value: stats.trajectoryDevices, icon: Compass, color: '#ea580c' },
      ]" :key="card.label">
        <div class="dm-stat-icon" :style="{ background: card.color + '1a', color: card.color }">
          <component :is="card.icon" :size="18" />
        </div>
        <div class="dm-stat-body">
          <span class="dm-stat-label">{{ card.label }}</span>
          <strong class="dm-stat-value">{{ card.value }}</strong>
        </div>
      </article>
    </section>

    <!-- Main 3-column layout -->
    <div class="dm-main">
      <!-- 设备列表 -->
      <aside class="dm-device-list">
        <header class="dm-list-header">
          <h2>
            <Filter :size="14" />
            <span>设备列表</span>
            <span class="dm-pill">{{ filteredDevices.length }}</span>
          </h2>
          <div class="dm-search">
            <Search :size="12" />
            <input v-model="searchKeyword" type="text" placeholder="名称/编码/类型" />
          </div>
          <div class="dm-filter-row">
            <select v-model="statusFilter" class="dm-select">
              <option>全部</option>
              <option>在线</option>
              <option>离线</option>
              <option>故障</option>
            </select>
            <select v-model="mobilityFilter" class="dm-select">
              <option>全部</option>
              <option>固定</option>
              <option>移动</option>
            </select>
          </div>
        </header>
        <ul class="dm-devices" v-if="filteredDevices.length">
          <li
            v-for="d in filteredDevices"
            :key="d.deviceCode"
            class="dm-device-item"
            :class="{ active: d.deviceCode === selectedCode }"
            @click="selectDevice(d.deviceCode)"
          >
            <span class="dm-device-dot" :style="{ background: statusColor(d.onlineStatus) }"></span>
            <div class="dm-device-info">
              <div class="dm-device-name">
                <span>{{ d.deviceName }}</span>
                <component
                  :is="mobilityBadge(d.mobile, d.mobilityKind).icon"
                  :size="12"
                  :color="mobilityBadge(d.mobile, d.mobilityKind).color"
                />
              </div>
              <div class="dm-device-meta">
                <code>{{ d.deviceCode }}</code>
                <span>{{ d.deviceType }}</span>
              </div>
            </div>
            <div class="dm-device-tail">
              <span class="dm-battery" v-if="d.batteryLevel != null" :style="{ color: batteryColor(d.batteryLevel) }">
                <Battery :size="11" />
                <span>{{ d.batteryLevel }}%</span>
              </span>
              <ChevronRight :size="12" />
            </div>
          </li>
        </ul>
        <div v-else class="dm-empty">
          <CircleHelp :size="20" />
          <p>没有匹配的设备</p>
        </div>
      </aside>

      <!-- Map -->
      <section class="dm-canvas-section">
        <div ref="mapRef" class="dm-map"></div>
        <div class="dm-map-legend">
          <div><span class="dm-dot" style="background:#16a34a"></span>在线</div>
          <div><span class="dm-dot" style="background:#94a3b8"></span>离线</div>
          <div><span class="dm-dot" style="background:#dc2626"></span>故障</div>
          <div><span class="dm-dash"></span>轨迹线</div>
          <div><span class="dm-dot" style="background:#facc15"></span>当前位置</div>
        </div>
        <div v-if="trajectoryPoints.length && showTrajectory" class="dm-trajectory-meta">
          <Compass :size="14" />
          <span>{{ trajectoryPoints.length }} 个采样点</span>
          <span v-if="trajectory?.mock" class="dm-mock-tag">MOCK</span>
          <button class="dm-btn dm-btn-sm" @click="showTrajectory = !showTrajectory">
            <component :is="showTrajectory ? Eye : EyeOff" :size="12" />
          </button>
        </div>
      </section>

      <!-- 右侧：选中详情 + 轨迹回放 -->
      <aside class="dm-detail">
        <article class="dm-card" v-if="selectedDevice">
          <header class="dm-card-header">
            <h3>{{ selectedDevice.deviceName }}</h3>
            <span class="dm-status-chip" :style="{ background: statusColor(selectedDevice.onlineStatus) + '24', color: statusColor(selectedDevice.onlineStatus) }">
              {{ selectedDevice.onlineStatus }}
            </span>
          </header>
          <div class="dm-card-body dm-detail-grid">
            <div><span>编码</span><strong><code>{{ selectedDevice.deviceCode }}</code></strong></div>
            <div><span>类型</span><strong>{{ selectedDevice.deviceType }}</strong></div>
            <div>
              <span>归属</span>
              <strong>{{ selectedDevice.mobilityKind ? mobilityBadge(true, selectedDevice.mobilityKind).label : '固定' }}</strong>
            </div>
            <div>
              <span>电量</span>
              <strong :style="{ color: batteryColor(selectedDevice.batteryLevel) }">
                <BatteryCharging :size="12" v-if="selectedDevice.batteryLevel > 80" />
                <Battery :size="12" v-else />
                {{ selectedDevice.batteryLevel ?? '—' }}%
              </strong>
            </div>
            <div><span>信号</span><strong><Signal :size="11" /> {{ selectedDevice.signalLevel || '—' }}</strong></div>
            <div><span>最近上报</span><strong>{{ fmtTime(selectedDevice.lastReportAt) }}</strong></div>
            <div v-if="selectedDevice.speedKmh != null"><span>速度</span><strong>{{ selectedDevice.speedKmh }} km/h</strong></div>
            <div v-if="selectedDevice.headingDeg != null"><span>方向</span><strong>{{ selectedDevice.headingDeg }}°</strong></div>
            <div class="dm-span"><span>当前位置</span><strong><code>{{ selectedDevice.longitude.toFixed(4) }}, {{ selectedDevice.latitude.toFixed(4) }}</code></strong></div>
          </div>
        </article>

        <article class="dm-card" v-if="selectedDevice && selectedDevice.mobile">
          <header class="dm-card-header">
            <Compass :size="14" />
            <h3>轨迹回放</h3>
            <select v-model="timeRange" class="dm-select dm-select-sm" @change="setTimeRange(timeRange)">
              <option value="1h">1h</option>
              <option value="6h">6h</option>
              <option value="24h">24h</option>
              <option value="7d">7d</option>
            </select>
          </header>

          <div class="dm-card-body">
            <div v-if="trajectoryLoading" class="dm-loading">
              <Loader2 :size="20" class="spin" />
              <span>正在加载轨迹…</span>
            </div>
            <div v-else-if="!trajectoryPoints.length" class="dm-empty">
              <CircleHelp :size="20" />
              <p>暂无轨迹数据，点击上方"回填轨迹"可生成演示轨迹。</p>
            </div>
            <div v-else>
              <div class="dm-progress-wrap">
                <input
                  type="range"
                  min="0"
                  max="1000"
                  :value="Math.round(playProgress * 1000)"
                  @input="seekTo($event.target.value / 1000)"
                  class="dm-progress"
                />
                <div class="dm-progress-labels">
                  <span>{{ fmtTime(trajectoryPoints[0].recordedAt) }}</span>
                  <span>{{ fmtTime(trajectoryPoints[trajectoryPoints.length - 1].recordedAt) }}</span>
                </div>
              </div>

              <div class="dm-controls">
                <button class="dm-btn dm-btn-primary" @click="togglePlay">
                  <component :is="isPlaying ? Pause : Play" :size="14" />
                  <span>{{ isPlaying ? '暂停' : '播放' }}</span>
                </button>
                <button class="dm-btn" @click="resetPlay">
                  <Trash2 :size="14" />
                  <span>重置</span>
                </button>
                <div class="dm-speed">
                  <span class="dm-control-label">速度</span>
                  <select v-model.number="playSpeed" class="dm-select dm-select-sm">
                    <option :value="0.5">0.5x</option>
                    <option :value="1">1x</option>
                    <option :value="2">2x</option>
                    <option :value="4">4x</option>
                    <option :value="8">8x</option>
                  </select>
                </div>
              </div>

              <!-- 当前点信息 -->
              <div class="dm-current-point" v-if="trajectoryPoints[Math.floor(playProgress * trajectoryPoints.length)]">
                <h4>当前位置</h4>
                <div class="dm-current-grid">
                  <div><span>经纬度</span><strong><code>{{ trajectoryPoints[Math.floor(playProgress * trajectoryPoints.length)].longitude.toFixed(4) }}, {{ trajectoryPoints[Math.floor(playProgress * trajectoryPoints.length)].latitude.toFixed(4) }}</code></strong></div>
                  <div><span>时间</span><strong>{{ fmtTime(trajectoryPoints[Math.floor(playProgress * trajectoryPoints.length)].recordedAt) }}</strong></div>
                  <div v-if="trajectoryPoints[Math.floor(playProgress * trajectoryPoints.length)].speedKmh != null"><span>速度</span><strong>{{ trajectoryPoints[Math.floor(playProgress * trajectoryPoints.length)].speedKmh }} km/h</strong></div>
                  <div v-if="trajectoryPoints[Math.floor(playProgress * trajectoryPoints.length)].batteryLevel != null"><span>电量</span><strong>{{ trajectoryPoints[Math.floor(playProgress * trajectoryPoints.length)].batteryLevel }}%</strong></div>
                </div>
              </div>
            </div>
          </div>
        </article>

        <article class="dm-card" v-if="!selectedDevice">
          <div class="dm-empty">
            <Crosshair :size="32" />
            <p>请在左侧列表选择一台设备，查看详情与轨迹</p>
          </div>
        </article>

        <article class="dm-card dm-card-tip">
          <header class="dm-card-header">
            <Zap :size="14" />
            <h3>操作提示</h3>
          </header>
          <ul class="dm-tips">
            <li><Plane :size="11" /> 紫色 = 无人机；橙色 = 车辆；绿色 = 手持终端。</li>
            <li><Compass :size="11" /> 点击移动设备后可在右侧进行轨迹回放，进度可拖拽。</li>
            <li><RefreshCw :size="11" /> "回填轨迹"用于演示数据为空时自动生成 24h 轨迹。</li>
            <li><MapPin :size="11" /> 点击列表项自动定位到设备位置并展开回放。</li>
          </ul>
        </article>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.devicemap-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  background: #f5f7fb;
  min-height: 100%;
  height: 100%;
  box-sizing: border-box;
}

/* ===== Header ===== */
.dm-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #ffffff;
  border-radius: 12px;
  padding: 14px 18px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}
.dm-title { display: flex; align-items: center; gap: 12px; color: #1f2d3d; }
.dm-title h1 { font-size: 18px; font-weight: 600; margin: 0; }
.dm-title p { font-size: 12px; color: #94a3b8; margin: 2px 0 0; }
.dm-actions { display: flex; gap: 8px; }

.dm-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 6px;
  border: 1px solid #d8e0ec;
  background: #ffffff;
  color: #1f2d3d;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}
.dm-btn:hover:not(:disabled) { border-color: #2563eb; color: #2563eb; }
.dm-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.dm-btn-primary { background: #2563eb; color: #ffffff; border-color: transparent; }
.dm-btn-primary:hover:not(:disabled) { color: #ffffff; }
.dm-btn-ghost { background: transparent; }
.dm-btn-sm { padding: 3px 8px; font-size: 11px; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }

/* ===== Stats ===== */
.dm-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}
.dm-stat {
  background: #ffffff;
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  align-items: center;
  gap: 12px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}
.dm-stat-icon {
  width: 36px; height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.dm-stat-body { display: flex; flex-direction: column; }
.dm-stat-label { font-size: 11px; color: #94a3b8; font-weight: 600; }
.dm-stat-value { font-size: 18px; font-weight: 700; color: #1f2d3d; }

/* ===== Main 3-column ===== */
.dm-main {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr) 340px;
  gap: 12px;
  flex: 1;
  min-height: 0;
}
@media (max-width: 1280px) {
  .dm-main { grid-template-columns: 280px minmax(0, 1fr) 300px; }
}
@media (max-width: 1024px) {
  .dm-main { grid-template-columns: 1fr; grid-template-rows: auto 400px auto; }
}

/* ===== 设备列表 ===== */
.dm-device-list {
  background: #ffffff;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}
.dm-list-header {
  padding: 12px 14px;
  border-bottom: 1px solid #f1f5f9;
}
.dm-list-header h2 {
  font-size: 13px;
  font-weight: 600;
  color: #1f2d3d;
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px;
}
.dm-pill {
  margin-left: auto;
  background: #2563eb;
  color: #ffffff;
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 10px;
}
.dm-search {
  display: flex;
  align-items: center;
  gap: 4px;
  background: #f1f5f9;
  padding: 4px 8px;
  border-radius: 6px;
  margin-bottom: 6px;
  color: #94a3b8;
}
.dm-search input { background: transparent; border: none; outline: none; font-size: 12px; flex: 1; }
.dm-filter-row { display: flex; gap: 6px; }
.dm-select {
  flex: 1;
  padding: 4px 6px;
  border: 1px solid #d8e0ec;
  border-radius: 6px;
  background: #ffffff;
  font-size: 12px;
}
.dm-select-sm { padding: 2px 6px; font-size: 11px; }

.dm-devices {
  list-style: none;
  margin: 0;
  padding: 8px;
  overflow-y: auto;
  flex: 1;
}
.dm-device-item {
  display: grid;
  grid-template-columns: 10px 1fr auto;
  gap: 8px;
  align-items: center;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}
.dm-device-item:hover { background: #f8fafc; }
.dm-device-item.active {
  background: #dbeafe;
  border-left: 3px solid #2563eb;
}
.dm-device-dot {
  width: 10px; height: 10px;
  border-radius: 50%;
  box-shadow: 0 0 0 2px #ffffff;
}
.dm-device-name {
  font-size: 12px;
  font-weight: 600;
  color: #1f2d3d;
  display: flex;
  align-items: center;
  gap: 6px;
}
.dm-device-meta {
  font-size: 10px;
  color: #94a3b8;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
}
.dm-device-meta code {
  background: #e2e8f0;
  color: #1f2d3d;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 10px;
}
.dm-device-tail {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #94a3b8;
}
.dm-battery { display: inline-flex; align-items: center; gap: 2px; font-size: 10px; }

.dm-empty {
  padding: 30px 12px;
  text-align: center;
  color: #94a3b8;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

/* ===== Map ===== */
.dm-canvas-section {
  position: relative;
  background: #ffffff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
  min-height: 480px;
}
.dm-map { position: absolute; inset: 0; }
.dm-map-legend {
  position: absolute;
  bottom: 12px;
  left: 12px;
  background: rgba(255, 255, 255, 0.95);
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 11px;
  color: #1f2d3d;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  z-index: 1;
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
.dm-map-legend > div { display: inline-flex; align-items: center; gap: 4px; }
.dm-dot {
  width: 10px; height: 10px;
  border-radius: 50%;
  display: inline-block;
}
.dm-dash {
  display: inline-block;
  width: 18px; height: 2px;
  background: linear-gradient(to right, #f97316 50%, transparent 50%);
  background-size: 6px 100%;
}
.dm-trajectory-meta {
  position: absolute;
  top: 12px;
  left: 12px;
  background: rgba(255, 255, 255, 0.95);
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 11px;
  color: #1f2d3d;
  display: flex;
  align-items: center;
  gap: 8px;
  z-index: 1;
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
.dm-mock-tag {
  background: #f97316;
  color: #ffffff;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 700;
}

/* ===== Detail ===== */
.dm-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  max-height: 100%;
}
.dm-card {
  background: #ffffff;
  border-radius: 12px;
  padding: 12px 14px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}
.dm-card-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  color: #1f2d3d;
}
.dm-card-header h3 { font-size: 13px; font-weight: 600; margin: 0; }
.dm-status-chip {
  margin-left: auto;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}
.dm-card-tip { background: #fef3c7; }

.dm-detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 12px;
}
.dm-detail-grid > div {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: #94a3b8;
}
.dm-detail-grid > div span { font-size: 10px; }
.dm-detail-grid > div strong {
  color: #1f2d3d;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.dm-detail-grid > div code {
  background: #f1f5f9;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 11px;
}
.dm-span { grid-column: 1 / -1; }

.dm-loading {
  padding: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #94a3b8;
  font-size: 12px;
}

.dm-progress-wrap { margin: 12px 0; }
.dm-progress {
  width: 100%;
  height: 6px;
  appearance: none;
  -webkit-appearance: none;
  background: #f1f5f9;
  border-radius: 3px;
  outline: none;
}
.dm-progress::-webkit-slider-thumb {
  appearance: none;
  -webkit-appearance: none;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #f97316;
  cursor: pointer;
}
.dm-progress-labels {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: #94a3b8;
  margin-top: 4px;
}

.dm-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.dm-speed {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.dm-control-label { font-size: 11px; color: #94a3b8; }

.dm-current-point {
  background: #fef3c7;
  border-radius: 6px;
  padding: 8px 10px;
  margin-top: 8px;
}
.dm-current-point h4 {
  font-size: 11px;
  color: #92400e;
  margin: 0 0 6px;
  font-weight: 700;
}
.dm-current-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px 12px;
  font-size: 11px;
}
.dm-current-grid > div {
  display: flex;
  flex-direction: column;
  color: #94a3b8;
}
.dm-current-grid > div span { font-size: 10px; }
.dm-current-grid > div strong { color: #1f2d3d; font-size: 11px; }
.dm-current-grid code { background: rgba(0,0,0,0.05); padding: 1px 3px; border-radius: 3px; font-size: 10px; }

.dm-tips {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.dm-tips li {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 11px;
  color: #78350f;
  line-height: 1.4;
}
</style>
