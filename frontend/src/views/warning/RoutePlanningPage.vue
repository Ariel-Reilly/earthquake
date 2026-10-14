<script setup>
/**
 * 灾区路径规划主页面 /dashboard/route-planning。
 * 顶部：起终点 + 避障参数 + 计算按钮（RouteForm）
 * 主体左侧：地图区域（SVG 渲染，多路径、避障多边形区分色）
 * 主体右侧：方案对比列表（RouteCompareCard）
 * 底部：导出与分享
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  Download,
  Layers,
  Loader2,
  Map as MapIcon,
  Share2,
} from 'lucide-vue-next'
import RouteForm from '../../components/route-planning/RouteForm.vue'
import RouteCompareCard from '../../components/route-planning/RouteCompareCard.vue'
import { dashboardApi } from '../../services/dashboardApi'
import { getAuthUser, hasPermission } from '../../stores/auth'
import { safeMin, safeMax } from '../../utils/safeMinMax'

const emit = defineEmits(['notify'])
defineProps({ searchTerm: { type: String, default: '' }, userRole: { type: String, default: 'admin' } })

const operator = computed(() => getAuthUser() ?? { name: 'system', role: 'admin' })
const canExport = computed(() => hasPermission(operator.value.role, 'route:export'))

const obstacles = ref([])
const resources = ref([])
const routes    = ref([])
const activeId  = ref('')
const loading   = ref(false)
const planError = ref('')
// 地图范围对齐项目主口径（重庆主城及北碚片区，覆盖 resource 表真实资源点经纬度）
const bbox = reactive({ minLng: 106.35, maxLng: 106.70, minLat: 29.45, maxLat: 29.90 })
const showRiskArea = ref(true)

const activeRoute = computed(() => routes.value.find((r) => r.id === activeId) ?? routes.value[0])

// 投影：把经纬度映射到 800 x 500 的 SVG 画布
const MAP_W = 880
const MAP_H = 520
function project(lng, lat) {
  const x = ((lng - bbox.minLng) / (bbox.maxLng - bbox.minLng)) * MAP_W
  const y = MAP_H - ((lat - bbox.minLat) / (bbox.maxLat - bbox.minLat)) * MAP_H
  return [x, y]
}

const styleByMode = {
  fastest:  { color: '#9dc0aa', dash: 'none', label: '最快' },
  safest:   { color: '#10b981', dash: '6 4',  label: '最安全' },
  shortest: { color: '#f59e0b', dash: '2 4',  label: '最短' },
}

const projectedRoutes = computed(() => {
  return routes.value.map((r) => {
    const pts = (r.geometry ?? []).map(([lng, lat]) => project(lng, lat))
    const line = pts.map(([x, y], i) => `${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`).join(' ')
    return { ...r, _line: line, _pts: pts }
  })
})

const projectedObstacles = computed(() => {
  return obstacles.value.map((o) => {
    const pts = (o.coordinates ?? []).map(([lng, lat]) => project(lng, lat))
    return { ...o, _points: pts.map((p) => p.join(',')).join(' ') }
  })
})

// 起点：优先取真实资源点中的救援队；无数据时回退到地图中心偏西。
// 终点（灾区）：后端规划请求由 RouteForm 提供，此处仅用于地图示意打点。
const startPoint = computed(() => {
  const team = resources.value.find((r) => r.type === 'rescue_team') ?? resources.value[0]
  if (team) {
    const [x, y] = project(team.lng, team.lat)
    return { x, y, label: team.name }
  }
  const [x, y] = project((bbox.minLng + bbox.maxLng) / 2, (bbox.minLat + bbox.maxLat) / 2)
  return { x, y, label: '起点' }
})
const endPoint = computed(() => {
  // 灾区示意点：位于当前视野北侧（与灾害常发片区一致）。
  const [x, y] = project((bbox.minLng + bbox.maxLng) / 2 + 0.08, (bbox.minLat + bbox.maxLat) / 2 + 0.12)
  return { x, y, label: '灾区' }
})

async function loadObstacles() {
  try {
    obstacles.value = await dashboardApi.getObstacleAreas()
  } catch (_e) {
    obstacles.value = []
  }
}

async function loadResources() {
  // 资源点来自后端 resource 表（GET /api/resources），非本地硬编码。
  // 仅取带经纬度的资源用于地图打点。
  try {
    const page = await dashboardApi.getResources({ page: 1, size: 200 })
    const list = page?.records ?? page?.items ?? []
    resources.value = list
      .filter((r) => r.longitude != null && r.latitude != null)
      .map((r) => ({
        id: String(r.id ?? r.resCode ?? r.res_code),
        name: r.resName ?? r.res_name ?? r.name ?? '未命名资源',
        lng: Number(r.longitude),
        lat: Number(r.latitude),
        type: r.resType ?? r.res_type ?? '',
        status: r.status ?? '',
      }))
  } catch (e) {
    resources.value = []
    emit('notify', `资源点加载失败：${e?.message ?? e}`)
  }
}

async function calculate(payload) {
  loading.value = true
  planError.value = ''
  try {
    const data = await dashboardApi.planRoutes(payload)
    routes.value = Array.isArray(data) ? data : (data?.routes ?? [])
    if (routes.value.length) activeId.value = routes.value[0].id
    emit('notify', `已生成 ${routes.value.length} 条路径`)
  } catch (e) {
    planError.value = e?.message ?? '路径计算失败'
    routes.value = []
    activeId.value = ''
  } finally {
    loading.value = false
  }
}

function exportRoute(route) {
  if (!canExport.value) {
    emit('notify', '当前角色无导出权限（需要 route:export）')
    return
  }
  const geojson = {
    type: 'Feature',
    properties: {
      name: route.id, mode: route.mode,
      distance: route.distance, duration: route.duration,
      riskLevel: route.riskLevel,
    },
    geometry: {
      type: 'LineString',
      coordinates: route.geometry ?? [],
    },
  }
  const blob = new Blob([JSON.stringify(geojson, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${route.id || 'route'}.geojson`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
  emit('notify', `已导出 ${route.id}.geojson`)
}

async function shareRoute(route) {
  const data = encodeURIComponent(JSON.stringify(route.geometry))
  const url = `${window.location.origin}/dashboard/route-planning?share=${route.id}&geom=${data.slice(0, 256)}`
  try {
    await navigator.clipboard.writeText(url)
    emit('notify', '分享链接已复制到剪贴板')
  } catch {
    emit('notify', '当前浏览器不支持复制：' + url)
  }
}

function fitBbox() {
  if (!routes.value.length) return
  const allLng = []
  const allLat = []
  routes.value.forEach((r) => (r.geometry ?? []).forEach(([lng, lat]) => {
    allLng.push(lng); allLat.push(lat)
  }))
  if (!allLng.length) return
  const pad = 0.05
  // ⚠️ 不写 Math.min(...allLng)：路线节点 + 灾害点合并后可能上千点。
  // 用 safeMin/safeMax，O(1) 栈深度。
  bbox.minLng = safeMin(allLng) - pad
  bbox.maxLng = safeMax(allLng) + pad
  bbox.minLat = safeMin(allLat) - pad
  bbox.maxLat = safeMax(allLat) + pad
}

watch(activeId, () => fitBbox())

onMounted(async () => {
  await Promise.all([loadObstacles(), loadResources()])
})
</script>

<template>
  <div class="route-page">
    <header class="head">
      <div>
      </div>
      <label class="toggle">
        <input type="checkbox" v-model="showRiskArea" />
        <Layers :size="14" /> 显示避障区域
      </label>
    </header>

    <div class="grid">
      <!-- 左侧：表单 -->
      <section class="panel form-panel">
        <h3>规划参数</h3>
        <RouteForm
          :obstacles="obstacles"
          :resources="resources"
          :loading="loading"
          @add-obstacle="(o) => obstacles.push(o)"
          @calculate="calculate"
        />
        <div v-if="planError" class="err">{{ planError }}</div>
      </section>

      <!-- 中间：地图 -->
      <section class="panel map-panel">
        <div class="map-header">
          <h3>路径总览</h3>
          <span class="meta">
            <span v-if="loading"><Loader2 :size="13" class="spin" /> 计算中…</span>
            <span v-else-if="routes.length">{{ routes.length }} 条方案 · 当前选中：{{ activeRoute?.mode ?? '—' }}</span>
            <span v-else>未计算</span>
          </span>
        </div>
        <div class="map-wrap">
          <svg :viewBox="`0 0 ${MAP_W} ${MAP_H}`" preserveAspectRatio="xMidYMid meet">
            <defs>
              <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
                <path d="M 40 0 L 0 0 0 40" fill="none" stroke="rgba(255,255,255,0.04)" stroke-width="1" />
              </pattern>
              <radialGradient id="bg" cx="50%" cy="50%" r="80%">
                <stop offset="0%" stop-color="#1f2a4a" />
                <stop offset="100%" stop-color="#0a1230" />
              </radialGradient>
            </defs>
            <rect width="100%" height="100%" fill="url(#bg)" />
            <rect width="100%" height="100%" fill="url(#grid)" />

            <!-- 避障多边形 -->
            <g v-if="showRiskArea">
              <polygon
                v-for="o in projectedObstacles"
                :key="o.id"
                :points="o._points"
                fill="rgba(239,68,68,0.18)"
                stroke="rgba(239,68,68,0.65)"
                stroke-width="1.5"
              />
              <text
                v-for="o in projectedObstacles"
                :key="`l-${o.id}`"
                :x="(parseFloat(o._points.split(' ')[0].split(',')[0])) + 6"
                :y="(parseFloat(o._points.split(' ')[0].split(',')[1])) - 6"
                fill="#fca5a5" font-size="11"
              >⚠ {{ o.name }}</text>
            </g>

            <g>
              <g v-for="r in resources" :key="r.id">
                <circle :cx="project(r.lng, r.lat)[0]" :cy="project(r.lng, r.lat)[1]" r="6" fill="#8a9c90" stroke="#fff" stroke-width="1.5" />
                <text :x="project(r.lng, r.lat)[0] + 9" :y="project(r.lng, r.lat)[1] + 4" fill="#c8d8ce" font-size="11">{{ r.name }}</text>
              </g>
            </g>

            <g v-if="!loading && routes.length">
              <path
                v-for="r in projectedRoutes"
                :key="r.id"
                :d="r._line"
                fill="none"
                :stroke="styleByMode[r.mode]?.color"
                :stroke-width="activeId === r.id ? 4 : 2"
                :stroke-dasharray="styleByMode[r.mode]?.dash"
                stroke-linecap="round" stroke-linejoin="round"
                opacity="0.9"
              />
            </g>

            <!-- waypoints -->
            <g v-if="activeRoute">
              <circle
                v-for="(w, i) in (activeRoute.waypoints ?? [])"
                :key="i"
                :cx="project(w.lng, w.lat)[0]"
                :cy="project(w.lng, w.lat)[1]"
                :r="w.type === 'start' || w.type === 'end' ? 8 : 5"
                :fill="w.type === 'start' ? '#9dc0aa' : (w.type === 'end' ? '#ef4444' : '#fbbf24')"
                stroke="#fff" stroke-width="1.5"
              />
              <text
                v-for="(w, i) in (activeRoute.waypoints ?? [])"
                :key="`t-${i}`"
                :x="project(w.lng, w.lat)[0] + 10"
                :y="project(w.lng, w.lat)[1] + 4"
                fill="#e5e7eb" font-size="11"
              >{{ w.name }}</text>
            </g>

            <!-- start / end 大标记 -->
            <g>
              <circle :cx="startPoint.x" :cy="startPoint.y" r="10" fill="#9dc0aa" stroke="#fff" stroke-width="2" />
              <text :x="startPoint.x + 12" :y="startPoint.y + 5" fill="#fff" font-size="13" font-weight="600">起点</text>
              <circle :cx="endPoint.x" :cy="endPoint.y" r="10" fill="#ef4444" stroke="#fff" stroke-width="2" />
              <text :x="endPoint.x + 12" :y="endPoint.y + 5" fill="#fff" font-size="13" font-weight="600">灾区</text>
            </g>

            <g transform="translate(20, 480)">
              <rect x="-8" y="-22" width="290" height="46" rx="6" fill="rgba(15,23,42,0.65)" stroke="rgba(148,163,184,0.3)" />
              <g v-for="(mode, key) in styleByMode" :key="key" :transform="`translate(${[0, 90, 180][Object.keys(styleByMode).indexOf(key)]}, 0)`">
                <line x1="0" y1="0" x2="22" y2="0" :stroke="mode.color" :stroke-dasharray="mode.dash" stroke-width="3" />
                <text x="28" y="4" fill="#c8d8ce" font-size="12">{{ mode.label }}</text>
              </g>
            </g>
          </svg>
        </div>
      </section>

      <!-- 右侧：方案 -->
      <section class="panel route-panel">
        <div class="route-head">
          <h3>方案列表</h3>
          <div class="route-actions">
            <button class="ghost" :disabled="!activeRoute" @click="exportRoute(activeRoute)">
              <Download :size="13" /> 导出 GeoJSON
            </button>
            <button class="ghost" :disabled="!activeRoute" @click="shareRoute(activeRoute)">
              <Share2 :size="13" /> 分享链接
            </button>
          </div>
        </div>
        <RouteCompareCard
          :routes="routes"
          :active-id="activeId"
          @select="(r) => activeId = r.id"
          @export="exportRoute"
          @share="shareRoute"
        />
      </section>
    </div>
  </div>
</template>

<style scoped>
.route-page { padding: 22px 28px; display: flex; flex-direction: column; gap: 14px; height: 100%; overflow: auto; }
.head { display: flex; justify-content: space-between; align-items: flex-end; border-bottom: 1px solid var(--dashboard-border, var(--c-brand-mist)); padding-bottom: 12px; }
.head h2 { display: inline-flex; align-items: center; gap: 8px; margin: 0; font-size: 20px; }
.head p { margin: 4px 0 0; color: var(--dashboard-muted, var(--c-text-2)); font-size: 13px; max-width: 600px; }
.toggle {
  display: inline-flex; align-items: center; gap: 6px;
  border: 1px solid var(--dashboard-border, var(--c-brand-tint)); padding: 6px 12px; border-radius: var(--r-pill);
  font-size: 12px; color: var(--dashboard-muted, var(--c-ink-1)); cursor: pointer;
}
.toggle input { accent-color: var(--c-brand-tint); }
.grid { display: grid; grid-template-columns: 320px 1fr 320px; gap: 16px; }
@media (max-width: 1180px) { .grid { grid-template-columns: 1fr; } }
.panel {
  background: var(--dashboard-card-bg, var(--c-surface));
  border: 1px solid var(--dashboard-border, var(--c-brand-mist)); border-radius: var(--r-lg);
  padding: 16px 18px; display: flex; flex-direction: column; gap: 10px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.04);
}
.panel h3 { margin: 0; font-size: 16px; }
.map-panel { padding: 0; overflow: hidden; }
.map-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 18px; border-bottom: 1px solid var(--c-border);
  background: linear-gradient(135deg, #1f2a4a, #13193b);
  color: var(--c-on-brand);
}
.map-header h3 { color: var(--c-on-brand); }
.meta { font-size: 12px; color: var(--c-brand-tint); display: inline-flex; align-items: center; gap: 6px; }
.map-wrap { background: #0a1230; }
.map-wrap svg { width: 100%; height: auto; display: block; }
.route-head { display: flex; justify-content: space-between; align-items: center; }
.route-actions { display: flex; gap: 6px; }
.ghost {
  background: transparent; color: var(--dashboard-muted, var(--c-ink-1));
  border: 1px solid var(--dashboard-border, var(--c-brand-tint)); padding: 4px 10px;
  border-radius: var(--r-sm); cursor: pointer; font-size: 12px;
  display: inline-flex; align-items: center; gap: 4px;
}
.ghost:disabled { opacity: 0.4; cursor: not-allowed; }
.ghost:hover:not(:disabled) { border-color: var(--c-brand-tint); color: var(--c-brand-deep); }
.err { color: var(--c-danger); font-size: 12px; padding: 6px 10px; background: var(--c-danger-bg); border-radius: var(--r-sm); }
.spin { animation: rotate 1s linear infinite; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
