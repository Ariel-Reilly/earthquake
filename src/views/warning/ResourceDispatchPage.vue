<script setup>
/**
 * 应急资源调度（独立页面 /dashboard/resource-dispatch）。
 *
 * 救援队伍、物资、避难场所统一调度 —— 基于事件一键指派。
 */
import { computed, onMounted, reactive, ref } from "vue"
import { useRoute } from "vue-router"
import {
  Check,
  ChevronRight,
  Loader2,
  MapPin,
  Package,
  Plus,
  Send,
  Trash2,
  Truck,
  Users,
  UsersRound,
  Navigation,
  MapPinned,
} from "lucide-vue-next"
import { createOpenLayersController } from "../../services/mapService"
import { dashboardApi } from "../../services/dashboardApi"
import { getAuthUser, hasPermission } from "../../stores/auth"

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])

const route = useRoute()
const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" }

const loading = ref(false)
const saving = ref(false)
const resources = ref({ records: [], total: 0 })
const filter = reactive({ type: "", status: "", keyword: "" })
const assignDialog = ref(false)
const assignForm = reactive({
  resourceId: null,
  hazardCode: route.query.hazardCode ?? "",
  priority: "P1",
  note: "",
})

// ===================== 路径规划 Tab 状态 =====================
const routeTabActive = ref(false)
const routeStart = ref("")
const routeEnd = ref("")
const routeAvoidZones = ref([])
const routePlanning = ref(false)
const routeResults = ref([])
let routeMapCtrl = null
const routeMapContainer = ref(null)
const routeMapReady = ref(false)

async function initRouteMap() {
  if (!routeMapContainer.value) return
  try {
    routeMapCtrl = await createOpenLayersController(routeMapContainer.value, { baseStyle: "vector" })
    routeMapReady.value = true
  } catch {
    routeMapReady.value = false
  }
}

function useMyLocation() {
  if (!navigator.geolocation) { emit("notify", "浏览器不支持定位"); return }
  navigator.geolocation.getCurrentPosition(
    (pos) => { routeStart.value = `${pos.coords.longitude.toFixed(5)},${pos.coords.latitude.toFixed(5)}`; emit("notify", "已使用当前位置作为起点") },
    () => emit("notify", "定位失败，请检查权限"),
  )
}

const HAZARD_SEED = [
  { code: "HZ-001", lon: "107.21", lat: "30.61", name: "青龙坡滑坡隐患点" },
  { code: "HZ-002", lon: "107.34", lat: "30.48", name: "大岭村崩塌点" },
  { code: "HZ-003", lon: "107.55", lat: "30.42", name: "白石沟泥石流" },
  { code: "HZ-004", lon: "107.61", lat: "30.67", name: "云台山地裂缝" },
  { code: "HZ-005", lon: "107.14", lat: "30.56", name: "石门沟滑坡点" },
]

function useEventLocation() {
  if (!route.query.hazardCode) { emit("notify", "当前无关联灾害点"); return }
  const hazard = HAZARD_SEED.find((h) => h.code === route.query.hazardCode)
  if (hazard && hazard.lon && hazard.lat) {
    routeStart.value = `${hazard.lon},${hazard.lat}`
    emit("notify", `已使用灾害点 ${hazard.code} 坐标作为起点`)
  } else {
    emit("notify", "未找到灾害点坐标")
  }
}

function addAvoidZone() {
  const name = prompt("输入避障区名称（如：塌方区域）")
  if (!name) return
  const id = Date.now()
  const cx = 107.3 + Math.random() * 0.1
  const cy = 30.5 + Math.random() * 0.1
  const d = 0.02
  routeAvoidZones.value.push({ id, name, polygon: [[cx - d, cy - d], [cx + d, cy - d], [cx + d, cy + d], [cx - d, cy + d]] })
}

function removeAvoidZone(id) {
  routeAvoidZones.value = routeAvoidZones.value.filter((z) => z.id !== id)
}

async function calcRoute() {
  if (!routeStart.value || !routeEnd.value) { emit("notify", "请填写起点和终点"); return }
  routePlanning.value = true
  try {
    const [sl, st, el, et] = [routeStart.value.split(","), routeEnd.value.split(",")]
    const data = await dashboardApi.planRoute({
      startLon: parseFloat(sl[0]), startLat: parseFloat(st[1]),
      endLon: parseFloat(el[0]), endLat: parseFloat(et[1]),
      avoidZones: routeAvoidZones.value.map((z) => z.polygon),
    })
    routeResults.value = Array.isArray(data) ? data : []
    emit("notify", `路径规划完成，共 ${routeResults.value.length} 条方案`)
  } catch {
    emit("notify", "路径规划失败")
    routeResults.value = []
  } finally {
    routePlanning.value = false
  }
}

