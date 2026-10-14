<script setup>
/**
 * 历史灾害库（独立页面 /dashboard/history-hazards）。
 *
 * 时间、地点、灾种、等级多维检索 —— 沉淀数据、辅助研判、对外报送。
 */
import { computed, onMounted, reactive, ref, watch } from "vue"
import { useRouter } from "vue-router"
import {
  Calendar,
  Database,
  Download,
  Filter,
  Loader2,
  MapPin,
  MountainSnow,
  Search,
  TrendingUp,
} from "lucide-vue-next"
import { dashboardApi } from "../../services/dashboardApi"
import { getAuthUser, hasPermission } from "../../stores/auth"
import { HAZARD_TYPES, hazardTypeLabel } from "../../utils/hazardTypes"

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])

const router = useRouter()
const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" }

const loading = ref(false)
const allHazards = ref([])
const filter = reactive({
  keyword: "",
  type: "",
  level: "",
  year: "",
  region: "",
})

// R3：灾种不再在本页各写一份，统一引用单一数据源
// （与后端 sys_dict.disaster_type 对齐，详见 utils/hazardTypes.js）
const hazardOptions = HAZARD_TYPES

const yearOptions = computed(() => {
  const years = new Set([...allHazards.value.map((h) => {
    if (!h.discoveredAt) return null
    return new Date(h.discoveredAt).getFullYear()
  })].filter(Boolean))
  return ["", ...[...years].sort((a, b) => b - a)]
})

const filtered = computed(() => {
  let list = [...allHazards.value]
  if (filter.type) list = list.filter((h) => h.type === filter.type)
  if (filter.level) list = list.filter((h) => h.level === filter.level)
  if (filter.year) list = list.filter((h) => {
    if (!h.discoveredAt) return false
    return new Date(h.discoveredAt).getFullYear().toString() === filter.year
  })
  if (filter.region.trim()) {
    const k = filter.region.trim().toLowerCase()
    // R3 P1-13：后端 HazardDto / HazardPoint 只有 regionCode（行政区划代码），
    // 并没有 region 或 location 字段。修复前读 h.region ?? h.location 恒为
    // undefined → 兜底成 "" → 任何非空关键字都匹配不上，列表被静默清空。
    // 这里改为匹配 regionCode（占位提示已同步改为「行政区代码」）。
    list = list.filter((h) => (h.regionCode ?? "").toLowerCase().includes(k))
  }
  if (filter.keyword.trim()) {
    const k = filter.keyword.trim().toLowerCase()
    list = list.filter(
      (h) => (h.name ?? "").toLowerCase().includes(k) ||
             (h.hazardCode ?? "").toLowerCase().includes(k),
    )
  }
  return list.sort((a, b) => (b.discoveredAt ?? "").localeCompare(a.discoveredAt ?? ""))
})

const stats = computed(() => {
  const list = allHazards.value
  return {
    total: list.length,
    critical: list.filter((h) => h.level === "高风险").length,
    watch: list.filter((h) => h.level === "关注").length,
    closed: list.filter((h) => h.status === "已闭环" || h.status === "已处置").length,
  }
})

const typeChart = computed(() => {
  const map = new Map()
  for (const h of filtered.value) {
    const label = hazardTypeLabel(h.type) || "其他"
    map.set(label, (map.get(label) ?? 0) + 1)
  }
  return [...map.entries()].map(([type, count]) => ({ type, count })).sort((a, b) => b.count - a.count)
})

async function load() {
  loading.value = true
  try {
    const data = await dashboardApi.getHazards({ page: 1, size: 1000 })
    allHazards.value = data?.list ?? []
  } catch (e) {
    emit("notify", e.message ?? "加载历史灾害失败")
  } finally {
    loading.value = false
  }
}

function openDetail(h) {
  router.push(`/dashboard/hazards/${h.id}`)
}

