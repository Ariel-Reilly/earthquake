<script setup>
/**
 * 数据源配置（独立页面 /dashboard/data-sources）。
 *
 * 遥感卫星、气象、水文、地震等多源数据接入管理 —— 启停 / 测试 / 认证。
 */
import { computed, onMounted, ref } from "vue"
import {
  CheckCircle2,
  Database,
  KeyRound,
  Loader2,
  Plug,
  PowerOff,
  RefreshCw,
  Search,
  Settings as SettingsIcon,
  XCircle,
} from "lucide-vue-next"
import { dashboardApi } from "../../services/dashboardApi"
import { getAuthUser, hasPermission } from "../../stores/auth"

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])

const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" }

const loading = ref(false)
const testing = ref(null)
const toggling = ref(null)
const sources = ref([])
const filter = ref({ keyword: "", category: "" })
const editDialog = ref(false)
const editing = ref(null)

const categories = [
  { id: "", label: "全部" },
  { id: "气象", label: "气象" },
  { id: "水文", label: "水文" },
  { id: "地震", label: "地震" },
  { id: "卫星", label: "卫星" },
  { id: "暴露度", label: "暴露度" },
]

const filtered = computed(() => {
  let list = sources.value ?? []
  if (filter.value.category) list = list.filter((s) => s.category === filter.value.category)
  if (filter.value.keyword.trim()) {
    const k = filter.value.keyword.trim().toLowerCase()
    list = list.filter(
      (s) => (s.label ?? "").toLowerCase().includes(k) ||
             (s.code ?? "").toLowerCase().includes(k),
    )
  }
  return list
})

const stats = computed(() => {
  const list = sources.value ?? []
  return {
    total: list.length,
    enabled: list.filter((s) => s.enabled).length,
    healthy: list.filter((s) => s.enabled && s.healthy !== false).length,
    unhealthy: list.filter((s) => s.enabled && s.healthy === false).length,
  }
})

async function loadSources() {
  loading.value = true
  try {
    const data = await dashboardApi.getDataSources?.()
    sources.value = (data ?? []).map((s) => ({ ...s, category: s.category ?? guessCategory(s.code) }))
  } catch (e) {
    emit("notify", e.message ?? "加载数据源失败")
  } finally {
    loading.value = false
  }
}

function guessCategory(code) {
  const map = {
    OPEN_METEO: "气象", NASA_GPM: "气象", NSMC: "气象",
    USGS: "地震",
    COPERNICUS: "卫星",
    OSM: "暴露度",
  }
  return map[code] ?? "通用"
}

async function toggle(code, enabled) {
  toggling.value = code
  try {
    await dashboardApi.toggleDataSource?.(code, enabled)
    const target = sources.value.find((s) => s.code === code)
    if (target) target.enabled = enabled
    emit("notify", `${enabled ? "已启用" : "已禁用"} ${code}`)
  } catch (e) {
    emit("notify", e.message ?? "操作失败")
  } finally {
    toggling.value = null
  }
}

async function testConn(code) {
  testing.value = code
  try {
    const r = await dashboardApi.testDataSource?.(code)
    const target = sources.value.find((s) => s.code === code)
    if (target) {
      target.healthy = true
      target.lastSync = "刚刚"
    }
    emit("notify", `${code} 连接成功：${r?.message ?? "OK"}`)
  } catch (e) {
    const target = sources.value.find((s) => s.code === code)
    if (target) target.healthy = false
    emit("notify", `${code} 连接失败：${e.message ?? "timeout"}`)
  } finally {
    testing.value = null
  }
}

function openEdit(s) {
  editing.value = { ...s }
  editDialog.value = true
}

async function saveEdit() {
  if (!editing.value) return
  const target = sources.value.find((s) => s.code === editing.value.code)
  if (target) Object.assign(target, editing.value)
  emit("notify", `已保存 ${editing.value.code} 配置`)
  editDialog.value = false
}

const has = (perm) => hasPermission(operator.role, perm)

onMounted(() => loadSources())
</script>