function exportRouteGPX() {
  if (!routeResults.value.length) return
  const r = routeResults.value[0]
  if (!r.geometry) { emit("notify", "当前方案无可导出路线"); return }
  const gpx = `<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="DimaWatch"><trk><name>${r.type === "fastest" ? "最快路线" : "最安全路线"}</name><trkseg>${r.geometry.map(([lon, lat]) => `<trkpt lat="${lat}" lon="${lon}"></trkpt>`).join("")}</trkseg></trk></gpx>`
  const blob = new Blob([gpx], { type: "application/gpx+xml" })
  const a = document.createElement("a")
  a.href = URL.createObjectURL(blob)
  a.download = `route-${r.type}.gpx`
  a.click()
  URL.revokeObjectURL(a.href)
  emit("notify", "GPX 已导出")
}

// ===================== 原资源调度功能 =====================
const typeMeta = {
  team:    { label: "救援队伍", icon: Users,    color: "#2b6cb0" },
  material:{ label: "应急物资",  icon: Package,  color: "#9b761e" },
  shelter: { label: "避难场所", icon: MapPin,   color: "#2a8154" },
}

const filteredResources = computed(() => {
  let list = resources.value.records ?? []
  if (filter.type)   list = list.filter((r) => r.type === filter.type)
  if (filter.status) list = list.filter((r) => r.status === filter.status)
  if (filter.keyword.trim()) {
    const k = filter.keyword.trim().toLowerCase()
    list = list.filter((r) =>
      (r.name ?? "").toLowerCase().includes(k) ||
      (r.code ?? "").toLowerCase().includes(k) ||
      (r.location ?? "").toLowerCase().includes(k),
    )
  }
  return list
})

const stats = computed(() => {
  const list = resources.value.records ?? []
  return {
    team:      list.filter((r) => r.type === "team").length,
    material:  list.filter((r) => r.type === "material").length,
    shelter:   list.filter((r) => r.type === "shelter").length,
    dispatched: list.filter((r) => r.status === "已派出").length,
  }
})

async function loadResources() {
  loading.value = true
  try {
    const data = await dashboardApi.getResources({ page: 1, size: 50 })
    resources.value = data ?? { records: [], total: 0 }
  } catch (e) { emit("notify", e.message ?? "加载资源失败") }
  finally { loading.value = false }
}

function typeKey(t) {
  const m = { "队伍": "team", "物资": "material", "场所": "shelter" }
  return m[t] ?? "team"
}

function typeInfo(r) {
  return typeMeta[typeKey(r.type)] ?? typeMeta.team
}

function openAssign(r) {
  assignForm.resourceId = r.id
  assignForm.hazardCode = route.query.hazardCode ?? ""
  assignForm.priority = "P1"
  assignForm.note = ""
  assignDialog.value = true
}

async function confirmAssign() {
  if (!assignForm.resourceId) return
  saving.value = true
  try {
    await dashboardApi.assignResource({
      resourceId: assignForm.resourceId,
      hazardCode: assignForm.hazardCode,
      priority: assignForm.priority,
      note: assignForm.note,
      operator: operator.name,
    })
    emit("notify", "资源已派出，任务指派成功")
    assignDialog.value = false
    await loadResources()
  } catch (e) { emit("notify", e.message ?? "指派失败") }
  finally { saving.value = false }
}

const has = (perm) => hasPermission(operator.role, perm)

onMounted(() => loadResources())
</script>

