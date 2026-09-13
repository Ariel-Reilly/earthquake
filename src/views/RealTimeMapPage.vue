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
  Sparkles,
  Sun,
  Target,
  X,
} from "lucide-vue-next"
import { createOpenLayersController } from "../services/mapService"
import { dashboardApi } from "../services/dashboardApi"
import { onRealtimeUpdate, startRealtimeSync, stopRealtimeSync } from "../services/realtimeService"

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
  try {
    mapController = await createOpenLayersController(mapContainer.value, {
      baseStyle: mapStyle.value,
      showWeather: layerState.weather,
      showSentinel: mapStyle.value === "satellite",
    })
    mapReady.value = true
    await loadAllOverlays()
    buildTimeline()
  } catch (e) {
    emit("notify", e?.message ?? "地图初始化失败")
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

    mapController.renderHazards?.(hz, { visible: layerState.hazard })
    mapController.renderDevices?.(dv, { visible: layerState.device })
    mapController.renderWarnings?.(wn, { visible: layerState.warning })
    mapController.renderAiMonitorHazards?.(am, { visible: layerState.aiMonitor })
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
  mapController?.setBaseStyle?.(style)
  emit("notify", `已切换到${style === "satellite" ? "卫星" : style === "terrain" ? "地形" : "矢量"}底图`)
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
function fitView() { mapController?.fitAllHazards?.() }

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
  if (mapController?.destroy) mapController.destroy()
})

const mapStyleOptions = [
  { id: "vector", label: "矢量", icon: MapPin },
  { id: "satellite", label: "卫星", icon: Satellite },
  { id: "terrain", label: "地形", icon: Sun },
]

const layerMeta = [
  { key: "hazard", label: "灾害点", color: "#c7553b" },
  { key: "device", label: "监测设备", color: "#2b6cb0" },
  { key: "warning", label: "预警事件", color: "#d7a546" },
  { key: "aiMonitor", label: "AI 实时监测", color: "#9b761e" },
  { key: "weather", label: "气象叠加", color: "#5d95c1" },
  { key: "personnel", label: "值守人员", color: "#2a8154" },
]

