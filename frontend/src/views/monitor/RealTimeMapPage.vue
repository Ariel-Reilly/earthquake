<script setup>
/**
 * 实时监测地图（独立页面 /dashboard/real-time-map）。
 *
 * 把感知层全部要素放在一张可交互大地图上：
 *   - 多底图切换（矢量 / 卫星 / 地形）
 *   - 多图层叠加（气象 / 设备 / 灾害 / 预警 / AI 实时监测）
 *   - 时间轴回放
 *   - 空间查询与测距
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue"
import {
  Calendar,
  Circle,
  Clock,
  Layers,
  Loader2,
  MapPin,
  Maximize2,
  Minus,
  Plus,
  Radio,
  RefreshCw,
  Ruler,
  Satellite,
  Cpu,
  Target,
  X,
  AlertCircle,
  Map,
} from "lucide-vue-next"
import { createOpenLayersController } from "../../services/mapService"
import { dashboardApi } from "../../services/dashboardApi"
import { onRealtimeUpdate, startRealtimeSync, stopRealtimeSync } from "../../services/realtimeService"

defineProps({
  currentTime: { type: String, default: "" },
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])

const mapContainer = ref(null)
let mapController = null
const mapReady = ref(false)
const mapStyle = ref("vector")
const loading = ref(true)
const mapError = ref(null)
const refreshing = ref(false)
const showLayers = ref(true)
const showLegend = ref(true)

const layerState = reactive({
  hazard: true,
  device: true,
  warning: true,
  aiMonitor: true,
  weather: false,
  personnel: false,
})

const timeline = ref([])
const timelineIndex = ref(0)
const isPlaying = ref(false)
let playTimer = null

const stats = reactive({ hazards: 0, devices: 0, warnings: 0, ai: 0, total: 0 })
const aiMonitorCount = ref(0)

async function bootstrapMap() {
  if (!mapContainer.value) return
  mapError.value = null
  try {
    mapController = await createOpenLayersController(mapContainer.value, {
      baseMap: mapStyle.value,
    })
    // 瓦片加载失败如实提示，并由 mapService 自动切换到备用影像源
    mapController.onTileError?.((message) => emit("notify", message))
    mapReady.value = true
    await loadAllOverlays()
    buildTimeline()
  } catch (e) {
    mapError.value = e?.message || "地图初始化失败"
    emit("notify", mapError.value)
  } finally {
    loading.value = false
  }
}

async function loadAllOverlays() {
  if (!mapController) return
  try {
    const [hazards, devices, warnings, ai] = await Promise.all([
      dashboardApi.getHazardsByViewport?.({}).catch(() => []),
      dashboardApi.getDevicesByViewport?.({}).catch(() => []),
      dashboardApi.getWarningsByViewport?.({}).catch(() => []),
      dashboardApi.getAiMonitorHazards?.().catch(() => []),
    ])
    const hz = hazards ?? []
    const dv = devices ?? []
    const wn = warnings ?? []
    const am = ai ?? []
    stats.hazards = hz.length
    stats.devices = dv.length
    stats.warnings = wn.length
    stats.ai = am.length
    stats.total = hz.length + dv.length + wn.length
    aiMonitorCount.value = am.length

    // 真实 API：setHazards / setDevices / setWarnings（mapService.js 返回的 controller）
    mapController.setHazards?.(hz)
    mapController.setDevices?.(dv)
    mapController.setWarnings?.(wn)
    // AI 监控点没有专用 setter，复用 hazards 层（数据源不同，靠 source 字段区分样式）
    if (am && am.length) {
      mapController.setHazards?.([...(hz || []), ...am])
    }
  } catch (e) {
    emit("notify", e?.message ?? "加载地图数据失败")
  }
}

async function refresh() {
  refreshing.value = true
  try {
    await loadAllOverlays()
    emit("notify", "地图已刷新")
  } finally {
    refreshing.value = false
  }
}

function changeMapStyle(style) {
  if (style === mapStyle.value) return
  mapStyle.value = style
  // 真实 API：setMode；getMode 也存在；旧名 setBaseStyle/setBaseMap 都不存在
  mapController?.setMode?.(style)
  emit("notify", style === "satellite" ? "卫星影像：纯影像模式" : "卫星影像：已开启地名注记")
}

function toggleLayer(key) {
  layerState[key] = !layerState[key]
  if (key === "hazard") mapController?.setLayerVisible?.("hazard", layerState.hazard)
  else if (key === "device") mapController?.setLayerVisible?.("device", layerState.device)
  else if (key === "warning") mapController?.setLayerVisible?.("warning", layerState.warning)
  else if (key === "aiMonitor") mapController?.setLayerVisible?.("aiMonitor", layerState.aiMonitor)
  else if (key === "weather") {
    layerState.weather ? mapController?.showWeatherOverlay?.() : mapController?.hideWeatherOverlay?.()
  }
}

function zoomIn() { mapController?.zoomIn?.() }
function zoomOut() { mapController?.zoomOut?.() }
// 真实 API：fitItems；旧名 fitAllHazards 不存在
function fitView() { mapController?.fitItems?.(mapController.getItems ? mapController.getItems() : []) }

function buildTimeline() {
  const now = Date.now()
  timeline.value = Array.from({ length: 24 }, (_, i) => {
    const t = new Date(now - (23 - i) * 60 * 60 * 1000)
    return { ts: t.toISOString(), label: `${t.getHours()}:00` }
  })
  timelineIndex.value = timeline.value.length - 1
}

function applyTimeline() {
  const frame = timeline.value[timelineIndex.value]
  if (!frame) return
  emit("notify", `时间轴已跳转到 ${frame.label}`)
}

function togglePlay() {
  if (isPlaying.value) {
    clearInterval(playTimer)
    playTimer = null
    isPlaying.value = false
  } else {
    isPlaying.value = true
    playTimer = setInterval(() => {
      timelineIndex.value = (timelineIndex.value + 1) % timeline.value.length
      applyTimeline()
    }, 1500)
  }
}

watch(timelineIndex, applyTimeline)

let stopRealtime = null
function onRealtime(payload) {
  if (payload?.type === "hazard" && payload.data) {
    loadAllOverlays()
  } else if (payload?.type === "poll") {
    loadAllOverlays()
  }
}

onMounted(async () => {
  await nextTick()
  await bootstrapMap()
  startRealtimeSync()
  stopRealtime = onRealtimeUpdate(onRealtime)
})

onBeforeUnmount(() => {
  if (stopRealtime) stopRealtime()
  stopRealtimeSync()
  if (playTimer) clearInterval(playTimer)
  if (trackPlayTimer) clearInterval(trackPlayTimer)
  if (mapController?.destroy) mapController.destroy()
  if (trackMapCtrl?.destroy) trackMapCtrl.destroy()
})

// 底图只有合规卫星影像一种数据源；两个模式分别控制是否叠加地名注记。
// 旧的「地形」选项是假选项（setBaseMap 会把它当成矢量处理，UI 却提示已切地形），已移除。
const mapStyleOptions = [
  { id: "vector", label: "影像+注记", icon: MapPin },
  { id: "satellite", label: "纯影像", icon: Satellite },
]

const layerMeta = [
  { key: "hazard", label: "灾害点", color: "#c7553b" },
  { key: "device", label: "监测设备", color: "#6f9e7f" },
  { key: "warning", label: "预警事件", color: "#c4a86e" },
  { key: "aiMonitor", label: "AI 实时监测", color: "#9b761e" },
  { key: "weather", label: "气象叠加", color: "#5d95c1" },
  { key: "personnel", label: "值守人员", color: "#4a7a5e" },
]

// 设备轨迹回放 Tab 状态
const trackTabActive = ref(false)
const trackDevices = ref([])
const trackDeviceId = ref(null)
const trackFrom = ref("")
const trackTo = ref("")
const trackSpeed = ref("1")
const trackPlaying = ref(false)
const trackProgress = ref(0) // 0-100
const trackLoading = ref(false)
const trackError = ref(null)
const trackPoints = ref([])
let trackPlayTimer = null
let trackMapCtrl = null
const trackMapContainer = ref(null)

async function loadTrackDevices() {
  try {
    const data = await dashboardApi.getDevices({ page: 1, size: 200 })
    trackDevices.value = data.list ?? []
    if (trackDevices.value.length && !trackDeviceId.value) {
      trackDeviceId.value = trackDevices.value[0].id
    }
  } catch {
    trackDevices.value = []
  }
}

function initDefaultTrackRange() {
  const now = new Date()
  const yesterday = new Date(now.getTime() - 20 * 60 * 1000)
  const fmt = (d) => d.toISOString().slice(0, 16)
  trackFrom.value = fmt(yesterday)
  trackTo.value = fmt(now)
}

async function loadTrackData() {
  if (!trackDeviceId.value || !trackFrom.value || !trackTo.value) return
  trackLoading.value = true
  trackError.value = null
  try {
    const data = await dashboardApi.getDeviceTrack(trackDeviceId.value, {
      from: trackFrom.value,
      to: trackTo.value,
    })
    trackPoints.value = Array.isArray(data) ? data : []
    if (trackPoints.value.length === 0) {
      trackError.value = "未找到该设备在指定时间范围内的轨迹数据"
    }
  } catch (e) {
    trackError.value = e?.message || "加载轨迹数据失败"
    trackPoints.value = []
  } finally {
    trackLoading.value = false
  }
}

function startTrackPlayback() {
  if (trackPoints.value.length < 2) return
  trackPlaying.value = true
  const interval = 2000 / Number(trackSpeed.value)
  let idx = 0
  trackProgress.value = 0
  clearInterval(trackPlayTimer)
  trackPlayTimer = setInterval(() => {
    idx++
    trackProgress.value = Math.min(100, Math.round((idx / (trackPoints.value.length - 1)) * 100))
    if (idx >= trackPoints.value.length - 1) {
      clearInterval(trackPlayTimer)
      trackPlayTimer = null
      trackPlaying.value = false
    }
  }, interval)
}

function stopTrackPlayback() {
  clearInterval(trackPlayTimer)
  trackPlayTimer = null
  trackPlaying.value = false
}

function toggleTrackPlay() {
  if (trackPlaying.value) {
    stopTrackPlayback()
  } else {
    if (trackProgress.value >= 100) trackProgress.value = 0
    startTrackPlayback()
  }
}

function onTrackScrub(e) {
  const val = Number(e.target.value)
  trackProgress.value = val
}

function exportTrackGeoJSON() {
  if (!trackPoints.value.length) return
  const geo = {
    type: "FeatureCollection",
    features: [
      {
        type: "Feature",
        geometry: {
          type: "LineString",
          coordinates: trackPoints.value.map((p) => [p.lon, p.lat]),
        },
        properties: {
          deviceId: trackDeviceId.value,
          from: trackFrom.value,
          to: trackTo.value,
          pointCount: trackPoints.value.length,
        },
      },
    ],
  }
  const blob = new Blob([JSON.stringify(geo, null, 2)], { type: "application/json" })
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = `device-${trackDeviceId.value}-track.geojson`
  a.click()
  URL.revokeObjectURL(url)
}

watch(trackDeviceId, loadTrackData)
watch([trackFrom, trackTo], () => {
  if (trackDeviceId.value) loadTrackData()
})
</script>

<template>
  <div class="real-time-map-shell">
    <header class="page-header">
      <div class="header-left">
        <h2 class="page-title">实时监测地图</h2>
        <!-- Tab 切换：地图 / 设备轨迹回放 -->
        <div class="inline-tabs">
          <button
            class="inline-tab"
            :class="{ active: !trackTabActive }"
            @click="trackTabActive = false"
          >地图视图</button>
          <button
            class="inline-tab"
            :class="{ active: trackTabActive }"
            @click="trackTabActive = true; loadTrackDevices(); initDefaultTrackRange()"
          >设备轨迹回放</button>
        </div>
      </div>
      <div class="actions">
        <div class="map-style-switch">
          <button
            v-for="opt in mapStyleOptions"
            :key="opt.id"
            :class="{ active: mapStyle === opt.id }"
            @click="changeMapStyle(opt.id)"
          >
            <component :is="opt.icon" :size="13" />{{ opt.label }}
          </button>
        </div>
        <button class="op-btn" :disabled="refreshing" @click="refresh">
          <RefreshCw :size="14" :class="refreshing ? 'spin' : ''" />刷新
        </button>
      </div>
    </header>

    <div class="map-layout">
      <aside v-if="showLayers" class="layer-panel">
        <div class="layer-header">
          <Layers :size="14" />图层管理
          <button class="icon-btn" @click="showLayers = false"><X :size="12" /></button>
        </div>
        <ul>
          <li v-for="m in layerMeta" :key="m.key" :class="{ disabled: !layerState[m.key] }">
            <label>
              <input type="checkbox" :checked="layerState[m.key]" @change="toggleLayer(m.key)" />
              <span class="dot" :style="{ background: m.color }"></span>
              <span class="lbl">{{ m.label }}</span>
              <span class="count">{{ stats[m.key] ?? 0 }}</span>
            </label>
          </li>
        </ul>

        <div class="layer-header sub">
          <Target :size="14" />空间工具
        </div>
        <div class="tool-row">
          <button class="op-btn" @click="zoomIn"><Plus :size="14" /></button>
          <button class="op-btn" @click="zoomOut"><Minus :size="14" /></button>
          <button class="op-btn" @click="fitView"><Maximize2 :size="14" />全图</button>
        </div>

        <div class="legend" v-if="showLegend">
          <div class="layer-header sub"><Circle :size="14" />图例</div>
          <ul class="legend-list">
            <li><span class="dot dot--hazard"></span>高风险灾害</li>
            <li><span class="dot dot--warning"></span>关注灾害</li>
            <li><span class="dot dot--brand"></span>正常状态</li>
            <li><span class="dot dot--brand"></span>监测设备</li>
            <li><span class="dot dot--ai"></span>AI 实时监测</li>
          </ul>
        </div>
      </aside>
      <button v-else class="layer-toggle" @click="showLayers = true"><Layers :size="14" /></button>

      <div class="map-stage" v-if="!trackTabActive">
        <!-- 加载中状态 -->
        <div v-if="loading" class="map-loading">
          <Loader2 :size="24" class="spin" />
          <span>地图加载中...</span>
        </div>
        <!-- 加载失败状态 -->
        <div v-else-if="mapError" class="map-error">
          <AlertCircle :size="28" />
          <strong>地图加载失败</strong>
          <p>{{ mapError }}</p>
          <button class="op-btn" @click="bootstrapMap">
            <RefreshCw :size="14" />重试
          </button>
        </div>
        <!-- 空状态 -->
        <div v-else-if="stats.total === 0" class="map-empty">
          <Map :size="32" />
          <strong>暂无监测数据</strong>
          <p>当前区域暂无灾害点、设备或预警信息</p>
        </div>
        <div ref="mapContainer" class="ol-map" :class="{ hidden: loading || mapError || stats.total === 0 }"></div>

        <div class="ai-banner" v-if="aiMonitorCount > 0">
          <Cpu :size="14" />
          AI 实时监测：<strong>{{ aiMonitorCount }}</strong> 个疑似灾害
          <small>· 由 AiHazardMonitorScheduler 每 90s 巡查</small>
        </div>
        <!-- AI 监测数据为空时的提示 -->
        <div v-else class="ai-banner ai-banner-empty">
          <Cpu :size="14" />
          <span>AI 实时监测：暂无疑似灾害</span>
          <small>· 由 AiHazardMonitorScheduler 每 90s 巡查</small>
        </div>
      </div>

      <!-- 设备轨迹回放视图（替换地图视图） -->
      <div class="track-stage" v-else>
        <div class="track-toolbar">
          <label class="track-label">
            设备
            <select v-model="trackDeviceId">
              <option :value="null" disabled>选择设备</option>
              <option v-for="d in trackDevices" :key="d.id" :value="d.id">{{ d.name }} ({{ d.state ?? '未知' }})</option>
            </select>
          </label>
          <label class="track-label">
            开始时间
            <input type="datetime-local" v-model="trackFrom" />
          </label>
          <label class="track-label">
            结束时间
            <input type="datetime-local" v-model="trackTo" />
          </label>
          <label class="track-label">
            速度
            <select v-model="trackSpeed">
              <option value="1">1x</option>
              <option value="2">2x</option>
              <option value="5">5x</option>
              <option value="10">10x</option>
            </select>
          </label>
          <button class="op-btn" @click="toggleTrackPlay" :disabled="trackPoints.length < 2">
            <span v-if="trackPlaying">暂停</span>
            <span v-else>播放</span>
          </button>
          <button class="op-btn" @click="exportTrackGeoJSON" :disabled="!trackPoints.length">
            导出轨迹 GeoJSON
          </button>
        </div>

        <div class="track-map-wrap">
          <!-- 加载中状态 -->
          <div v-if="trackLoading" class="track-map-status track-loading">
            <Loader2 :size="24" class="spin" />
            <span>加载轨迹数据...</span>
          </div>
          <!-- 错误/空状态 -->
          <div v-else-if="trackError || trackPoints.length === 0" class="track-map-status track-empty">
            <Map :size="28" />
            <strong>{{ trackError ? '加载失败' : '暂无轨迹' }}</strong>
            <p>{{ trackError || '请选择设备与时间范围后重新加载' }}</p>
            <button v-if="trackError" class="op-btn" @click="loadTrackData">
              <RefreshCw :size="14" />重试
            </button>
          </div>
          <div ref="trackMapContainer" class="track-ol-map" :class="{ hidden: trackLoading || trackError || trackPoints.length === 0 }"></div>

          <!-- 轨迹进度时间轴 -->
          <div v-if="trackPoints.length > 1" class="track-timeline">
            <div class="track-timeline-labels">
              <span>{{ trackFrom ? new Date(trackFrom).toLocaleString('zh-CN', { hour12: false }) : '--' }}</span>
              <span class="track-progress-text">当前: {{ Math.round(trackProgress / 100 * (trackPoints.length - 1)) + 1 }} / {{ trackPoints.length }}</span>
              <span>{{ trackTo ? new Date(trackTo).toLocaleString('zh-CN', { hour12: false }) : '--' }}</span>
            </div>
            <input
              type="range"
              min="0"
              max="100"
              :value="trackProgress"
              @input="onTrackScrub"
              class="track-scrubber"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 时间轴（仅地图视图时显示） -->
    <footer class="timeline-bar" v-if="!trackTabActive">
      <div class="timeline-head">
        <Clock :size="14" />
        <span class="timeline-title">时间轴：最近 24h</span>
        <button class="ghost-btn" @click="togglePlay">
          <span v-if="isPlaying">⏸ 暂停</span>
          <span v-else>▶ 播放</span>
        </button>
      </div>
      <input
        type="range"
        min="0"
        :max="timeline.length - 1"
        v-model.number="timelineIndex"
        class="timeline-slider"
      />
      <div class="timeline-labels">
        <span v-for="(f, i) in timeline" :key="i" :class="{ active: i === timelineIndex }">{{ f.label }}</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.real-time-map-shell {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 60px);
  background: var(--c-surface);
  border-radius: var(--r-lg);
  overflow: hidden;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px 12px;
  border-bottom: 1px solid var(--c-brand-soft);
  gap: 16px;
  flex-wrap: wrap;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 20px;
}
.page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--c-text);
}
.inline-tabs {
  display: flex;
  gap: 4px;
  padding: 4px;
  background: var(--c-bg);
  border-radius: var(--r-md);
}
.inline-tab {
  padding: 6px 16px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-brand-deep);
  font-size: 13px;
  cursor: pointer;
  transition: background .15s, color .15s;
}
.inline-tab.active {
  background: var(--c-brand);
  color: var(--c-on-brand);
}
.page-header .actions { display: flex; gap: 10px; align-items: center; }

.map-style-switch { display: flex; gap: 6px; background: var(--c-bg); padding: 4px; border-radius: var(--r-md); }
.map-style-switch button { display: flex; gap: 4px; align-items: center; padding: 5px 10px; border: 0; background: transparent; border-radius: var(--r-sm); color: var(--c-brand-deep); font-size: 12px; cursor: pointer; }
.map-style-switch button.active { background: var(--c-surface); color: var(--c-text); box-shadow: 0 1px 4px rgba(0,0,0,.06); }

.op-btn { display: flex; gap: 6px; align-items: center; padding: 7px 12px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-sm); color: var(--c-text); font-size: 12px; cursor: pointer; }
.op-btn:hover { background: var(--c-bg); }
.op-btn:disabled { opacity: .5; cursor: not-allowed; }

.map-layout { display: flex; flex: 1; min-height: 0; position: relative; }
.layer-panel { width: 240px; padding: 16px; border-right: 1px solid var(--c-brand-soft); overflow-y: auto; }
.layer-header { display: flex; gap: 6px; align-items: center; color: var(--c-text); font-weight: 600; font-size: 13px; }
.layer-header.sub { margin-top: 16px; padding-top: 12px; border-top: 1px dashed var(--c-separator); }
.layer-header .icon-btn { margin-left: auto; padding: 2px; border: 0; background: transparent; cursor: pointer; color: var(--c-text-2); }
.layer-panel ul { list-style: none; margin: 12px 0 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.layer-panel li label { display: flex; gap: 8px; align-items: center; padding: 7px 10px; border-radius: var(--r-sm); cursor: pointer; font-size: 12px; color: var(--c-text); }
.layer-panel li label:hover { background: var(--c-bg); }
.layer-panel li.disabled label { opacity: .5; }
.layer-panel li .dot { width: 10px; height: 10px; border-radius: 50%; flex: 0 0 auto; }
.layer-panel li .lbl { flex: 1; }
.layer-panel li .count { color: var(--c-text-2); font-size: 11px; }

.tool-row { display: flex; gap: 6px; margin-top: 8px; }
.tool-row .op-btn { flex: 1; justify-content: center; }

.legend-list { list-style: none; margin: 8px 0 0; padding: 0; display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: var(--c-text); }
.legend-list .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 8px; vertical-align: middle; }

.layer-toggle { position: absolute; left: 8px; top: 8px; z-index: 5; padding: 8px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-sm); cursor: pointer; }

.map-stage { flex: 1; position: relative; }
.ol-map { position: absolute; inset: 0; }
.ol-map.hidden { display: none; }
.map-loading {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  justify-content: center;
  background: var(--c-surface);
  color: var(--c-brand-deep);
  z-index: 4;
}
.map-loading span { font-size: 13px; }
.map-error {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  justify-content: center;
  background: var(--c-surface);
  color: var(--c-danger-strong);
  z-index: 4;
}
.map-error strong { font-size: 15px; }
.map-error p { color: var(--c-danger-strong); font-size: 12px; margin: 0; }
/* 图例圆点：颜色与 JS 图层定义（hazard/device/warning/aiMonitor）对应，
   属「有意区分」的图层标识色，故跟随同一套语义令牌而非合并成单一色。 */