<template>
  <div class="data-sources-shell">
    <header class="page-header">
      <div>
        <h2><Database :size="18" />数据源配置</h2>
        <p>遥感卫星、气象、水文、地震等多源数据接入管理 —— 启停 / 测试 / 认证。</p>
      </div>
      <div class="actions">
        <button class="op-btn" @click="loadSources">
          <RefreshCw :size="14" />刷新
        </button>
        <button class="op-btn"><Plug :size="14" />新增数据源</button>
      </div>
    </header>

    <section class="stat-grid">
      <div class="stat-card"><strong>{{ stats.total }}</strong><span>数据源总数</span></div>
      <div class="stat-card"><strong style="color:#2a8154">{{ stats.enabled }}</strong><span>已启用</span></div>
      <div class="stat-card"><strong style="color:#2f7f80">{{ stats.healthy }}</strong><span>连接正常</span></div>
      <div class="stat-card"><strong style="color:#c7553b">{{ stats.unhealthy }}</strong><span>连接异常</span></div>
    </section>

    <section class="filter-bar">
      <Search :size="14" />
      <input v-model="filter.keyword" placeholder="搜索数据源" class="filter-input" />
      <div class="cat-tabs">
        <button v-for="c in categories" :key="c.id" :class="{ active: filter.category === c.id }" @click="filter.category = c.id">
          {{ c.label }}
        </button>
      </div>
    </section>

    <div v-if="loading" class="loading-row">
      <Loader2 :size="16" class="spin" />加载数据源…
    </div>
    <table v-else class="src-table">
      <thead>
        <tr>
          <th>数据源</th>
          <th>类别</th>
          <th>状态</th>
          <th>最近同步</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in filtered" :key="s.code">
          <td>
            <strong>{{ s.label }}</strong>
            <code>{{ s.code }}</code>
          </td>
          <td><span class="cat-tag">{{ s.category }}</span></td>
          <td>
            <span class="status" :class="s.enabled ? (s.healthy === false ? 'warn' : 'ok') : 'off'">
              <CheckCircle2 v-if="s.enabled && s.healthy !== false" :size="13" />
              <XCircle v-else-if="s.enabled" :size="13" />
              <PowerOff v-else :size="13" />
              {{ s.enabled ? (s.healthy === false ? "异常" : "正常") : "已禁用" }}
            </span>
          </td>
          <td><small>{{ s.lastSync ?? "—" }}</small></td>
          <td class="ops">
            <button v-if="has('datasource:edit')" class="ghost-btn small" :disabled="testing === s.code" @click="testConn(s.code)">
              <Loader2 v-if="testing === s.code" :size="12" class="spin" />
              <RefreshCw v-else :size="12" />测试
            </button>
            <button v-if="has('datasource:edit')" class="ghost-btn small" @click="openEdit(s)">
              <KeyRound :size="12" />认证
            </button>
            <button v-if="has('datasource:edit')" class="primary-btn small" :disabled="toggling === s.code" @click="toggle(s.code, !s.enabled)">
              <Loader2 v-if="toggling === s.code" :size="12" class="spin" />
              <span v-else>{{ s.enabled ? "禁用" : "启用" }}</span>
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="editDialog && editing" class="modal-mask" @click.self="editDialog = false">
      <div class="modal-card">
        <h3><SettingsIcon :size="16" />{{ editing.label }} 认证配置</h3>
        <label>认证方式
          <select v-model="editing.authType">
            <option value="none">无需认证</option>
            <option value="apikey">API Key</option>
            <option value="oauth">OAuth 2.0</option>
            <option value="basic">Basic Auth</option>
          </select>
        </label>
        <label v-if="editing.authType === 'apikey'">API Key
          <input v-model="editing.apiKey" type="password" placeholder="sk-..." />
        </label>
        <label v-if="editing.authType === 'basic'">用户名
          <input v-model="editing.username" />
        </label>
        <label v-if="editing.authType === 'basic'">密码
          <input v-model="editing.password" type="password" />
        </label>
        <label v-if="editing.authType === 'oauth'">Client ID
          <input v-model="editing.clientId" />
        </label>
        <label v-if="editing.authType === 'oauth'">Client Secret
          <input v-model="editing.clientSecret" type="password" />
        </label>
        <label>同步频率（分钟）
          <input v-model.number="editing.syncInterval" type="number" min="1" max="1440" />
        </label>
        <div class="modal-actions">
          <button class="ghost-btn" @click="editDialog = false">取消</button>
          <button class="primary-btn" @click="saveEdit">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.data-sources-shell { padding: 24px 28px; }
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
.cat-tabs { display: flex; gap: 4px; background: #f4f7f5; padding: 3px; border-radius: 8px; }
.cat-tabs button { padding: 5px 10px; border: 0; background: transparent; border-radius: 5px; cursor: pointer; font-size: 11px; color: #56716e; }
.cat-tabs button.active { background: #fff; color: #1f3e44; box-shadow: 0 1px 3px rgba(0,0,0,.06); }

.loading-row { padding: 32px; text-align: center; color: #7a8d89; }

.src-table { width: 100%; border-collapse: collapse; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; overflow: hidden; }
.src-table th, .src-table td { padding: 12px 14px; text-align: left; font-size: 13px; border-bottom: 1px solid #eef3f1; }
.src-table th { background: #fafdfc; color: #56716e; font-weight: 600; }
.src-table td strong { display: block; color: #1f3e44; }
.src-table td code { color: #7a8d89; font-size: 11px; }
.cat-tag { padding: 2px 8px; background: rgba(47,127,128,.1); color: #2f7f80; border-radius: 4px; font-size: 11px; }

.status { display: inline-flex; gap: 4px; align-items: center; padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 500; }
.status.ok { color: #2a8154; background: rgba(42,129,84,.12); }
.status.warn { color: #c7553b; background: rgba(199,85,59,.12); }
.status.off { color: #7a8d89; background: #f4f7f5; }

.ops { display: flex; gap: 6px; }
.ghost-btn.small, .primary-btn.small { padding: 5px 10px; font-size: 11px; }
.ghost-btn { display: inline-flex; gap: 4px; align-items: center; padding: 6px 10px; border: 1px solid #d9e6e1; background: #fff; border-radius: 6px; color: #37555a; cursor: pointer; font-size: 12px; }
.ghost-btn:hover { background: #f4f7f5; }
.ghost-btn:disabled { opacity: .5; cursor: not-allowed; }
.primary-btn { display: inline-flex; gap: 4px; align-items: center; padding: 6px 10px; border: 0; background: #2f7f80; color: #fff; border-radius: 6px; cursor: pointer; font-size: 12px; }
.primary-btn:disabled { opacity: .5; cursor: not-allowed; }

.modal-mask { position: fixed; inset: 0; background: rgba(15,32,38,.45); display: grid; place-items: center; z-index: 100; }
.modal-card { width: min(440px, 92vw); background: #fff; border-radius: 14px; padding: 22px; }
.modal-card h3 { margin: 0 0 16px; color: #1f3e44; display: flex; gap: 6px; align-items: center; }
.modal-card label { display: block; margin-bottom: 14px; color: #56716e; font-size: 12px; font-weight: 600; }
.modal-card input, .modal-card select { display: block; width: 100%; margin-top: 6px; padding: 8px 10px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 18px; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
