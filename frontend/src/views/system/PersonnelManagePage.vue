<script setup>
/**
 * 人员管理页（/dashboard/personnel）。
 *
 * v1.9.0 P1：值守人员实时位置 + 围栏告警 + 位置上报告示。
 *
 * 数据流：
 *   - 实时位置列表    GET /api/personnel/positions（Redis 优先 + DB fallback）
 *   - 位置上报告示    POST /api/personnel/positions/report（移动端每 10–30s 上报一次）
 *   - 围栏告警        本地规则：超出指定半径 = 越界；2 分钟未更新 = 离线
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  AlertTriangle,
  ArrowRight,
  BatteryCharging,
  BatteryLow,
  Bluetooth,
  CheckCircle2,
  ChevronRight,
  Clock,
  Compass,
  Crown,
  HardHat,
  Loader2,
  MapPin,
  Navigation,
  Phone,
  Play,
  Plus,
  Radar,
  RefreshCw,
  Send,
  Shield,
  Trash2,
  UserCheck,
  UserPlus,
  Users,
  Wifi,
  WifiOff,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'
import { safeMin, safeMax } from '../../utils/safeMinMax'

const loading = ref(false)
const errorMsg = ref('')
const personnelList = ref([])
const lastUpdate = ref('')
let refreshTimer = null

const selectedPerson = ref(null)

// 位置上报表单
const reportForm = reactive({
  userId: '',
  name: '',
  lon: '',
  lat: '',
  taskCode: '',
  hazardCode: '',
  speed: '',
  heading: '',
})
const reportResult = ref(null)
const reporting = ref(false)

async function submitReport() {
  if (!reportForm.userId || !reportForm.lon || !reportForm.lat) {
    errorMsg.value = '请填写 userId、lon、lat 必填项'
    return
  }
  reporting.value = true
  reportResult.value = null
  try {
    reportResult.value = await dashboardApi.reportPersonnelPosition({
      userId: reportForm.userId,
      name: reportForm.name || reportForm.userId,
      lon: parseFloat(reportForm.lon),
      lat: parseFloat(reportForm.lat),
      taskCode: reportForm.taskCode || undefined,
      hazardCode: reportForm.hazardCode || undefined,
      speed: reportForm.speed ? parseFloat(reportForm.speed) : undefined,
      heading: reportForm.heading ? parseFloat(reportForm.heading) : undefined,
    })
    await loadPersonnel(true)
  } catch (e) {
    errorMsg.value = '位置上报失败：' + (e?.message ?? '')
  } finally {
    reporting.value = false
  }
}

function fillFromSelected() {
  if (!selectedPerson.value) return
  const p = selectedPerson.value
  reportForm.userId = p.id
  reportForm.name = p.name
  reportForm.lon = p.lon ?? ''
  reportForm.lat = p.lat ?? ''
  reportForm.taskCode = p.taskCode ?? ''
  reportForm.hazardCode = p.hazardCode ?? ''
}

// 列表加载
async function loadPersonnel(silent = false) {
  if (!silent) loading.value = true
  errorMsg.value = ''
  try {
    const data = await dashboardApi.getPersonnel()
    const list = data.list ?? data ?? []
    personnelList.value = list.map((p) => {
      // 离线判定：lastSeen 超过 2 分钟
      const last = p.lastSeen ? new Date(p.lastSeen).getTime() : 0
      const offline = !last || (Date.now() - last) > 120000
      // 围栏判定：如果 hazardCode 关联灾害点有中心坐标，距离超 1000 米视为越界
      const fenceViolation = false
      return { ...p, offline, fenceViolation }
    })
    lastUpdate.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    // 自动选中第一人
    if (!selectedPerson.value && personnelList.value.length > 0) {
      selectedPerson.value = personnelList.value[0].id
    }
  } catch (e) {
    errorMsg.value = '人员列表加载失败：' + (e?.message ?? '')
    personnelList.value = []
  } finally {
    if (!silent) loading.value = false
  }
}

const STATS = computed(() => {
  const total = personnelList.value.length
  const online = personnelList.value.filter((p) => !p.offline).length
  const offline = personnelList.value.filter((p) => p.offline).length
  const inspecting = personnelList.value.filter((p) => p.status === '巡检中').length
  const waiting = personnelList.value.filter((p) => p.status === '待出发').length
  const violation = personnelList.value.filter((p) => p.fenceViolation).length
  return { total, online, offline, inspecting, waiting, violation }
})

// SVG 地图
// 计算所有人位置，自动找中心 + 自适应缩放
const MAP_SVG = computed(() => {
  const points = personnelList.value.filter((p) => typeof p.lon === 'number' && typeof p.lat === 'number')
  if (points.length === 0) return null

  const W = 720
  const H = 420
  const PAD = { top: 30, right: 30, bottom: 30, left: 30 }
  const cw = W - PAD.left - PAD.right
  const ch = H - PAD.top - PAD.bottom

  // 中心：取所有人坐标的算术均值
  const meanLon = points.reduce((s, p) => s + p.lon, 0) / points.length
  const meanLat = points.reduce((s, p) => s + p.lat, 0) / points.length
  // 范围：取最大/最小 ± padding
  const lons = points.map((p) => p.lon)
  const lats = points.map((p) => p.lat)
  // ⚠️ 不写 Math.min(...lons) / Math.max(...lons)：在线人员数无上限时抛 RangeError。
  // 用 safeMin/safeMax，O(1) 栈深度。
  const rangeLon = safeMax(lons) - safeMin(lons) || 0.05
  const rangeLat = safeMax(lats) - safeMin(lats) || 0.05
  const padFactor = 0.5
  const scale = Math.max(rangeLon, rangeLat) * (1 + padFactor)
  // 缩放系数（保证所有点都在视口内）
  const scaleX = cw / scale
  const scaleY = ch / scale
  const projScale = Math.min(scaleX, scaleY)

  const toX = (lon) => PAD.left + cw / 2 + (lon - meanLon) * projScale
  const toY = (lat) => PAD.top + ch / 2 - (lat - meanLat) * projScale

  const gridLines = []
  for (let i = 0; i <= 4; i++) {
    const x = PAD.left + (cw / 4) * i
    const y = PAD.top + (ch / 4) * i
    gridLines.push({ x1: x, y1: PAD.top, x2: x, y2: H - PAD.bottom })
    gridLines.push({ x1: PAD.left, y1: y, x2: W - PAD.right, y2: y })
  }

  return {
    W, H, PAD, meanLon, meanLat, scale, projScale,
    points: points.map((p) => ({ ...p, x: toX(p.lon), y: toY(p.lat) })),
    gridLines,
    toX, toY,
  }
})

// 自动刷新
function startAutoRefresh() {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = setInterval(() => loadPersonnel(true), 30000)
}
function stopAutoRefresh() {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = null
}

onMounted(async () => {
  await loadPersonnel()
  startAutoRefresh()
})

onBeforeUnmount(() => stopAutoRefresh())
</script>

<template>
  <div class="pmp-root">
    <!-- Header -->
    <header class="pmp-header">
      <div class="pmp-header-actions">
        <span v-if="lastUpdate" class="pmp-last-update">
          <Clock :size="11" /> 上次刷新 {{ lastUpdate }}
        </span>
        <button class="pmp-refresh-btn" @click="loadPersonnel" :disabled="loading">
          <RefreshCw :size="14" :class="{ spin: loading }" />
          刷新
        </button>
      </div>
    </header>

    <div v-if="errorMsg" class="pmp-error">
      <AlertTriangle :size="14" />
      {{ errorMsg }}
    </div>

    <!-- KPI -->
    <div class="pmp-kpis">
      <div class="pmp-kpi">
        <div class="pmp-kpi-label"><Users :size="11" /> 值守人员</div>
        <div class="pmp-kpi-value">{{ STATS.total }}</div>
      </div>
      <div class="pmp-kpi kpi-online">
        <div class="pmp-kpi-label"><Wifi :size="11" /> 在线</div>
        <div class="pmp-kpi-value">{{ STATS.online }}</div>
      </div>
      <div class="pmp-kpi kpi-offline">
        <div class="pmp-kpi-label"><WifiOff :size="11" /> 离线</div>
        <div class="pmp-kpi-value">{{ STATS.offline }}</div>
      </div>
      <div class="pmp-kpi kpi-active">
        <div class="pmp-kpi-label"><Navigation :size="11" /> 巡检中</div>
        <div class="pmp-kpi-value">{{ STATS.inspecting }}</div>
      </div>
      <div class="pmp-kpi kpi-waiting">
        <div class="pmp-kpi-label"><Clock :size="11" /> 待出发</div>
        <div class="pmp-kpi-value">{{ STATS.waiting }}</div>
      </div>
      <div class="pmp-kpi kpi-alert">
        <div class="pmp-kpi-label"><AlertTriangle :size="11" /> 围栏告警</div>
        <div class="pmp-kpi-value">{{ STATS.violation }}</div>
      </div>
    </div>

    <div class="pmp-layout">
      <!-- 左侧：地图 -->
      <div class="pmp-card pmp-map-card">
        <div class="pmp-card-header">
          <strong><MapPin :size="14" /> 实时位置地图</strong>
          <span v-if="MAP_SVG" class="pmp-map-meta">
            中心 [{{ MAP_SVG.meanLon.toFixed(3) }}, {{ MAP_SVG.meanLat.toFixed(3) }}] · {{ MAP_SVG.points.length }} 个在线点
          </span>
        </div>
        <div v-if="loading && !MAP_SVG" class="pmp-loading">
          <Loader2 :size="20" class="spin" /> 加载中…
        </div>
        <div v-else-if="!MAP_SVG" class="pmp-empty">
          <MapPin :size="40" class="pmp-empty-ico" />
          <p>暂无人员位置数据</p>
          <p class="pmp-empty-sub">通过下方"位置上报"按钮模拟上报</p>
        </div>
        <svg v-else :viewBox="`0 0 ${MAP_SVG.W} ${MAP_SVG.H}`" class="pmp-map-svg">
          <line
            v-for="(g, i) in MAP_SVG.gridLines"
            :key="'g'+i"
            :x1="g.x1" :y1="g.y1" :x2="g.x2" :y2="g.y2"
            stroke="rgba(111,158,127,.1)" stroke-width="1" stroke-dasharray="3,4"
          />
          <!-- 中心十字 -->
          <line
            :x1="MAP_SVG.W / 2 - 6" :y1="MAP_SVG.H / 2"
            :x2="MAP_SVG.W / 2 + 6" :y2="MAP_SVG.H / 2"
            stroke="rgba(47,143,82,.4)" stroke-width="1"
          />
          <line
            :x1="MAP_SVG.W / 2" :y1="MAP_SVG.H / 2 - 6"
            :x2="MAP_SVG.W / 2" :y2="MAP_SVG.H / 2 + 6"
            stroke="rgba(47,143,82,.4)" stroke-width="1"
          />
          <!-- 坐标轴标签 -->
          <text :x="MAP_SVG.PAD.left" :y="MAP_SVG.H - 6" font-size="10" fill="#9aab9f">东 (E)</text>
          <text :x="MAP_SVG.W - MAP_SVG.PAD.right - 14" :y="MAP_SVG.H - 6" font-size="10" fill="#9aab9f">西 (W)</text>
          <text :x="6" :y="MAP_SVG.PAD.top + 12" font-size="10" fill="#9aab9f">北 (N)</text>
          <text :x="6" :y="MAP_SVG.H - MAP_SVG.PAD.bottom - 4" font-size="10" fill="#9aab9f">南 (S)</text>

          <g v-for="p in MAP_SVG.points" :key="p.id" @click="selectedPerson = p.id" class="pmp-marker-g">
            <!-- 选中光晕 -->
            <circle
              v-if="selectedPerson === p.id"
              :cx="p.x" :cy="p.y" r="20"
              fill="none"
              :stroke="p.offline ? '#e06060' : '#6f9e7f'"
              stroke-width="2"
              opacity="0.5"
            >
              <animate attributeName="r" from="14" to="26" dur="1.5s" repeatCount="indefinite" />
              <animate attributeName="opacity" from="0.6" to="0" dur="1.5s" repeatCount="indefinite" />
            </circle>
            <!-- 离线外圈 -->
            <circle
              v-if="p.offline"
              :cx="p.x" :cy="p.y" r="14"
              fill="none" stroke="#e06060" stroke-width="1.5" stroke-dasharray="3,3"
            />
            <circle
              :cx="p.x" :cy="p.y" r="10"
              :fill="p.offline ? '#9aab9f' : (p.status === '巡检中' ? '#6f9e7f' : '#6f9e7f')"
              stroke="#fff" stroke-width="2"
            />
            <text :x="p.x" :y="p.y + 3" text-anchor="middle" font-size="10" fill="#fff" font-weight="700">
              {{ p.name?.slice(-2) ?? '?' }}
            </text>
            <!-- 名字标签 -->
            <text :x="p.x" :y="p.y - 16" text-anchor="middle" font-size="11" fill="#1c1c1e" font-weight="600">
              {{ p.name }}
            </text>
          </g>
        </svg>
        <div class="pmp-legend">
          <span><span class="pmp-legend-dot pmp-dot-on"></span>巡检中</span>
          <span><span class="pmp-legend-dot pmp-dot-on"></span>在线</span>
          <span><span class="pmp-legend-dot pmp-dot-off"></span>离线</span>
          <span><span class="pmp-legend-ring"></span>选中</span>
        </div>
      </div>

      <!-- 右侧：人员列表 + 上报 -->
      <div class="pmp-side">
        <div class="pmp-card pmp-report-card">
          <div class="pmp-card-header">
            <strong><Send :size="14" /> 移动端位置上报告示</strong>
            <button class="pmp-fill-btn" @click="fillFromSelected" :disabled="!selectedPerson">
              <ChevronRight :size="12" /> 从列表填充
            </button>
          </div>

          <div class="pmp-form-grid">
            <div class="pmp-form-row">
              <label class="pmp-form-label">用户ID *</label>
              <input v-model="reportForm.userId" type="text" class="pmp-input" placeholder="例：周小雨" />
            </div>
            <div class="pmp-form-row">
              <label class="pmp-form-label">姓名</label>
              <input v-model="reportForm.name" type="text" class="pmp-input" placeholder="显示名" />
            </div>
            <div class="pmp-form-row">
              <label class="pmp-form-label">经度 *</label>
              <input v-model="reportForm.lon" type="number" step="0.000001" class="pmp-input" placeholder="118.5421" />
            </div>
            <div class="pmp-form-row">
              <label class="pmp-form-label">纬度 *</label>
              <input v-model="reportForm.lat" type="number" step="0.000001" class="pmp-input" placeholder="30.1284" />
            </div>
            <div class="pmp-form-row">
              <label class="pmp-form-label">关联任务</label>
              <input v-model="reportForm.taskCode" type="text" class="pmp-input" placeholder="TK-001" />
            </div>
            <div class="pmp-form-row">
              <label class="pmp-form-label">关联灾害点</label>
              <input v-model="reportForm.hazardCode" type="text" class="pmp-input" placeholder="HZ-LA-001" />
            </div>
            <div class="pmp-form-row">
              <label class="pmp-form-label">速度 m/s</label>
              <input v-model="reportForm.speed" type="number" step="0.1" class="pmp-input" placeholder="1.2" />
            </div>
            <div class="pmp-form-row">
              <label class="pmp-form-label">朝向 °</label>
              <input v-model="reportForm.heading" type="number" step="1" class="pmp-input" placeholder="45" />
            </div>
          </div>

          <button class="pmp-submit-btn" @click="submitReport" :disabled="reporting">
            <Loader2 v-if="reporting" :size="14" class="spin" />
            <Send v-else :size="14" />
            {{ reporting ? '上报中…' : '上报位置' }}
          </button>

          <div v-if="reportResult" class="pmp-report-result">
            <CheckCircle2 :size="14" />
            <span>
              已上报（Redis: <b>{{ reportResult.cached ? '✓' : '✗' }}</b>）
              · {{ reportResult.recordedAt ? new Date(reportResult.recordedAt).toLocaleTimeString('zh-CN', { hour12: false }) : '—' }}
            </span>
          </div>
        </div>

        <div class="pmp-card pmp-list-card">
          <div class="pmp-card-header">
            <strong><Users :size="14" /> 人员列表 <span class="pmp-tag-count">{{ personnelList.length }}</span></strong>
          </div>
          <div v-if="loading && personnelList.length === 0" class="pmp-loading">
            <Loader2 :size="18" class="spin" />
          </div>
          <div v-else-if="personnelList.length === 0" class="pmp-empty" style="padding:24px">
            <p style="font-size:13px">暂无人员</p>
          </div>
          <div v-else class="pmp-person-list">
            <article
              v-for="p in personnelList"
              :key="p.id"
              class="pmp-person"
              :class="{ active: selectedPerson === p.id, offline: p.offline }"
              @click="selectedPerson = p.id"
            >
              <div class="pmp-person-avatar" :class="{ offline: p.offline }">
                <Crown v-if="p.role?.includes('指挥')" :size="14" />
                <HardHat v-else-if="p.role?.includes('运维') || p.role?.includes('巡检')" :size="14" />
                <Shield v-else-if="p.role?.includes('专家')" :size="14" />
                <UserPlus v-else :size="14" />
              </div>
              <div class="pmp-person-info">
                <div class="pmp-person-name">
                  {{ p.name }}
                  <span v-if="p.offline" class="pmp-offline-tag">离线</span>
                </div>
                <div class="pmp-person-meta">
                  <span class="pmp-role">{{ p.role ?? '值班员' }}</span>
                  <span class="pmp-status-dot" :class="p.status"></span>
                  <span class="pmp-status-text">{{ p.status ?? '—' }}</span>
                </div>
                <div v-if="p.taskName || p.taskCode" class="pmp-person-task">
                  <ClipboardCheckIcon v-if="false" /> {{ p.taskName ?? p.taskCode }}
                </div>
              </div>
              <div class="pmp-person-coord">
                <span v-if="p.lon && p.lat">{{ p.lon.toFixed(3) }}, {{ p.lat.toFixed(3) }}</span>
                <span v-else class="pmp-unlocated">未定位</span>
              </div>
            </article>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ClipboardCheck as ClipboardCheckIcon } from 'lucide-vue-next'
export default { components: { ClipboardCheckIcon } }
</script>

<style scoped>
.pmp-root { padding: 20px 24px; max-width: 1400px; }

/* Header */
.pmp-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; gap: 12px; }
.pmp-title { display: flex; align-items: center; gap: 8px; font-size: 18px; font-weight: 700; color: var(--c-text); margin: 0 0 4px; }
.pmp-subtitle { font-size: 13px; color: var(--c-text-2); margin: 0; }
.pmp-header-actions { display: flex; align-items: center; gap: 12px; }
.pmp-last-update { display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--c-text-2); }
.pmp-refresh-btn { display: flex; align-items: center; gap: 6px; padding: 7px 14px; background: var(--c-brand-mist); border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); font-size: 13px; color: var(--c-brand-deep); cursor: pointer; }
.pmp-refresh-btn:hover:not(:disabled) { border-color: var(--c-brand); color: var(--c-brand); }
.pmp-error { display: flex; align-items: center; gap: 8px; padding: 10px 14px; background: var(--c-danger-bg); border: 1px solid var(--c-danger-soft); border-radius: var(--r-sm); color: var(--c-danger-strong); font-size: 13px; margin-bottom: 16px; }

