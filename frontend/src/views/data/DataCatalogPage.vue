<script setup>
/**
 * 数据资产目录（/dashboard/data-catalog，权限 data:catalog:view）。
 * KPI 概览 + 资源列表 / 使用排行 双 Tab，含详情抽屉、血缘 SVG、趋势 sparkline、申请权限与新增资源弹窗。
 * 接口走 dashboardApi（requireBackend），离线降级空态。
 */
import { computed, onMounted, ref, watch } from "vue"
import {
  ArrowRight, BarChart3, ChevronRight, Database, Edit, Eye, Filter,
  Plus, RefreshCw, Search, Tag, Trash2,
} from "lucide-vue-next"
import { dashboardApi } from "../../services/dashboardApi"

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])

const TYPE_OPTIONS = [
  { value: "", label: "全部类型" },
  { value: "表", label: "表" },
  { value: "视图", label: "视图" },
  { value: "指标", label: "指标" },
  { value: "文件", label: "文件" },
  { value: "API", label: "API" },
]
const CATEGORY_OPTIONS = [
  { value: "", label: "全部分类" },
  { value: "监测", label: "监测" },
  { value: "业务", label: "业务" },
  { value: "元数据", label: "元数据" },
]
const SOURCE_OPTIONS = [
  { value: "", label: "全部来源" },
  { value: "TDengine", label: "TDengine" },
  { value: "PostgreSQL", label: "PostgreSQL" },
  { value: "MinIO", label: "MinIO" },
  { value: "EMQX", label: "EMQX" },
  { value: "AI Service", label: "AI Service" },
  { value: "外部 API", label: "外部 API" },
]
const TYPE_BADGE = {
  表: { c: "#4f8f99", bg: "rgba(79,143,153,.12)" },
  视图: { c: "#6dbfa5", bg: "rgba(109,191,165,.12)" },
  指标: { c: "#e0a050", bg: "rgba(224,160,80,.14)" },
  文件: { c: "#7a8d89", bg: "rgba(122,141,137,.12)" },
  API: { c: "#b88a00", bg: "rgba(217,192,64,.16)" },
}
const CAT_BADGE = {
  监测: { c: "#4f8f99", bg: "rgba(79,143,153,.12)" },
  业务: { c: "#b06d22", bg: "rgba(224,160,80,.14)" },
  元数据: { c: "#7a8d89", bg: "rgba(122,141,137,.12)" },
}

// 状态
const refreshing = ref(false)
const kpiLoading = ref(true)
const kpi = ref({ total: 0, tables: 0, fields: 0, lastUpdated: "—" })
const activeTab = ref("assets")
const filters = ref({ keyword: "", type: "", category: "", source: "" })

const assets = ref([])
const assetsLoading = ref(false)
const usageList = ref([])
const usageLoading = ref(false)

const detailDrawerOpen = ref(false)
const detailLoading = ref(false)
const detailData = ref(null)

const trendDrawerOpen = ref(false)
const trendLoading = ref(false)
const trendData = ref(null)

const requestDialogOpen = ref(false)
const requestForm = ref({ scenario: "", duration: "30", reason: "" })
const requestSubmitting = ref(false)
const requestTarget = ref(null)

const newDialogOpen = ref(false)
const newSubmitting = ref(false)
const newForm = ref({ name: "", type: "表", category: "监测", source: "TDengine", description: "", tags: "" })
const newTagDraft = ref("")

const editTagsDrawerOpen = ref(false)
const editTagsLoading = ref(false)
const editTagsTarget = ref(null)
const editTagsDraft = ref([])
const editTagsNew = ref("")

const subscribeLoading = ref(false)

// 计算属性
const filteredAssets = computed(() => {
  const kw = (filters.value.keyword || "").trim().toLowerCase()
  return (assets.value || []).filter((a) => {
    if (filters.value.type && a.type !== filters.value.type) return false
    if (filters.value.category && a.category !== filters.value.category) return false
    if (filters.value.source && a.source !== filters.value.source) return false
    if (kw) {
      const blob = `${a.name ?? ""} ${a.description ?? ""} ${(a.tags ?? []).join(" ")}`.toLowerCase()
      if (!blob.includes(kw)) return false
    }
    return true
  })
})
const filteredUsage = computed(() => {
  const kw = (filters.value.keyword || "").trim().toLowerCase()
  return (usageList.value || []).filter((u) => {
    if (filters.value.type && u.type !== filters.value.type) return false
    if (filters.value.category && u.category !== filters.value.category) return false
    if (filters.value.source && u.source !== filters.value.source) return false
    if (kw) {
      const blob = `${u.name ?? ""} ${u.caller ?? ""}`.toLowerCase()
      if (!blob.includes(kw)) return false
    }
    return true
  })
})
const sourceOptions = computed(() => {
  const set = new Set(SOURCE_OPTIONS.map((s) => s.value).filter(Boolean))
  for (const a of assets.value || []) if (a.source) set.add(a.source)
  return [{ value: "", label: "全部来源" }, ...Array.from(set).map((v) => ({ value: v, label: v }))]
})
const newTags = computed(() =>
  (newForm.value.tags || "").split(/[,，;；\s]+/).map((t) => t.trim()).filter(Boolean),
)

watch(() => props.searchTerm, (v) => {
  if (typeof v === "string") filters.value.keyword = v
})