.dot--hazard { background: var(--c-danger-strong); }
.dot--warning { background: var(--c-warning); }
.dot--brand { background: var(--c-brand); }
.dot--ai { background: var(--c-warning-ink); }
.map-error .op-btn { margin-top: 8px; }
.map-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  justify-content: center;
  background: var(--c-surface);
  color: var(--c-text-2);
  z-index: 4;
}
.map-empty strong { font-size: 15px; color: var(--c-ink-1); }
.map-empty p { font-size: 12px; margin: 0; }
.map-loading .spin,
.map-error .spin { animation: spin 1s linear infinite; }

.ai-banner { position: absolute; top: 12px; right: 12px; z-index: 3; display: flex; gap: 8px; align-items: center; padding: 8px 14px; border-radius: var(--r-pill); background: var(--c-surface); color: var(--c-text); font-size: 12px; box-shadow: 0 4px 12px rgba(0,0,0,.06); }
.ai-banner small { color: var(--c-text-2); }
.ai-banner-empty {
  background: var(--glass-regular);
  color: var(--c-text-2);
}

.timeline-bar {
  padding: 10px 24px 14px;
  border-top: 1px solid var(--c-brand-soft);
  background: var(--c-surface-2);
}
.timeline-head {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: var(--c-text);
  margin-bottom: 6px;
}
.timeline-title {
  flex-shrink: 0;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.timeline-head .ghost-btn {
  margin-left: auto;
  padding: 4px 10px;
  border: 1px solid var(--c-separator);
  background: var(--c-surface);
  border-radius: var(--r-sm);
  cursor: pointer;
  font-size: 11px;
}
.timeline-slider {
  width: 100%;
}
.timeline-labels {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: var(--c-text-2);
  margin-top: 2px;
  /* 限制最多显示 9 个标签，每 3 小时一个 */
  max-width: 400px;
  margin-left: auto;
  margin-right: 0;
}
.timeline-labels span {
  flex: 1;
  text-align: center;
  min-width: 32px;
}
.timeline-labels span:nth-child(odd):not(:first-child):not(:last-child) {
  /* 每 3 小时显示一个主要标签 */
  display: none;
}
.timeline-labels span:first-child,
.timeline-labels span:nth-child(7),
.timeline-labels span:nth-child(13),
.timeline-labels span:nth-child(19),
.timeline-labels span:last-child {
  display: block;
}
.timeline-labels .active {
  color: var(--c-brand);
  font-weight: 600;
}

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

/* 设备轨迹回放视图 */
.track-stage {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.track-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--c-brand-soft);
  background: var(--c-surface-2);
  flex-wrap: wrap;
}
.track-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--c-text);
}
.track-label select,
.track-label input {
  padding: 5px 8px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  font-size: 12px;
  color: var(--c-text);
}
.track-map-wrap {
  flex: 1;
  position: relative;
  min-height: 0;
}
.track-ol-map {
  position: absolute;
  inset: 0;
}
.track-ol-map.hidden { display: none; }
.track-map-status {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  justify-content: center;
  z-index: 3;
}
.track-loading {
  background: var(--c-surface);
  color: var(--c-brand-deep);
}
.track-loading span { font-size: 13px; }
.track-empty {
  background: var(--c-surface);
  color: var(--c-text-2);
}
.track-empty strong { font-size: 14px; color: var(--c-ink-1); }
.track-empty p { font-size: 12px; margin: 0; }
.track-empty .op-btn { margin-top: 8px; }
.track-timeline {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 10px 16px;
  background: var(--c-surface-3);
  border-top: 1px solid var(--c-brand-soft);
}
.track-scrubber {
  width: 100%;
}
.track-timeline-labels {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  color: var(--c-text-2);
  margin-bottom: 8px;
}
.track-progress-text {
  color: var(--c-brand);
  font-weight: 600;
}
</style>