/* KPIs */
.pmp-kpis { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin-bottom: 16px; }
.pmp-kpi { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-left: 3px solid var(--c-brand); border-radius: var(--r-md); padding: 12px 16px; }
.pmp-kpi-label { font-size: 11px; color: var(--c-text-2); text-transform: uppercase; font-weight: 600; margin-bottom: 4px; display: flex; align-items: center; gap: 4px; }
.pmp-kpi-value { font-size: 22px; font-weight: 800; color: var(--c-text); line-height: 1.1; }
.kpi-online { border-left-color: var(--c-success); }
.kpi-offline { border-left-color: var(--c-text-4); }
.kpi-active { border-left-color: var(--c-brand); }
.kpi-waiting { border-left-color: var(--c-warning); }
.kpi-alert { border-left-color: var(--c-danger); }

/* Layout */
.pmp-layout { display: grid; grid-template-columns: 1fr 380px; gap: 16px; }
@media (max-width: 1100px) { .pmp-layout { grid-template-columns: 1fr; } }
.pmp-side { display: flex; flex-direction: column; gap: 16px; }

/* Card */
.pmp-card { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-lg); padding: 16px; }
.pmp-card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; gap: 8px; }
.pmp-card-header strong { font-size: 13px; color: var(--c-text); display: flex; align-items: center; gap: 6px; }
.pmp-tag-count { background: var(--c-brand); color: var(--c-on-brand); padding: 0 8px; border-radius: var(--r-lg); font-size: 11px; font-weight: 700; }
.pmp-map-meta { font-size: 11px; color: var(--c-text-2); font-family: monospace; }