// 加载
async function loadSummary() {
  kpiLoading.value = true
  try {
    const data = await dashboardApi.getCatalogSummary()
    kpi.value = {
      total: data?.total ?? 0,
      tables: data?.tables ?? 0,
      fields: data?.fields ?? 0,
      lastUpdated: data?.lastUpdated ?? "—",
    }
  } catch {
    kpi.value = { total: 0, tables: 0, fields: 0, lastUpdated: "—" }
  } finally {
    kpiLoading.value = false
  }
}
async function loadAssets() {
  assetsLoading.value = true
  try {
    const data = await dashboardApi.listDataAssets({
      type: filters.value.type || undefined,
      category: filters.value.category || undefined,
      source: filters.value.source || undefined,
      keyword: filters.value.keyword || undefined,
    })
    const list = Array.isArray(data?.records) ? data.records : Array.isArray(data) ? data : []
    assets.value = list.map((a) => ({
      ...a,
      tags: Array.isArray(a.tags) ? a.tags : [],
      lineageUp: Array.isArray(a.lineageUp) ? a.lineageUp : [],
      lineageDown: Array.isArray(a.lineageDown) ? a.lineageDown : [],
      fields: Array.isArray(a.fields) ? a.fields : [],
    }))
  } catch { assets.value = [] } finally { assetsLoading.value = false }
}
async function loadUsage() {
  usageLoading.value = true
  try {
    const data = await dashboardApi.listCatalogUsage()
    const list = Array.isArray(data?.records) ? data.records : Array.isArray(data) ? data : []
    usageList.value = list.map((u) => ({ ...u, trend7d: Array.isArray(u.trend7d) ? u.trend7d : [] }))
  } catch { usageList.value = [] } finally { usageLoading.value = false }
}
async function refreshAll() {
  refreshing.value = true
  try {
    await Promise.all([loadSummary(), loadAssets(), loadUsage()])
    emit("notify", "目录数据已刷新")
  } finally { refreshing.value = false }
}

// 详情
async function openDetail(asset) {
  detailDrawerOpen.value = true
  detailLoading.value = true
  detailData.value = { ...asset }
  try {
    const data = await dashboardApi.getDataAssetDetail(asset.id)
    detailData.value = {
      ...asset,
      ...data,
      tags: Array.isArray(data?.tags) ? data.tags : asset.tags ?? [],
      fields: Array.isArray(data?.fields) ? data.fields : asset.fields ?? [],
      lineageUp: Array.isArray(data?.lineageUp) ? data.lineageUp : asset.lineageUp ?? [],
      lineageDown: Array.isArray(data?.lineageDown) ? data.lineageDown : asset.lineageDown ?? [],
      metadata: data?.metadata ?? {
        creator: asset.creator ?? "—",
        createdAt: asset.createdAt ?? "—",
        recent7dQueries: asset.recent7dQueries ?? 0,
      },
    }
  } catch (e) {
    detailData.value = {
      ...asset,
      metadata: {
        creator: asset.creator ?? "—",
        createdAt: asset.createdAt ?? "—",
        recent7dQueries: asset.recent7dQueries ?? 0,
      },
    }
    emit("notify", e?.message ?? "加载详情失败，已显示本地数据")
  } finally { detailLoading.value = false }
}
function closeDetail() { detailDrawerOpen.value = false; detailData.value = null }

// 申请权限
function openRequestDialog(asset) {
  requestTarget.value = asset
  requestForm.value = { scenario: "", duration: "30", reason: "" }
  requestDialogOpen.value = true
}
async function submitRequest() {
  if (!requestTarget.value) return
  if (!requestForm.value.scenario.trim() || !requestForm.value.reason.trim()) {
    emit("notify", "请填写使用场景与用途说明")
    return
  }
  requestSubmitting.value = true
  try {
    await dashboardApi.requestDataAssetAccess(requestTarget.value.id, {
      scenario: requestForm.value.scenario.trim(),
      duration: requestForm.value.duration,
      reason: requestForm.value.reason.trim(),
    })
    emit("notify", `已提交《${requestTarget.value.name}》权限申请`)
    requestDialogOpen.value = false
  } catch (e) {
    emit("notify", e?.message ?? "提交申请失败")
  } finally { requestSubmitting.value = false }
}

// 编辑标签
function openEditTags(asset) {
  editTagsTarget.value = asset
  editTagsDraft.value = [...(asset.tags ?? [])]
  editTagsNew.value = ""
  editTagsDrawerOpen.value = true
}
function pushEditTag() {
  const t = (editTagsNew.value || "").trim()
  if (!t || editTagsDraft.value.includes(t)) { editTagsNew.value = ""; return }
  editTagsDraft.value = [...editTagsDraft.value, t]
  editTagsNew.value = ""
}
function removeEditTag(t) { editTagsDraft.value = editTagsDraft.value.filter((x) => x !== t) }
async function submitEditTags() {
  if (!editTagsTarget.value) return
  editTagsLoading.value = true
  try {
    await dashboardApi.updateDataAssetTags(editTagsTarget.value.id, { tags: editTagsDraft.value })
    const target = assets.value.find((a) => a.id === editTagsTarget.value.id)
    if (target) target.tags = [...editTagsDraft.value]
    emit("notify", `已更新《${editTagsTarget.value.name}》标签`)
    editTagsDrawerOpen.value = false
  } catch (e) {
    emit("notify", e?.message ?? "保存标签失败")
  } finally { editTagsLoading.value = false }
}