function exportCsv() {
  if (!hasPermission(operator.role, "hazards:export")) return emit("notify", "无导出权限")
  const rows = [
    ["编号", "名称", "灾种", "等级", "状态", "发现时间", "位置"].join(","),
    ...filtered.value.map((h) =>
      [
        h.hazardCode ?? "",
        '"' + (h.name ?? "").replace(/"/g, '""') + '"',
        h.type ?? "",
        h.level ?? "",
        h.status ?? "",
        h.discoveredAt ?? "",
        h.lon && h.lat ? `${h.lon},${h.lat}` : "",
      ].join(","),
    ),
  ].join("\n")
  const blob = new Blob(["\uFEFF" + rows], { type: "text/csv;charset=utf-8" })
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = `history-hazards-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
  emit("notify", "历史灾害已导出")
}

function resetFilters() {
  Object.assign(filter, { keyword: "", type: "", level: "", year: "", region: "" })
}

watch(filter, () => {}, { deep: true })

onMounted(() => load())
</script>

<template>
  <div class="history-hazards-shell">
    <header class="page-header">
      <div>
      </div>
      <div class="actions">
        <button class="op-btn" @click="exportCsv"><Download :size="14" />导出 CSV</button>
      </div>
    </header>

    <section class="stat-grid">
      <div class="stat-card"><strong>{{ stats.total }}</strong><span>灾害记录总数</span></div>
      <div class="stat-card"><strong class="stat-num--danger">{{ stats.critical }}</strong><span>高风险历史事件</span></div>
      <div class="stat-card"><strong class="stat-num--watch">{{ stats.watch }}</strong><span>关注事件</span></div>
      <div class="stat-card"><strong class="stat-num--closed">{{ stats.closed }}</strong><span>已闭环</span></div>
    </section>

    <section class="filter-bar">
      <Filter :size="14" />
      <input v-model="filter.keyword" placeholder="搜索编号 / 名称" class="filter-input" />
      <select v-model="filter.type">
        <option value="">全部灾种</option>
        <option v-for="opt in hazardOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
      </select>
      <select v-model="filter.level">
        <option value="">全部等级</option>
        <option value="高风险">高风险</option>
        <option value="关注">关注</option>
        <option value="正常">正常</option>
      </select>
      <select v-model="filter.year">
        <option v-for="y in yearOptions" :key="y" :value="y">{{ y ? `${y} 年` : '全部年份' }}</option>
      </select>
      <input v-model="filter.region" placeholder="行政区代码" class="filter-input small" title="按行政区划代码筛选（如 110000）；后端当前仅提供 regionCode 字段" />
      <button class="ghost-btn small" @click="resetFilters">重置</button>
    </section>

    <div class="content-grid">
      <section class="distribution-panel">
        <h3><TrendingUp :size="14" />灾种分布</h3>
        <ul class="type-bar">
          <li v-for="t in typeChart" :key="t.type">
            <span class="type-name">{{ t.type }}</span>
            <div class="track">
              <div class="fill" :style="{ width: (t.count / Math.max(...typeChart.map((x) => x.count), 1)) * 100 + '%' }"></div>
            </div>
            <strong>{{ t.count }}</strong>
          </li>
        </ul>
      </section>

      <section class="list-panel">
        <div v-if="loading" class="loading-row">
          <Loader2 :size="16" class="spin" />加载中…
        </div>
        <ul v-else-if="filtered.length" class="hazard-list">
          <li v-for="h in filtered" :key="h.id" @click="openDetail(h)">
            <div class="level-dot" :class="h.level === '高风险' ? 'orange' : h.level === '关注' ? 'yellow' : 'blue'">
              {{ h.level === '高风险' ? '!' : h.level === '关注' ? '?' : '✓' }}
            </div>
            <div class="body">
              <div class="title">
                <strong>{{ h.name }}</strong>
                <code>{{ h.hazardCode }}</code>
                <span class="level-tag" :class="h.level === '高风险' ? 'orange' : h.level === '关注' ? 'yellow' : 'blue'">{{ h.level }}</span>
              </div>
              <p>{{ hazardTypeLabel(h.type) }} · {{ h.status }}</p>
              <small>
                <Calendar :size="11" /> {{ h.discoveredAt ? new Date(h.discoveredAt).toLocaleString('zh-CN', { hour12: false }) : '—' }}
                <span style="margin: 0 6px;">·</span>
                <MapPin :size="11" /> {{ h.lon }}, {{ h.lat }}
              </small>
            </div>
          </li>
        </ul>
        <div v-else class="empty-row">没有匹配的历史灾害记录</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.history-hazards-shell { padding: 24px 28px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: var(--c-text); }
.page-header p { margin: 4px 0 0; color: var(--c-text-2); font-size: 12px; }
.op-btn { display: flex; gap: 6px; align-items: center; padding: 7px 12px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-sm); color: var(--c-text); font-size: 12px; cursor: pointer; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.stat-card { padding: 14px 16px; background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); }
.stat-card strong { display: block; font-size: 22px; color: var(--c-text); }
.stat-card span { color: var(--c-text-2); font-size: 12px; }

.filter-bar { display: flex; gap: 10px; align-items: center; background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 12px 16px; margin-bottom: 14px; }
.filter-bar .filter-input { flex: 1; padding: 7px 12px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); background: var(--c-surface-2); }
.filter-bar .filter-input.small { flex: 0 0 140px; }
.filter-bar select { padding: 7px 12px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); background: var(--c-surface); }
.ghost-btn.small { padding: 6px 12px; font-size: 12px; }
.ghost-btn { padding: 7px 12px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-sm); color: var(--c-text); cursor: pointer; font-size: 12px; }

.content-grid { display: grid; grid-template-columns: 280px 1fr; gap: 14px; }
.distribution-panel, .list-panel { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 14px 16px; }
.distribution-panel h3 { margin: 0 0 12px; display: flex; gap: 6px; align-items: center; font-size: 13px; color: var(--c-brand-deep); }
.type-bar { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.type-bar li { display: flex; gap: 8px; align-items: center; }
.type-bar .type-name { width: 60px; font-size: 12px; color: var(--c-brand-deep); }
.type-bar .track { flex: 1; height: 8px; background: var(--c-bg); border-radius: var(--r-xs); overflow: hidden; }
.type-bar .fill { height: 100%; background: var(--c-brand); border-radius: var(--r-xs); }
.type-bar strong { font-size: 12px; color: var(--c-text); min-width: 30px; text-align: right; }

.loading-row, .empty-row { padding: 32px; text-align: center; color: var(--c-text-2); font-size: 13px; }

.hazard-list { list-style: none; margin: 0; padding: 0; max-height: 600px; overflow-y: auto; }
.hazard-list li { display: flex; gap: 12px; padding: 12px 14px; border-bottom: 1px solid var(--c-border-2); cursor: pointer; transition: background .12s; }
.hazard-list li:hover { background: var(--c-bg); }
.level-dot { flex: 0 0 32px; width: 32px; height: 32px; border-radius: 50%; color: var(--c-on-brand); display: grid; place-items: center; font-weight: 700; }
.level-dot.orange { background: var(--c-danger-strong); }
.level-dot.yellow { background: var(--c-warning-bg); }
.level-dot.blue   { background: var(--c-brand); }
.body { flex: 1; min-width: 0; }
.title { display: flex; gap: 8px; align-items: center; }
.title strong { color: var(--c-text); font-size: 13px; }
.title code { color: var(--c-text-2); font-size: 11px; }
.level-tag { padding: 1px 6px; border-radius: 3px; font-size: 10px; }
.level-tag.orange { background: var(--c-danger-bg); color: var(--c-danger-strong); }
.level-tag.yellow { background: var(--c-warning-bg); color: var(--c-warning-ink); }
.level-tag.blue { background: var(--c-success-bg); color: var(--c-brand-deep); }
/* 统计数字强调色（原为模板内联 style，改为 class 以支持暗色主题）*/
.stat-num--danger { color: var(--c-danger-strong); }
.stat-num--watch { color: var(--c-warning); }
.stat-num--closed { color: var(--c-brand-deep); }
.body p { margin: 2px 0; color: var(--c-text-2); font-size: 12px; }
.body small { color: var(--c-text-3); font-size: 11px; display: flex; gap: 4px; align-items: center; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