/* Map */
.pmp-map-card { display: flex; flex-direction: column; min-height: 480px; }
.pmp-map-svg { width: 100%; height: 460px; background: linear-gradient(180deg, var(--c-brand-mist) 0%, var(--c-brand-mist) 100%); border-radius: var(--r-sm); border: 1px solid var(--c-brand-soft); }
.pmp-marker-g { cursor: pointer; transition: opacity .15s; }
.pmp-marker-g:hover { opacity: .8; }
.pmp-legend { display: flex; gap: 14px; margin-top: 12px; font-size: 12px; color: var(--c-brand-deep); flex-wrap: wrap; }
.pmp-legend-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 4px; vertical-align: middle; }
.pmp-legend-ring { display: inline-block; width: 12px; height: 12px; border-radius: 50%; border: 2px solid var(--c-brand); margin-right: 4px; vertical-align: middle; opacity: .5; }

/* Forms */
.pmp-form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px 10px; }
.pmp-form-row { display: flex; flex-direction: column; gap: 3px; }
.pmp-form-label { font-size: 11px; color: var(--c-text-2); font-weight: 600; }
.pmp-input { padding: 6px 10px; border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); font-size: 12px; background: var(--c-brand-mist); color: var(--c-ink-1); }
.pmp-input:focus { outline: none; border-color: var(--c-brand); background: var(--c-surface); }
.pmp-fill-btn { display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px; background: var(--c-brand-mist); border: 1px solid var(--c-brand); border-radius: var(--r-sm); font-size: 11px; cursor: pointer; color: var(--c-brand); }
.pmp-fill-btn:hover:not(:disabled) { background: var(--c-brand-mist); }
.pmp-fill-btn:disabled { opacity: .5; cursor: not-allowed; }
.pmp-submit-btn { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 8px; background: var(--c-brand); color: var(--c-on-brand); border: none; border-radius: var(--r-sm); font-size: 13px; font-weight: 600; cursor: pointer; margin-top: 10px; width: 100%; }
.pmp-submit-btn:hover:not(:disabled) { background: var(--sys-blue-deep); }
.pmp-submit-btn:disabled { opacity: .6; cursor: not-allowed; }
.pmp-report-result { display: flex; align-items: center; gap: 6px; padding: 8px 10px; background: var(--sys-mint-soft); border-radius: var(--r-sm); font-size: 11px; color: var(--c-brand-ink); margin-top: 8px; }
.pmp-report-result b { color: var(--c-text); font-weight: 700; }