<template>
  <div class="resource-dispatch-shell">
    <header class="page-header">
      <div>
        <h2><Truck :size="18" />应急资源调度</h2>
        <p>救援队伍、物资、避难场所统一调度 —— 基于事件一键指派。</p>
      </div>
      <div class="actions">
        <button class="op-btn" :class="{ active: !routeTabActive }" @click="routeTabActive = false">
          <Truck :size="14" />资源调度
        </button>
        <button class="op-btn" :class="{ active: routeTabActive }" @click="routeTabActive = true; initRouteMap()">
          <Navigation :size="14" />路径规划
        </button>
      </div>
    </header>

    <!-- ========== 资源调度 Tab ========== -->
    <template v-if="!routeTabActive">
      <section class="stat-grid">
        <div class="stat-card">
          <UsersRound :size="18" color="#2b6cb0" />
          <strong>{{ stats.team }}</strong><span>救援队伍</span>
        </div>
        <div class="stat-card">
          <Package :size="18" color="#9b761e" />
          <strong>{{ stats.material }}</strong><span>应急物资</span>
        </div>
        <div class="stat-card">
          <MapPin :size="18" color="#2a8154" />
          <strong>{{ stats.shelter }}</strong><span>避难场所</span>
        </div>
        <div class="stat-card">
          <Send :size="18" color="#c7553b" />
          <strong>{{ stats.dispatched }}</strong><span>已派出</span>
        </div>
      </section>

      <section class="filter-bar">
        <input v-model="filter.keyword" class="filter-input" placeholder="搜索资源名 / 编号 / 位置" />
        <select v-model="filter.type">
          <option value="">全部类型</option>
          <option value="队伍">救援队伍</option>
          <option value="物资">应急物资</option>
          <option value="场所">避难场所</option>
        </select>
        <select v-model="filter.status">
          <option value="">全部状态</option>
          <option value="在岗">在岗</option>
          <option value="可用">可用</option>
          <option value="已派出">已派出</option>
          <option value="不可用">不可用</option>
        </select>
      </section>

      <div v-if="loading" class="loading-row"><Loader2 :size="16" class="spin" />加载资源…</div>
      <ul v-else-if="filteredResources.length" class="resource-grid">
        <li v-for="r in filteredResources" :key="r.id">
          <div class="card-head" :style="{ borderColor: typeInfo(r).color }">
            <component :is="typeInfo(r).icon" :size="22" :style="{ color: typeInfo(r).color }" />
            <div class="meta">
              <strong>{{ r.name }}</strong><code>{{ r.code }}</code>
            </div>
            <span class="type-tag" :style="{ background: typeInfo(r).color + '22', color: typeInfo(r).color }">{{ r.type }}</span>
          </div>
          <ul class="props">
            <li><span>位置</span>{{ r.location ?? "—" }}</li>
            <li><span>规模</span>{{ r.count ?? "—" }}</li>
            <li>
              <span>状态</span>
              <strong :style="{ color: r.status === '已派出' ? '#c7553b' : (r.status === '可用' || r.status === '在岗') ? '#2a8154' : '#9baead' }">{{ r.status }}</strong>
            </li>
          </ul>
          <div class="card-actions">
            <button v-if="has('resource:assign')" class="primary-btn small" :disabled="r.status === '已派出'" @click="openAssign(r)">
              <Send :size="12" />{{ r.status === "已派出" ? "已派出" : "一键指派" }}
            </button>
            <button class="ghost-btn small" @click="emit('notify', `查看 ${r.name} 详情`)" title="查看详情"><ChevronRight :size="12" /></button>
          </div>
        </li>
      </ul>
      <div v-else class="empty-row">没有匹配的资源</div>
    </template>

    <!-- ========== 路径规划 Tab ========== -->
    <template v-if="routeTabActive">
      <section class="route-layout">
        <!-- 左侧面板 -->
        <aside class="route-left">
          <h3 class="route-panel-title"><MapPinned :size="15" />路线设置</h3>
          <label class="route-field">
            <span>起点</span>
            <input v-model="routeStart" placeholder="经度,纬度 如 107.21,30.61" />
            <div class="route-field-btns">
              <button class="ghost-btn small" @click="useMyLocation">使用我的位置</button>
              <button class="ghost-btn small" @click="useEventLocation">使用事件位置</button>
            </div>
          </label>
          <label class="route-field">
            <span>终点</span>
            <input v-model="routeEnd" placeholder="经度,纬度 如 107.55,30.42" />
          </label>
          <div class="route-field">
            <span>避障区</span>
            <button class="op-btn small" @click="addAvoidZone"><Plus :size="12" />添加避障区</button>
          </div>
          <ul class="avoid-list">
            <li v-for="z in routeAvoidZones" :key="z.id" class="avoid-card">
              <span>{{ z.name }}</span>
              <button class="icon-btn danger" @click="removeAvoidZone(z.id)" title="删除"><Trash2 :size="12" /></button>
            </li>
            <li v-if="!routeAvoidZones.length" class="avoid-empty">暂无避障区</li>
          </ul>
        </aside>

        <!-- 中心地图 -->
        <div class="route-map-stage">
          <div ref="routeMapContainer" class="route-ol-map"></div>
          <div v-if="!routeMapReady" class="map-loading route-map-loading">
            <Loader2 :size="16" class="spin" />地图初始化中…
          </div>
        </div>

        <!-- 右侧对比面板 -->
        <aside class="route-right">
          <h3 class="route-panel-title"><Navigation :size="15" />方案对比</h3>
          <div v-if="routePlanning" class="loading-hint"><Loader2 :size="14" class="spin" />规划中…</div>
          <ul v-else-if="routeResults.length" class="route-results">
            <li v-for="r in routeResults" :key="r.type" class="route-result-item" :class="{ recommended: r.type === 'fastest' }">
              <div class="route-result-head">
                <span class="route-type-tag" :style="{ background: r.type === 'fastest' ? '#4f8f99' : '#d9c040', color: '#fff' }">
                  {{ r.type === "fastest" ? "最快" : "最安全" }}
                </span>
                <strong>{{ r.distance }} km</strong>
              </div>
              <div class="route-result-meta">
                <span>预计耗时</span>
                <strong>{{ r.duration }}</strong>
              </div>
              <div v-if="r.warning" class="route-result-warning">{{ r.warning }}</div>
            </li>
          </ul>
          <div v-else class="empty-hint-sm">点击"重新计算"获取路线方案</div>
          <div class="route-actions-top">
            <button class="primary-btn" @click="calcRoute" :disabled="routePlanning">
              <Loader2 v-if="routePlanning" :size="13" class="spin" />重新计算
            </button>
            <button class="secondary-btn" @click="exportRouteGPX" :disabled="!routeResults.length">导出 GPX</button>
          </div>
        </aside>
      </section>
    </template>

    <!-- 资源指派弹窗（两个 Tab 都可访问） -->
    <div v-if="assignDialog" class="modal-mask" @click.self="assignDialog = false">
      <div class="modal-card">
        <h3><Send :size="16" />资源指派</h3>
        <label>关联灾害点编号<input v-model="assignForm.hazardCode" placeholder="如 HZ-001" /></label>
        <label>优先级
          <div class="priority-row">
            <button v-for="p in ['P0','P1','P2','P3']" :key="p"
              :class="{ active: assignForm.priority === p }"
              :style="{ color: assignForm.priority === p ? '#fff' : '', background: assignForm.priority === p ? (p === 'P0' ? '#c7553b' : p === 'P1' ? '#d7a546' : p === 'P2' ? '#5d95c1' : '#9baead') : '' }"
              @click="assignForm.priority = p">{{ p }}</button>
          </div>
        </label>
        <label>说明<textarea v-model="assignForm.note" rows="3" placeholder="任务目标 / 注意事项" /></label>
        <div class="modal-actions">
          <button class="ghost-btn" @click="assignDialog = false">取消</button>
          <button class="primary-btn" :disabled="saving" @click="confirmAssign">
            <Loader2 v-if="saving" :size="14" class="spin" />确认指派
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.resource-dispatch-shell { padding: 24px 28px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: #1f3e44; }
.page-header p { margin: 4px 0 0; color: #7a8d89; font-size: 12px; }

.op-btn { display: flex; gap: 6px; align-items: center; padding: 7px 12px; border: 1px solid #d9e6e1; background: #fff; border-radius: 8px; color: #37555a; font-size: 12px; cursor: pointer; }
.op-btn:hover { background: #f4f7f5; }
.op-btn.active { border-color: #2f7f80; color: #2f7f80; background: #eff9f6; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.stat-card { padding: 14px 16px; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; display: flex; gap: 10px; align-items: center; }
.stat-card strong { font-size: 22px; color: #1f3e44; }
.stat-card span { color: #7a8d89; font-size: 12px; }
.stat-card > strong, .stat-card > span { display: block; }

.filter-bar { display: flex; gap: 10px; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 14px; margin-bottom: 14px; }
.filter-input { flex: 1; padding: 8px 12px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; }
.filter-bar select { padding: 8px 12px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fff; }

.loading-row, .empty-row { padding: 32px; text-align: center; color: #7a8d89; font-size: 13px; }

.resource-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; list-style: none; padding: 0; margin: 0; }
.resource-grid li { background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 14px; transition: box-shadow .15s; }
.resource-grid li:hover { box-shadow: 0 4px 14px rgba(0,0,0,.05); }
.card-head { display: flex; gap: 10px; align-items: center; padding-bottom: 12px; border-bottom: 1px dashed #e9eee9; margin-bottom: 12px; border-left: 3px solid; padding-left: 8px; }
.card-head .meta { flex: 1; min-width: 0; }
.card-head strong { display: block; color: #1f3e44; font-size: 14px; }
.card-head code { color: #7a8d89; font-size: 11px; }
.type-tag { padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }

.props { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 6px; }
.props li { display: flex; gap: 8px; font-size: 12px; color: #37555a; }
.props li span { width: 48px; color: #7a8d89; }

.card-actions { display: flex; gap: 6px; margin-top: 14px; padding-top: 12px; border-top: 1px dashed #e9eee9; }
.primary-btn.small { flex: 1; }
.primary-btn { display: flex; gap: 6px; align-items: center; padding: 7px 12px; border: 0; background: #2f7f80; color: #fff; border-radius: 8px; cursor: pointer; font-size: 12px; justify-content: center; }
.primary-btn:disabled { opacity: .5; }
.ghost-btn.small { padding: 7px 10px; }
.ghost-btn { padding: 7px 12px; border: 1px solid #d9e6e1; background: #fff; border-radius: 8px; color: #37555a; cursor: pointer; font-size: 12px; }

.modal-mask { position: fixed; inset: 0; background: rgba(15,32,38,.45); display: grid; place-items: center; z-index: 100; }
.modal-card { width: min(440px, 92vw); background: #fff; border-radius: 14px; padding: 22px; }
.modal-card h3 { margin: 0 0 16px; color: #1f3e44; display: flex; gap: 6px; align-items: center; }
.modal-card label { display: block; margin-bottom: 14px; color: #56716e; font-size: 12px; font-weight: 600; }
.modal-card input, .modal-card textarea { display: block; width: 100%; margin-top: 6px; padding: 8px 10px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; }

.priority-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; margin-top: 6px; }
.priority-row button { padding: 8px; border: 1px solid #d9e6e1; background: #fafdfc; border-radius: 6px; cursor: pointer; font-weight: 600; }

.modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 18px; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

/* 路径规划 Tab */
.route-layout {
  display: grid;
  grid-template-columns: 280px 1fr 280px;
  gap: 0;
  height: calc(100vh - 220px);
  border: 1px solid #e9eee9;
  border-radius: 12px;
  overflow: hidden;
  margin-top: 14px;
}
.route-left { padding: 16px; border-right: 1px solid #e9eee9; overflow-y: auto; background: #fff; }
.route-panel-title { display: flex; gap: 6px; align-items: center; color: #1f3e44; font-size: 13px; margin: 0 0 14px; }
.route-field { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.route-field > span { color: #56716e; font-size: 12px; font-weight: 600; }
.route-field input { padding: 8px 10px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; font-size: 12px; color: #24494d; }
.route-field-btns { display: flex; gap: 6px; flex-wrap: wrap; }
.op-btn.small { padding: 5px 10px; font-size: 11px; }
.secondary-btn { display: flex; gap: 6px; align-items: center; padding: 7px 12px; border: 1px solid #d9e6e1; background: #fff; border-radius: 8px; color: #37555a; cursor: pointer; font-size: 12px; }
.secondary-btn:disabled { opacity: .5; cursor: not-allowed; }
.avoid-list { list-style: none; padding: 0; margin: 8px 0 0; display: flex; flex-direction: column; gap: 6px; }
.avoid-card { display: flex; justify-content: space-between; align-items: center; padding: 8px 10px; background: #fffaf0; border: 1px solid #e7d3a3; border-radius: 8px; font-size: 12px; color: #8a6d3b; }
.avoid-empty { font-size: 12px; color: #7a8d89; text-align: center; padding: 10px 0; }
.icon-btn.danger { color: #c7553b; }
.route-map-stage { position: relative; background: #e9f2ee; }
.route-ol-map { position: absolute; inset: 0; }
.route-map-loading { position: absolute; inset: 0; display: flex; gap: 8px; align-items: center; justify-content: center; background: rgba(244,247,245,.85); color: #56716e; }
.route-right { padding: 16px; border-left: 1px solid #e9eee9; overflow-y: auto; background: #fff; display: flex; flex-direction: column; gap: 12px; }
.loading-hint { font-size: 12px; color: #7a8d89; display: flex; gap: 6px; align-items: center; }
.route-results { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.route-result-item { padding: 12px; border: 1px solid #e9eee9; border-radius: 10px; background: #fafdfc; }
.route-result-item.recommended { border-color: #4f8f99; background: #f0f8f8; }
.route-result-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.route-type-tag { padding: 2px 8px; border-radius: 5px; font-size: 11px; font-weight: 600; }
.route-result-head strong { font-size: 16px; color: #1f3e44; }
.route-result-meta { display: flex; justify-content: space-between; font-size: 12px; color: #56716e; }
.route-result-warning { margin-top: 6px; padding: 5px 8px; background: #fbecec; color: #9c3d3d; border-radius: 6px; font-size: 11px; }
.empty-hint-sm { font-size: 12px; color: #7a8d89; text-align: center; padding: 20px 0; }
.route-actions-top { display: flex; flex-direction: column; gap: 8px; margin-top: auto; }
.route-actions-top .primary-btn { width: 100%; }
.route-actions-top .secondary-btn { width: 100%; justify-content: center; }
</style>