// 趋势
async function openTrend(item) {
  trendDrawerOpen.value = true
  trendLoading.value = true
  trendData.value = { ...item }
  try {
    const data = await dashboardApi.listCatalogUsage()
    const list = Array.isArray(data?.records) ? data.records : Array.isArray(data) ? data : []
    const hit = list.find((u) => u.id === item.id)
    if (hit) trendData.value = { ...item, ...hit, trend7d: Array.isArray(hit.trend7d) ? hit.trend7d : item.trend7d ?? [] }
  } catch (e) {
    emit("notify", e?.message ?? "加载趋势失败")
  } finally { trendLoading.value = false }
}
function closeTrend() { trendDrawerOpen.value = false; trendData.value = null }
async function subscribeChange(item) {
  subscribeLoading.value = true
  try {
    await dashboardApi.subscribeAssetChange(item.id)
    emit("notify", `已订阅《${item.name}》变更通知`)
  } catch (e) {
    emit("notify", e?.message ?? "订阅失败")
  } finally { subscribeLoading.value = false }
}

// 新增资源
function openNewDialog() {
  newForm.value = { name: "", type: "表", category: "监测", source: "TDengine", description: "", tags: "" }
  newTagDraft.value = ""
  newDialogOpen.value = true
}
function addNewTag() {
  const t = (newTagDraft.value || "").trim()
  if (!t) return
  const set = new Set(newTags.value)
  set.add(t)
  newForm.value.tags = Array.from(set).join("，")
  newTagDraft.value = ""
}
function removeNewTag(t) {
  const set = new Set(newTags.value)
  set.delete(t)
  newForm.value.tags = Array.from(set).join("，")
}
async function submitNew() {
  if (!newForm.value.name.trim()) { emit("notify", "请填写资源名称"); return }
  newSubmitting.value = true
  try {
    // 后端暂无 POST /catalog/assets，本地登记（kpi 同步自增）
    const local = {
      id: `local-${Date.now()}`,
      name: newForm.value.name.trim(),
      type: newForm.value.type,
      category: newForm.value.category,
      source: newForm.value.source,
      description: newForm.value.description,
      fieldCount: 0, storage: "—", frequency: "—",
      tags: newTags.value, lineageUp: [], lineageDown: [], fields: [],
      creator: "当前用户",
      createdAt: new Date().toISOString().slice(0, 10),
      recent7dQueries: 0,
    }
    assets.value = [local, ...assets.value]
    kpi.value.total += 1
    emit("notify", `已新增资源《${local.name}》（本地登记，待后端持久化）`)
    newDialogOpen.value = false
  } catch (e) {
    emit("notify", e?.message ?? "新增失败")
  } finally { newSubmitting.value = false }
}

// 辅助
function resetFilters() { filters.value = { keyword: "", type: "", category: "", source: "" } }
function fmtNumber(n) {
  if (n === null || n === undefined || n === "") return "—"
  if (typeof n !== "number") return n
  if (Math.abs(n) >= 1e8) return `${(n / 1e8).toFixed(1)}亿`
  if (Math.abs(n) >= 1e4) return `${(n / 1e4).toFixed(1)}万`
  return String(n)
}
function maxOfTrend(arr) {
  if (!Array.isArray(arr) || !arr.length) return 1
  return Math.max(1, ...arr)
}
function trimName(n, max = 10) {
  if (!n) return ""
  return n.length > max ? n.slice(0, max) + "…" : n
}

onMounted(async () => {
  if (props.searchTerm) filters.value.keyword = props.searchTerm
  await Promise.all([loadSummary(), loadAssets(), loadUsage()])
})
</script>

