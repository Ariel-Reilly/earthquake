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
    list = list.filter((h) => (h.region ?? h.location ?? "").toLowerCase().includes(k))
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
    map.set(h.type ?? "其他", (map.get(h.type ?? "其他") ?? 0) + 1)
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
      <div class="stat-card"><strong style="color:#c7553b">{{ stats.critical }}</strong><span>高风险历史事件</span></div>
      <div class="stat-card"><strong style="color:#d7a546">{{ stats.watch }}</strong><span>关注事件</span></div>
      <div class="stat-card"><strong style="color:#2a8154">{{ stats.closed }}</strong><span>已闭环</span></div>
    </section>

    <section class="filter-bar">
      <Filter :size="14" />
      <input v-model="filter.keyword" placeholder="搜索编号 / 名称" class="filter-input" />
      <select v-model="filter.type">
        <option value="">全部灾种</option>
        <option v-for="t in ['滑坡','崩塌','泥石流','地面塌陷','地震','洪水','火灾']" :key="t">{{ t }}</option>
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
      <input v-model="filter.region" placeholder="行政区" class="filter-input small" />
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
              <p>{{ h.type }} · {{ h.status }}</p>
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
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: #1f3e44; }
.page-header p { margin: 4px 0 0; color: #7a8d89; font-size: 12px; }
.op-btn { display: flex; gap: 6px; align-items: center; padding: 7px 12px; border: 1px solid #d9e6e1; background: #fff; border-radius: 8px; color: #37555a; font-size: 12px; cursor: pointer; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.stat-card { padding: 14px 16px; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; }
.stat-card strong { display: block; font-size: 22px; color: #1f3e44; }
.stat-card span { color: #7a8d89; font-size: 12px; }

.filter-bar { display: flex; gap: 10px; align-items: center; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 12px 16px; margin-bottom: 14px; }
.filter-bar .filter-input { flex: 1; padding: 7px 12px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; }
.filter-bar .filter-input.small { flex: 0 0 140px; }
.filter-bar select { padding: 7px 12px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fff; }
.ghost-btn.small { padding: 6px 12px; font-size: 12px; }
.ghost-btn { padding: 7px 12px; border: 1px solid #d9e6e1; background: #fff; border-radius: 8px; color: #37555a; cursor: pointer; font-size: 12px; }

.content-grid { display: grid; grid-template-columns: 280px 1fr; gap: 14px; }
.distribution-panel, .list-panel { background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 14px 16px; }
.distribution-panel h3 { margin: 0 0 12px; display: flex; gap: 6px; align-items: center; font-size: 13px; color: #56716e; }
.type-bar { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.type-bar li { display: flex; gap: 8px; align-items: center; }
.type-bar .type-name { width: 60px; font-size: 12px; color: #56716e; }
.type-bar .track { flex: 1; height: 8px; background: #f4f7f5; border-radius: 4px; overflow: hidden; }
.type-bar .fill { height: 100%; background: #2f7f80; border-radius: 4px; }
.type-bar strong { font-size: 12px; color: #1f3e44; min-width: 30px; text-align: right; }

.loading-row, .empty-row { padding: 32px; text-align: center; color: #7a8d89; font-size: 13px; }

.hazard-list { list-style: none; margin: 0; padding: 0; max-height: 600px; overflow-y: auto; }
.hazard-list li { display: flex; gap: 12px; padding: 12px 14px; border-bottom: 1px solid #eef3f1; cursor: pointer; transition: background .12s; }
.hazard-list li:hover { background: #f4f7f5; }
.level-dot { flex: 0 0 32px; width: 32px; height: 32px; border-radius: 50%; color: #fff; display: grid; place-items: center; font-weight: 700; }
.level-dot.orange { background: #c7553b; }
.level-dot.yellow { background: #d7a546; }
.level-dot.blue   { background: #5db486; }
.body { flex: 1; min-width: 0; }
.title { display: flex; gap: 8px; align-items: center; }
.title strong { color: #1f3e44; font-size: 13px; }
.title code { color: #7a8d89; font-size: 11px; }
.level-tag { padding: 1px 6px; border-radius: 3px; font-size: 10px; }
.level-tag.orange { background: rgba(199,85,59,.12); color: #c7553b; }
.level-tag.yellow { background: rgba(215,165,70,.18); color: #9b761e; }
.level-tag.blue { background: rgba(93,180,134,.15); color: #2a8154; }
.body p { margin: 2px 0; color: #7a8d89; font-size: 12px; }
.body small { color: #9aacab; font-size: 11px; display: flex; gap: 4px; align-items: center; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