// ===================== 设备轨迹回放 Tab 状态 =====================
const trackTabActive = ref(false)
const trackDevices = ref([])
const trackDeviceId = ref(null)
const trackFrom = ref("")
const trackTo = ref("")
const trackSpeed = ref("1")
const trackPlaying = ref(false)
const trackProgress = ref(0) // 0-100
const trackLoading = ref(false)
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
  try {
    const data = await dashboardApi.getDeviceTrack(trackDeviceId.value, {
      from: trackFrom.value,
      to: trackTo.value,
    })
    trackPoints.value = Array.isArray(data) ? data : []
  } catch {
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
      <div>
        <h2><MapPin :size="18" />实时监测地图</h2>
        <p>多底图、多图层、时间轴回放；AI 实时监测灾害每 90 秒自动落点。</p>
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
            <li><span class="dot" style="background:#c7553b"></span>高风险灾害</li>
            <li><span class="dot" style="background:#d7a546"></span>关注灾害</li>
            <li><span class="dot" style="background:#5db486"></span>正常状态</li>
            <li><span class="dot" style="background:#2b6cb0"></span>监测设备</li>
            <li><span class="dot" style="background:#9b761e"></span>AI 实时监测</li>
          </ul>
        </div>
      </aside>
      <button v-else class="layer-toggle" @click="showLayers = true"><Layers :size="14" /></button>

      <div class="map-stage">
        <div v-if="loading" class="map-loading">
          <Loader2 :size="20" class="spin" />
          地图加载中…
        </div>
        <div ref="mapContainer" class="ol-map"></div>

        <div class="ai-banner" v-if="aiMonitorCount > 0">
          <Sparkles :size="14" />
          AI 实时监测：<strong>{{ aiMonitorCount }}</strong> 个疑似灾害
          <small>· 由 AiHazardMonitorScheduler 每 90s 巡查</small>
        </div>

        <!-- 内嵌 Tab 切换：地图 / 设备轨迹回放 -->
        <div class="map-inline-tabs">
          <button
            class="map-inline-tab"
            :class="{ active: !trackTabActive }"
            @click="trackTabActive = false"
          >地图视图</button>
          <button
            class="map-inline-tab"
            :class="{ active: trackTabActive }"
            @click="trackTabActive = true; loadTrackDevices(); initDefaultTrackRange()"
          >设备轨迹回放</button>
        </div>
      </div>
    </div>

    <footer class="timeline-bar">
      <div class="timeline-head">
        <Clock :size="14" />
        <span>时间轴：最近 24h</span>
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

    <!-- ========== 设备轨迹回放 Tab ========== -->
    <section v-if="trackTabActive" class="track-panel">
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
        <span class="track-hint">
          <template v-if="trackLoading">加载轨迹数据...</template>
          <template v-else-if="trackPoints.length > 0">{{ trackPoints.length }} 个轨迹点</template>
          <template v-else>无轨迹数据，请选择设备与时间范围</template>
        </span>
      </div>

      <div class="track-map-wrap">
        <div ref="trackMapContainer" class="track-ol-map"></div>
        <!-- polyline preview via CSS fallback when map not ready -->
        <div v-if="trackPoints.length > 1" class="track-timeline">
          <input
            type="range"
            min="0"
            max="100"
            :value="trackProgress"
            @input="onTrackScrub"
            class="track-scrubber"
          />
          <div class="track-timeline-labels">
            <span>{{ trackFrom ? new Date(trackFrom).toLocaleString('zh-CN', { hour12: false }) : '--' }}</span>
            <span>当前: {{ Math.round(trackProgress / 100 * (trackPoints.length - 1)) + 1 }} / {{ trackPoints.length }}</span>
            <span>{{ trackTo ? new Date(trackTo).toLocaleString('zh-CN', { hour12: false }) : '--' }}</span>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.real-time-map-shell {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 60px);
  background: #fff;
  border-radius: 16px;
  overflow: hidden;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 20px 24px 12px;
  border-bottom: 1px solid #e9eee9;
}
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: #1f3e44; }
.page-header p { margin: 4px 0 0; color: #7a8d89; font-size: 12px; }
.page-header .actions { display: flex; gap: 10px; align-items: center; }

.map-style-switch { display: flex; gap: 6px; background: #f4f7f5; padding: 4px; border-radius: 10px; }
.map-style-switch button { display: flex; gap: 4px; align-items: center; padding: 5px 10px; border: 0; background: transparent; border-radius: 7px; color: #56716e; font-size: 12px; cursor: pointer; }
.map-style-switch button.active { background: #fff; color: #1f3e44; box-shadow: 0 1px 4px rgba(0,0,0,.06); }

.op-btn { display: flex; gap: 6px; align-items: center; padding: 7px 12px; border: 1px solid #d9e6e1; background: #fff; border-radius: 8px; color: #37555a; font-size: 12px; cursor: pointer; }
.op-btn:hover { background: #f4f7f5; }
.op-btn:disabled { opacity: .5; cursor: not-allowed; }

.map-layout { display: flex; flex: 1; min-height: 0; position: relative; }
.layer-panel { width: 240px; padding: 16px; border-right: 1px solid #e9eee9; overflow-y: auto; }
.layer-header { display: flex; gap: 6px; align-items: center; color: #1f3e44; font-weight: 600; font-size: 13px; }
.layer-header.sub { margin-top: 16px; padding-top: 12px; border-top: 1px dashed #d9e6e1; }
.layer-header .icon-btn { margin-left: auto; padding: 2px; border: 0; background: transparent; cursor: pointer; color: #7a8d89; }
.layer-panel ul { list-style: none; margin: 12px 0 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.layer-panel li label { display: flex; gap: 8px; align-items: center; padding: 7px 10px; border-radius: 8px; cursor: pointer; font-size: 12px; color: #37555a; }
.layer-panel li label:hover { background: #f4f7f5; }
.layer-panel li.disabled label { opacity: .5; }
.layer-panel li .dot { width: 10px; height: 10px; border-radius: 50%; flex: 0 0 auto; }
.layer-panel li .lbl { flex: 1; }
.layer-panel li .count { color: #7a8d89; font-size: 11px; }

.tool-row { display: flex; gap: 6px; margin-top: 8px; }
.tool-row .op-btn { flex: 1; justify-content: center; }

.legend-list { list-style: none; margin: 8px 0 0; padding: 0; display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: #37555a; }
.legend-list .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 8px; vertical-align: middle; }

.layer-toggle { position: absolute; left: 8px; top: 8px; z-index: 5; padding: 8px; border: 1px solid #d9e6e1; background: #fff; border-radius: 8px; cursor: pointer; }

.map-stage { flex: 1; position: relative; }
.ol-map { position: absolute; inset: 0; }
.map-loading { position: absolute; inset: 0; display: flex; gap: 8px; align-items: center; justify-content: center; background: rgba(244,247,245,.85); color: #56716e; z-index: 4; }

.ai-banner { position: absolute; top: 12px; right: 12px; z-index: 3; display: flex; gap: 8px; align-items: center; padding: 8px 14px; border-radius: 999px; background: rgba(255,255,255,.92); color: #1f3e44; font-size: 12px; box-shadow: 0 4px 12px rgba(0,0,0,.06); }
.ai-banner small { color: #7a8d89; }

.timeline-bar { padding: 10px 24px 14px; border-top: 1px solid #e9eee9; background: #fafdfc; }
.timeline-head { display: flex; gap: 8px; align-items: center; font-size: 12px; color: #1f3e44; margin-bottom: 6px; }
.timeline-head .ghost-btn { margin-left: auto; padding: 4px 10px; border: 1px solid #d9e6e1; background: #fff; border-radius: 6px; cursor: pointer; font-size: 11px; }
.timeline-slider { width: 100%; }
.timeline-labels { display: flex; justify-content: space-between; font-size: 10px; color: #7a8d89; margin-top: 2px; }
.timeline-labels .active { color: #2f7f80; font-weight: 600; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

/* 设备轨迹回放 */
.track-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.track-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid #e9eee9;
  background: #fafdfc;
  flex-wrap: wrap;
}
.track-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #37555a;
}
.track-label select,
.track-label input {
  padding: 5px 8px;
  border: 1px solid #d9e6e1;
  border-radius: 6px;
  background: #fff;
  font-size: 12px;
  color: #24494d;
}
.track-hint {
  font-size: 11px;
  color: #7a8d89;
  margin-left: auto;
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
.track-timeline {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 10px 16px;
  background: rgba(250, 253, 252, 0.92);
  border-top: 1px solid #e9eee9;
}
.track-scrubber {
  width: 100%;
}
.track-timeline-labels {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: #7a8d89;
  margin-top: 4px;
}
.map-inline-tabs {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 4;
  display: flex;
  gap: 2px;
  padding: 3px;
  background: rgba(255, 255, 255, 0.92);
  border-radius: 999px;
  box-shadow: 0 2px 8px rgba(0,0,0,.08);
}
.map-inline-tab {
  padding: 5px 14px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #56716e;
  font-size: 12px;
  cursor: pointer;
  transition: background .15s, color .15s;
}
.map-inline-tab.active {
  background: #2f7f80;
  color: #fff;
}
</style>