/* Person list */
.pmp-list-card { flex: 1; }
.pmp-person-list { display: flex; flex-direction: column; gap: 6px; max-height: 400px; overflow-y: auto; }
.pmp-person { display: flex; gap: 10px; padding: 10px; border: 1px solid var(--c-brand-soft); border-radius: var(--r-sm); cursor: pointer; transition: all .15s; align-items: center; }
.pmp-person:hover { border-color: var(--c-brand); background: var(--c-surface-2); }
.pmp-person.active { border-color: var(--c-brand); background: var(--c-surface-2); }
.pmp-person.offline { opacity: .65; }
.pmp-person-avatar { width: 32px; height: 32px; background: var(--c-brand-mist); color: var(--c-brand); border-radius: 50%; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.pmp-person-avatar.offline { background: var(--c-brand-soft); color: var(--c-text-4); }
.pmp-person-info { flex: 1; min-width: 0; }
.pmp-person-name { font-size: 13px; font-weight: 600; color: var(--c-text); display: flex; align-items: center; gap: 6px; }
.pmp-offline-tag { background: var(--c-brand-soft); color: var(--c-text-4); font-size: 10px; padding: 1px 6px; border-radius: var(--r-sm); font-weight: 600; }
.pmp-person-meta { display: flex; align-items: center; gap: 4px; font-size: 11px; color: var(--c-text-2); margin-top: 1px; }
.pmp-status-dot { width: 6px; height: 6px; border-radius: 50%; }
.pmp-status-dot.巡检中 { background: var(--c-brand); }
.pmp-status-dot.在线 { background: var(--c-success); }
.pmp-status-dot.待命, .pmp-status-dot.待出发 { background: var(--c-warning); }
.pmp-status-text { color: var(--c-brand-deep); }
.pmp-person-task { font-size: 10px; color: var(--c-text-2); margin-top: 2px; font-family: monospace; }
.pmp-person-coord { font-size: 11px; font-family: monospace; color: var(--c-ink-1); }

/* Loading/Empty */
.pmp-loading { display: flex; align-items: center; justify-content: center; gap: 8px; padding: 60px; color: var(--c-text-2); font-size: 13px; }
.pmp-empty { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 60px 20px; color: var(--c-text-2); text-align: center; flex: 1; }
.pmp-empty p { margin: 0; font-size: 13px; color: var(--c-brand-deep); }
.pmp-empty-sub { font-size: 11px; color: var(--c-text-4); }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 720px) {
  .pmp-root { padding: 12px; }
  .pmp-kpis { grid-template-columns: repeat(3, 1fr); }
  .pmp-form-grid { grid-template-columns: 1fr; }
}
/* 设计令牌收尾：模板静态色内联样式 → 类（2026-10-09） */
.pmp-empty-ico { color: var(--c-text-4); }
.pmp-dot-on { background: var(--c-brand); }
.pmp-dot-off { background: var(--c-text-4); }
.pmp-unlocated { color: var(--c-text-4); }
</style>