<template>
  <div class="data-catalog-shell">
    <header class="page-header">
      <div>
      </div>
      <div class="actions">
        <button class="op-btn" :disabled="refreshing" @click="refreshAll">
          <RefreshCw :size="14" :class="{ spin: refreshing }" />刷新
        </button>
        <button class="primary-btn" @click="openNewDialog">
          <Plus :size="14" />新增资源
        </button>
      </div>
    </header>

    <!-- KPI -->
    <section class="stat-grid">
      <div class="stat-card">
        <strong>{{ kpiLoading ? "—" : fmtNumber(kpi.total) }}</strong>
        <span>资源总数</span>
        <div class="bar"><i :style="{ width: '92%', background: '#4f8f99' }"></i></div>
      </div>
      <div class="stat-card">
        <strong>{{ kpiLoading ? "—" : fmtNumber(kpi.tables) }}</strong>
        <span>表数量</span>
        <div class="bar"><i :style="{ width: '74%', background: '#6dbfa5' }"></i></div>
      </div>
      <div class="stat-card">
        <strong>{{ kpiLoading ? "—" : fmtNumber(kpi.fields) }}</strong>
        <span>总字段数</span>
        <div class="bar"><i :style="{ width: '81%', background: '#e0a050' }"></i></div>
      </div>
      <div class="stat-card">
        <strong>{{ kpiLoading ? "—" : kpi.lastUpdated }}</strong>
        <span>上次更新时间</span>
        <div class="bar"><i :style="{ width: '63%', background: '#d9c040' }"></i></div>
      </div>
    </section>

    <!-- 筛选 -->
    <section class="panel filter-panel">
      <div class="filter-bar">
        <Filter :size="14" class="muted" />
        <div class="search-wrap">
          <Search :size="13" class="muted" />
          <input v-model="filters.keyword" class="filter-input" placeholder="搜索资源名 / 标签 / 描述" />
        </div>
        <select v-model="filters.type" class="filter-select">
          <option v-for="o in TYPE_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
        <select v-model="filters.category" class="filter-select">
          <option v-for="o in CATEGORY_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
        <select v-model="filters.source" class="filter-select">
          <option v-for="o in sourceOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
        <button class="ghost-btn small" @click="resetFilters">
          <Trash2 :size="12" />重置
        </button>
      </div>
    </section>

    <!-- Tabs -->
    <section class="panel tabs-panel">
      <div class="tabs">
        <button :class="['tab-btn', { active: activeTab === 'assets' }]" @click="activeTab = 'assets'">
          <Database :size="14" />资源列表<span class="count">{{ filteredAssets.length }}</span>
        </button>
        <button :class="['tab-btn', { active: activeTab === 'usage' }]" @click="activeTab = 'usage'">
          <BarChart3 :size="14" />使用排行<span class="count">{{ filteredUsage.length }}</span>
        </button>
      </div>

      <!-- Tab 1 资源列表 -->
      <div v-if="activeTab === 'assets'" class="tab-body">
        <div v-if="assetsLoading" class="loading-row">加载中…</div>
        <div v-else-if="!filteredAssets.length" class="empty-row">暂无符合条件的资源</div>
        <div v-else class="table-scroll">
          <table class="catalog-table">
            <thead>
              <tr>
                <th>资源名</th><th>类型</th><th>分类</th><th>字段数</th>
                <th>存储量</th><th>更新频率</th><th>来源系统</th><th class="ops-col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="a in filteredAssets" :key="a.id">
                <td>
                  <strong>{{ a.name }}</strong>
                  <p class="desc">{{ a.description || "—" }}</p>
                </td>
                <td>
                  <span class="badge" :style="{
                    color: TYPE_BADGE[a.type]?.c ?? '#7a8d89',
                    background: TYPE_BADGE[a.type]?.bg ?? 'rgba(122,141,137,.12)',
                  }">{{ a.type }}</span>
                </td>
                <td>
                  <span class="badge" :style="{
                    color: CAT_BADGE[a.category]?.c ?? '#7a8d89',
                    background: CAT_BADGE[a.category]?.bg ?? 'rgba(122,141,137,.12)',
                  }">{{ a.category }}</span>
                </td>
                <td>{{ fmtNumber(a.fieldCount ?? 0) }}</td>
                <td>{{ a.storage ?? "—" }}</td>
                <td>{{ a.frequency ?? "—" }}</td>
                <td>{{ a.source ?? "—" }}</td>
                <td class="ops">
                  <button class="ghost-btn small" @click="openDetail(a)"><Eye :size="12" />查看</button>
                  <button class="ghost-btn small" @click="openEditTags(a)"><Edit :size="12" />编辑标签</button>
                  <button class="primary-btn small" @click="openRequestDialog(a)">
                    <ChevronRight :size="12" />申请权限
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Tab 2 使用排行 -->
      <div v-if="activeTab === 'usage'" class="tab-body">
        <div v-if="usageLoading" class="loading-row">加载中…</div>
        <div v-else-if="!filteredUsage.length" class="empty-row">暂无使用记录</div>
        <div v-else class="table-scroll">
          <table class="catalog-table">
            <thead>
              <tr>
                <th>资源名</th><th>7 天查询次数</th><th>30 天查询次数</th>
                <th>最近访问</th><th>调用人</th><th class="ops-col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="u in filteredUsage" :key="u.id">
                <td>
                  <strong>{{ u.name }}</strong>
                  <p class="desc">{{ u.type ?? "—" }} · {{ u.category ?? "—" }}</p>
                </td>
                <td>
                  <strong>{{ fmtNumber(u.queries7d ?? 0) }}</strong>
                  <span class="mini-bars">
                    <span v-for="(v, idx) in (u.trend7d || []).slice(0, 7)" :key="idx" class="mini-bar"
                      :style="{
                        height: `${Math.max(6, (v / maxOfTrend(u.trend7d)) * 22)}px`,
                        background: idx === (u.trend7d || []).length - 1 ? '#4f8f99' : '#6dbfa5',
                      }"
                    ></span>
                  </span>
                </td>
                <td>{{ fmtNumber(u.queries30d ?? 0) }}</td>
                <td>{{ u.lastAccess ?? "—" }}</td>
                <td>{{ u.caller ?? "—" }}</td>
                <td class="ops">
                  <button class="ghost-btn small" @click="openTrend(u)">
                    <BarChart3 :size="12" />查看趋势
                  </button>
                  <button class="primary-btn small" :disabled="subscribeLoading" @click="subscribeChange(u)">
                    <ChevronRight :size="12" />订阅变更
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <!-- 详情抽屉 -->
    <transition name="drawer-fade">
      <div v-if="detailDrawerOpen" class="drawer-mask" @click.self="closeDetail">
        <aside class="drawer-card detail-drawer">
          <header class="drawer-head">
            <div>
              <h3><Database :size="16" />{{ detailData?.name ?? "资源详情" }}</h3>
              <p v-if="detailData">{{ detailData.type ?? "—" }} · {{ detailData.category ?? "—" }} · {{ detailData.source ?? "—" }}</p>
            </div>
            <button class="ghost-btn small" @click="closeDetail">关闭</button>
          </header>
          <div v-if="detailLoading" class="loading-row">加载中…</div>
          <div v-else-if="detailData" class="drawer-body">
            <!-- 标签 -->
            <section class="block">
              <h4><Tag :size="14" />标签</h4>
              <div class="chip-row">
                <span v-for="t in (detailData.tags ?? [])" :key="t" class="chip">{{ t }}</span>
                <span v-if="!(detailData.tags ?? []).length" class="muted small">暂无标签</span>
              </div>
            </section>
            <!-- 字段 -->
            <section class="block">
              <h4>字段列表（{{ (detailData.fields ?? []).length }}）</h4>
              <div v-if="!(detailData.fields ?? []).length" class="muted small">暂无字段定义</div>
              <table v-else class="mini-table">
                <thead><tr><th>名称</th><th>类型</th><th>描述</th><th>主键</th><th>脱敏</th></tr></thead>
                <tbody>
                  <tr v-for="(f, idx) in detailData.fields" :key="idx">
                    <td><code>{{ f.name }}</code></td>
                    <td>{{ f.type ?? "—" }}</td>
                    <td>{{ f.description ?? "—" }}</td>
                    <td><span :class="['pk-tag', f.primary ? 'on' : 'off']">{{ f.primary ? "是" : "否" }}</span></td>
                    <td><span :class="['pk-tag', f.sensitive ? 'sens' : 'safe']">{{ f.sensitive ? "脱敏" : "公开" }}</span></td>
                  </tr>
                </tbody>
              </table>
            </section>
            <!-- 血缘 -->
            <section class="block">
              <h4><ArrowRight :size="14" />血缘上下游</h4>
              <div class="lineage">
                <svg viewBox="0 0 600 200" class="lineage-svg" preserveAspectRatio="xMidYMid meet">
                  <text x="20" y="14" font-size="9" fill="#7a8d89">上游</text>
                  <text x="20" y="196" font-size="9" fill="#7a8d89">下游</text>
                  <g v-for="(n, i) in (detailData.lineageUp || []).slice(0, 3)" :key="`up-${i}`">
                    <rect :x="20 + i * 95" y="22" width="80" height="40" rx="8"
                      :fill="(CAT_BADGE[n.category] || CAT_BADGE['元数据']).bg"
                      :stroke="(CAT_BADGE[n.category] || CAT_BADGE['元数据']).c" stroke-width="1.2" />
                    <text :x="60 + i * 95" y="40" text-anchor="middle" font-size="10" fill="#24494d">{{ trimName(n.name, 8) }}</text>
                    <text :x="60 + i * 95" y="54" text-anchor="middle" font-size="9" fill="#7a8d89">{{ n.type ?? "—" }}</text>
                    <path :d="`M${60 + i * 95}62 L${60 + i * 95}95 L300 115`"
                      :stroke="(CAT_BADGE[n.category] || CAT_BADGE['元数据']).c" stroke-width="1.2" fill="none" stroke-dasharray="3 3" />
                  </g>
                  <rect x="240" y="100" width="120" height="50" rx="10" fill="#2f7f80" stroke="#246b6c" stroke-width="1.4" />
                  <text x="300" y="123" text-anchor="middle" font-size="11" fill="#fff" font-weight="600">{{ trimName(detailData.name, 10) }}</text>
                  <text x="300" y="138" text-anchor="middle" font-size="9" fill="rgba(255,255,255,.85)">当前资源</text>
                  <g v-for="(n, i) in (detailData.lineageDown || []).slice(0, 3)" :key="`down-${i}`">
                    <path :d="`M360 125 L${540 - i * 95}120 L${540 - i * 95}88`"
                      :stroke="(CAT_BADGE[n.category] || CAT_BADGE['业务']).c" stroke-width="1.2" fill="none" />
                    <rect :x="500 - i * 95" y="155" width="80" height="40" rx="8"
                      :fill="(CAT_BADGE[n.category] || CAT_BADGE['业务']).bg"
                      :stroke="(CAT_BADGE[n.category] || CAT_BADGE['业务']).c" stroke-width="1.2" />
                    <text :x="540 - i * 95" y="173" text-anchor="middle" font-size="10" fill="#24494d">{{ trimName(n.name, 8) }}</text>
                    <text :x="540 - i * 95" y="187" text-anchor="middle" font-size="9" fill="#7a8d89">{{ n.type ?? "—" }}</text>
                  </g>
                </svg>
                <div v-if="!(detailData.lineageUp || []).length && !(detailData.lineageDown || []).length" class="muted small">暂无血缘信息</div>
              </div>
            </section>
            <!-- 元数据 -->
            <section class="block">
              <h4>元数据</h4>
              <div class="meta-grid">
                <div><span class="meta-key">创建人</span><strong>{{ detailData.metadata?.creator ?? "—" }}</strong></div>
                <div><span class="meta-key">创建时间</span><strong>{{ detailData.metadata?.createdAt ?? "—" }}</strong></div>
                <div><span class="meta-key">最近 7 天查询</span><strong>{{ fmtNumber(detailData.metadata?.recent7dQueries ?? 0) }} 次</strong></div>
              </div>
            </section>
          </div>
        </aside>
      </div>
    </transition>

    <!-- 趋势抽屉 -->
    <transition name="drawer-fade">
      <div v-if="trendDrawerOpen" class="drawer-mask" @click.self="closeTrend">
        <aside class="drawer-card trend-drawer">
          <header class="drawer-head">
            <div>
              <h3><BarChart3 :size="16" />{{ trendData?.name ?? "使用趋势" }}</h3>
              <p v-if="trendData">{{ trendData.type ?? "—" }} · {{ trendData.category ?? "—" }}</p>
            </div>
            <button class="ghost-btn small" @click="closeTrend">关闭</button>
          </header>
          <div v-if="trendLoading" class="loading-row">加载中…</div>
          <div v-else-if="trendData" class="drawer-body">
            <section class="block">
              <h4>7 天查询趋势</h4>
              <div class="sparkline">
                <div v-for="(v, idx) in (trendData.trend7d || [])" :key="idx" class="spark-bar" :style="{
                  height: `${Math.max(8, (v / maxOfTrend(trendData.trend7d)) * 160)}px`,
                  background: idx === trendData.trend7d.length - 1 ? '#4f8f99' : '#6dbfa5',
                }"><span class="spark-tip">{{ v }}</span></div>
                <div v-if="!(trendData.trend7d || []).length" class="muted small">无趋势数据</div>
              </div>
              <div v-if="(trendData.trend7d || []).length" class="spark-axis">
                <span v-for="(v, idx) in (trendData.trend7d || [])" :key="idx">D{{ idx + 1 }}</span>
              </div>
            </section>
            <section class="block">
              <h4>汇总</h4>
              <div class="meta-grid">
                <div><span class="meta-key">7 天查询</span><strong>{{ fmtNumber(trendData.queries7d ?? 0) }}</strong></div>
                <div><span class="meta-key">30 天查询</span><strong>{{ fmtNumber(trendData.queries30d ?? 0) }}</strong></div>
                <div><span class="meta-key">最近访问</span><strong>{{ trendData.lastAccess ?? "—" }}</strong></div>
                <div><span class="meta-key">调用人</span><strong>{{ trendData.caller ?? "—" }}</strong></div>
              </div>
            </section>
          </div>
        </aside>
      </div>
    </transition>

    <!-- 申请权限弹窗 -->
    <transition name="drawer-fade">
      <div v-if="requestDialogOpen" class="drawer-mask" @click.self="requestDialogOpen = false">
        <div class="modal-card small-modal">
          <h3><ChevronRight :size="16" />申请资源权限</h3>
          <p v-if="requestTarget" class="muted small">目标资源：<strong>{{ requestTarget.name }}</strong></p>
          <label>使用场景
            <input v-model="requestForm.scenario" placeholder="例：日常监测看板 / 模型训练 / 离线分析" />
          </label>
          <label>申请时长
            <select v-model="requestForm.duration">
              <option value="7">7 天</option><option value="30">30 天</option>
              <option value="90">90 天</option><option value="365">1 年</option>
              <option value="permanent">长期</option>
            </select>
          </label>
          <label>用途说明
            <textarea v-model="requestForm.reason" rows="3" placeholder="详细描述用途与数据使用范围"></textarea>
          </label>
          <div class="modal-actions">
            <button class="ghost-btn" @click="requestDialogOpen = false">取消</button>
            <button class="primary-btn" :disabled="requestSubmitting" @click="submitRequest">
              <span v-if="requestSubmitting">提交中…</span><span v-else>提交申请</span>
            </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 新增资源弹窗 -->
    <transition name="drawer-fade">
      <div v-if="newDialogOpen" class="drawer-mask" @click.self="newDialogOpen = false">
        <div class="modal-card">
          <h3><Plus :size="16" />新增数据资源</h3>
          <label>资源名称
            <input v-model="newForm.name" placeholder="例：监测点实时指标表" />
          </label>
          <div class="row-2">
            <label>类型
              <select v-model="newForm.type">
                <option v-for="o in TYPE_OPTIONS.filter((x) => x.value)" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </label>
            <label>分类
              <select v-model="newForm.category">
                <option v-for="o in CATEGORY_OPTIONS.filter((x) => x.value)" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </label>
          </div>
          <label>来源系统
            <select v-model="newForm.source">
              <option v-for="o in SOURCE_OPTIONS.filter((x) => x.value)" :key="o.value" :value="o.value">{{ o.label }}</option>
            </select>
          </label>
          <label>描述
            <textarea v-model="newForm.description" rows="3" placeholder="资源用途、存储位置、负责人等"></textarea>
          </label>
          <label>标签
            <div class="tag-input">
              <span v-for="t in newTags" :key="t" class="chip editable">{{ t }}<button class="chip-x" @click="removeNewTag(t)">×</button></span>
              <input v-model="newTagDraft" class="tag-field" placeholder="输入后回车添加" @keydown.enter.prevent="addNewTag" />
            </div>
          </label>
          <div class="modal-actions">
            <button class="ghost-btn" @click="newDialogOpen = false">取消</button>
            <button class="primary-btn" :disabled="newSubmitting" @click="submitNew">
              <span v-if="newSubmitting">提交中…</span><span v-else>保存资源</span>
            </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 编辑标签抽屉 -->
    <transition name="drawer-fade">
      <div v-if="editTagsDrawerOpen" class="drawer-mask" @click.self="editTagsDrawerOpen = false">
        <aside class="drawer-card tag-drawer">
          <header class="drawer-head">
            <div>
              <h3><Tag :size="16" />编辑标签 · {{ editTagsTarget?.name ?? "" }}</h3>
              <p>用于检索与权限分组，最多 20 个。</p>
            </div>
            <button class="ghost-btn small" @click="editTagsDrawerOpen = false">关闭</button>
          </header>
          <div class="drawer-body">
            <div class="tag-input big">
              <span v-for="t in editTagsDraft" :key="t" class="chip editable">{{ t }}<button class="chip-x" @click="removeEditTag(t)">×</button></span>
              <input v-model="editTagsNew" class="tag-field" placeholder="输入后回车添加" @keydown.enter.prevent="pushEditTag" />
            </div>
            <div class="muted small">当前 {{ editTagsDraft.length }} / 20</div>
            <div class="modal-actions">
              <button class="ghost-btn" @click="editTagsDrawerOpen = false">取消</button>
              <button class="primary-btn" :disabled="editTagsLoading" @click="submitEditTags">
                <span v-if="editTagsLoading">保存中…</span><span v-else>保存</span>
              </button>
            </div>
          </div>
        </aside>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.data-catalog-shell { padding: 24px 28px; display: flex; flex-direction: column; gap: 18px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: #1f3e44; }
.page-header p { margin: 4px 0 0; color: #7a8d89; font-size: 12px; }
.actions { display: flex; gap: 8px; }

.op-btn, .ghost-btn, .primary-btn {
  display: inline-flex; gap: 6px; align-items: center;
  padding: 7px 13px; border-radius: 8px; font-size: 12px; cursor: pointer;
  transition: background .18s ease, box-shadow .18s ease;
}
.op-btn, .ghost-btn { border: 1px solid #d9e6e1; background: #fff; color: #37555a; }
.op-btn:hover, .ghost-btn:hover { background: #f4f7f5; }
.primary-btn { border: 0; background: #2f7f80; color: #fff; box-shadow: 0 4px 10px rgba(47,127,128,.18); }
.primary-btn:hover { background: #246b6c; }
.primary-btn:disabled, .ghost-btn:disabled, .op-btn:disabled { opacity: .55; cursor: not-allowed; }
.small { padding: 5px 10px; font-size: 11px; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.stat-card { padding: 18px 18px 14px; background: #fff; border: 1px solid #e9eee9; border-radius: 12px; }
.stat-card strong { display: block; font-size: 24px; color: #1f3e44; }
.stat-card span { color: #7a8d89; font-size: 12px; margin-top: 4px; display: block; }
.stat-card .bar { display: block; height: 5px; background: #e7f0ed; border-radius: 3px; margin-top: 14px; overflow: hidden; }
.stat-card .bar i { display: block; height: 100%; border-radius: inherit; }

.filter-panel { padding: 12px 14px; }
.filter-bar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.search-wrap { flex: 1; min-width: 200px; display: flex; gap: 6px; align-items: center; padding: 6px 10px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; }
.filter-input { flex: 1; border: 0; background: transparent; outline: 0; color: #24494d; font-size: 12px; }
.filter-select { padding: 7px 10px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; color: #37555a; font-size: 12px; outline: 0; min-width: 110px; }
.muted { color: #7a8d89; }

.tabs-panel { padding: 0; }
.tabs { display: flex; gap: 4px; padding: 12px 14px 0; border-bottom: 1px solid #edf2f0; }
.tab-btn { display: inline-flex; gap: 6px; align-items: center; padding: 8px 14px; border: 0; background: transparent; cursor: pointer; color: #56716e; font-size: 13px; font-weight: 500; border-radius: 8px 8px 0 0; border-bottom: 2px solid transparent; }
.tab-btn.active { color: #1f3e44; border-bottom-color: #2f7f80; background: rgba(47,127,128,.06); }
.tab-btn .count { display: inline-block; min-width: 22px; padding: 1px 6px; border-radius: 9px; background: rgba(47,127,128,.12); color: #2f7f80; font-size: 10px; text-align: center; }
.tab-body { padding: 14px; }

.table-scroll { overflow-x: auto; }
.catalog-table { width: 100%; min-width: 880px; border-collapse: collapse; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; overflow: hidden; }
.catalog-table th, .catalog-table td { padding: 12px 14px; text-align: left; font-size: 12.5px; border-bottom: 1px solid #eef3f1; vertical-align: top; }
.catalog-table th { background: #fafdfc; color: #56716e; font-weight: 600; position: sticky; top: 0; z-index: 1; }
.catalog-table td strong { color: #1f3e44; }
.catalog-table .desc { margin: 4px 0 0; color: #7a8d89; font-size: 11px; max-width: 280px; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 500; white-space: nowrap; }
.ops { display: flex; flex-wrap: wrap; gap: 6px; }
.ops-col { min-width: 220px; }
.mini-bars { display: inline-flex; gap: 2px; align-items: flex-end; height: 24px; margin-left: 8px; vertical-align: middle; }
.mini-bar { display: block; width: 4px; border-radius: 1px; }
.loading-row, .empty-row { padding: 36px 14px; text-align: center; color: #7a8d89; font-size: 12px; background: #fff; border: 1px dashed #dbe4e0; border-radius: 10px; }

.drawer-mask { position: fixed; inset: 0; background: rgba(15,32,38,.45); display: flex; justify-content: flex-end; z-index: 100; }
.drawer-card { width: min(560px, 96vw); height: 100vh; background: #fafdfc; display: flex; flex-direction: column; box-shadow: -8px 0 24px rgba(0,0,0,.08); }
.drawer-head { display: flex; justify-content: space-between; align-items: flex-start; padding: 18px 22px; border-bottom: 1px solid #edf2f0; }
.drawer-head h3 { margin: 0; color: #1f3e44; font-size: 16px; display: flex; gap: 8px; align-items: center; }
.drawer-head p { margin: 4px 0 0; color: #7a8d89; font-size: 12px; }
.drawer-body { padding: 18px 22px; overflow-y: auto; flex: 1; }
.block { margin-bottom: 22px; }
.block h4 { margin: 0 0 10px; color: #24494d; font-size: 13px; display: flex; gap: 6px; align-items: center; font-weight: 600; }

.chip-row { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { display: inline-flex; align-items: center; gap: 4px; padding: 3px 10px; background: rgba(79,143,153,.10); color: #4f8f99; border-radius: 999px; font-size: 11px; }
.chip.editable { background: rgba(224,160,80,.14); color: #b06d22; }
.chip-x { border: 0; background: transparent; cursor: pointer; color: inherit; padding: 0 0 0 2px; line-height: 1; }

.mini-table { width: 100%; border-collapse: collapse; background: #fff; border: 1px solid #e9eee9; border-radius: 8px; overflow: hidden; }
.mini-table th, .mini-table td { padding: 8px 10px; border-bottom: 1px solid #eef3f1; font-size: 11.5px; text-align: left; }
.mini-table th { background: #fafdfc; color: #56716e; font-weight: 600; }
.mini-table td code { color: #24494d; background: #f4f7f5; padding: 1px 5px; border-radius: 3px; font-size: 11px; }
.pk-tag { padding: 1px 6px; border-radius: 3px; font-size: 10px; font-weight: 500; }
.pk-tag.on { color: #2a8154; background: rgba(42,129,84,.12); }
.pk-tag.off { color: #7a8d89; background: rgba(122,141,137,.10); }
.pk-tag.sens { color: #c7553b; background: rgba(199,85,59,.12); }
.pk-tag.safe { color: #4f8f99; background: rgba(79,143,153,.12); }

.lineage { background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 14px; }
.lineage-svg { width: 100%; height: 200px; display: block; }

.meta-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.meta-grid > div { padding: 10px 12px; background: #fff; border: 1px solid #e9eee9; border-radius: 8px; }
.meta-key { display: block; color: #7a8d89; font-size: 11px; margin-bottom: 4px; }

.sparkline { display: flex; align-items: flex-end; gap: 6px; height: 180px; padding: 14px; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; }
.spark-bar { flex: 1; min-width: 14px; border-radius: 4px 4px 0 0; position: relative; transition: height .25s ease; }
.spark-tip { position: absolute; top: -18px; left: 50%; transform: translateX(-50%); font-size: 10px; color: #56716e; white-space: nowrap; }
.spark-axis { display: flex; gap: 6px; margin-top: 6px; padding: 0 14px; }
.spark-axis span { flex: 1; text-align: center; font-size: 10px; color: #7a8d89; }

.small-modal { width: min(440px, 92vw); }
.modal-card { align-self: center; margin: auto; width: min(520px, 92vw); background: #fff; border-radius: 14px; padding: 22px; }
.modal-card h3 { margin: 0 0 14px; color: #1f3e44; font-size: 16px; display: flex; gap: 6px; align-items: center; }
.modal-card label { display: block; margin-bottom: 12px; color: #56716e; font-size: 11px; font-weight: 600; }
.modal-card input, .modal-card select, .modal-card textarea { display: block; width: 100%; margin-top: 6px; padding: 8px 10px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; color: #24494d; font-size: 12px; outline: 0; }
.modal-card textarea { resize: vertical; font-family: inherit; }
.row-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 14px; }

.tag-input { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; padding: 8px; margin-top: 6px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; }
.tag-input.big { padding: 12px; }
.tag-field { flex: 1; min-width: 110px; border: 0; background: transparent; outline: 0; color: #24494d; font-size: 12px; padding: 4px; }
.tag-drawer { width: min(440px, 96vw); }

.drawer-fade-enter-active, .drawer-fade-leave-active { transition: opacity .22s ease; }
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }
.drawer-fade-enter-from .drawer-card, .drawer-fade-leave-to .drawer-card { transform: translateX(40px); }
.drawer-fade-enter-from .modal-card, .drawer-fade-leave-to .modal-card { transform: translateY(20px); }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

@media (max-width: 1024px) { .stat-grid { grid-template-columns: repeat(2, 1fr); } .meta-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 720px) {
  .data-catalog-shell { padding: 16px; }
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .filter-bar { flex-wrap: wrap; }
  .filter-select { flex: 1 1 45%; min-width: 0; }
  .search-wrap { flex-basis: 100%; }
  .row-2, .meta-grid { grid-template-columns: 1fr; }
  .ops { flex-direction: column; align-items: stretch; }
  .tabs { overflow-x: auto; }
  .page-header { flex-direction: column; gap: 10px; }
}
</style>
