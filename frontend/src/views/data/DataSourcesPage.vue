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
const newDialog = ref(false)
const newForm = ref(null)

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
  try {
    await dashboardApi.updateDataSource(editing.value.code, editing.value)
    emit("notify", `已保存 ${editing.value.code} 配置`)
    editDialog.value = false
    await loadSources()
  } catch (e) {
    emit("notify", `保存失败：${e?.message ?? e}`)
  }
}

function openNewDialog() {
  newForm.value = {
    code: "",
    name: "",
    category: "气象",
    type: "API",
    host: "",
    port: "",
    database: "",
    username: "",
    enabled: true,
  }
  newDialog.value = true
}

async function submitNew() {
  if (!newForm.value) return
  if (!newForm.value.code.trim()) {
    emit("notify", "请填写数据源编码")
    return
  }
  if (!newForm.value.name.trim()) {
    emit("notify", "请填写数据源名称")
    return
  }
  const payload = {
    ...newForm.value,
    code: newForm.value.code.trim(),
    label: newForm.value.name.trim(),
    name: newForm.value.name.trim(),
  }
  try {
    await dashboardApi.createDataSource(payload)
    emit("notify", `已新增数据源 ${payload.code}`)
    newDialog.value = false
    await loadSources()
  } catch (e) {
    emit("notify", `新增数据源失败：${e?.message ?? e}`)
  }
}

const has = (perm) => hasPermission(operator.role, perm)

onMounted(() => loadSources())
</script>

<template>
  <div class="data-sources-shell">
    <header class="page-header">
      <div>
      </div>
      <div class="actions">
        <button class="op-btn" @click="loadSources">
          <RefreshCw :size="14" />刷新
        </button>
        <button class="op-btn" @click="openNewDialog"><Plug :size="14" />新增数据源</button>
      </div>
    </header>

    <section class="stat-grid">
      <div class="stat-card"><strong>{{ stats.total }}</strong><span>数据源总数</span></div>
      <div class="stat-card"><strong class="ds-kpi-on">{{ stats.enabled }}</strong><span>已启用</span></div>
      <div class="stat-card"><strong class="ds-kpi-ok">{{ stats.healthy }}</strong><span>连接正常</span></div>
      <div class="stat-card"><strong class="ds-kpi-bad">{{ stats.unhealthy }}</strong><span>连接异常</span></div>
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

    <div v-if="newDialog && newForm" class="modal-mask" @click.self="newDialog = false">
      <div class="modal-card">
        <h3><Plug :size="16" />新增数据源</h3>
        <label>数据源编码 <span class="ds-req">*</span>
          <input v-model="newForm.code" placeholder="如：CUSTOM_API" />
        </label>
        <label>数据源名称 <span class="ds-req">*</span>
          <input v-model="newForm.name" placeholder="如：自定义监测接口" />
        </label>
        <label>分类
          <select v-model="newForm.category">
            <option value="气象">气象</option>
            <option value="水文">水文</option>
            <option value="地震">地震</option>
            <option value="卫星">卫星</option>
            <option value="地质">地质</option>
            <option value="监测">监测</option>
            <option value="暴露度">暴露度</option>
            <option value="通用">通用</option>
          </select>
        </label>
        <label>类型
          <select v-model="newForm.type">
            <option value="API">API</option>
            <option value="数据库">数据库</option>
            <option value="文件">文件</option>
            <option value="流式">流式</option>
          </select>
        </label>
        <label>主机地址
          <input v-model="newForm.host" placeholder="如：api.example.com" />
        </label>
        <label>端口
          <input v-model.number="newForm.port" type="number" placeholder="如：443" />
        </label>
        <label>数据库 / 路径
          <input v-model="newForm.database" placeholder="数据库名或接口路径" />
        </label>
        <label>用户名
          <input v-model="newForm.username" placeholder="登录用户名" />
        </label>
        <label class="checkbox-label">
          <input v-model="newForm.enabled" type="checkbox" />
          启用该数据源
        </label>
        <div class="modal-actions">
          <button class="ghost-btn" @click="newDialog = false">取消</button>
          <button class="primary-btn" @click="submitNew">确认新增</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.data-sources-shell { padding: 24px 28px; }
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
.cat-tabs { display: flex; gap: 4px; background: var(--c-bg); padding: 3px; border-radius: var(--r-sm); }
.cat-tabs button { padding: 5px 10px; border: 0; background: transparent; border-radius: var(--r-sm); cursor: pointer; font-size: 11px; color: var(--c-brand-deep); }
.cat-tabs button.active { background: var(--c-surface); color: var(--c-text); box-shadow: 0 1px 3px rgba(0,0,0,.06); }

.loading-row { padding: 32px; text-align: center; color: var(--c-text-2); }

.src-table { width: 100%; border-collapse: collapse; background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); overflow: hidden; }
.src-table th, .src-table td { padding: 12px 14px; text-align: left; font-size: 13px; border-bottom: 1px solid var(--c-border-2); }
.src-table th { background: var(--c-surface-2); color: var(--c-brand-deep); font-weight: 600; }
.src-table td strong { display: block; color: var(--c-text); }
.src-table td code { color: var(--c-text-2); font-size: 11px; }
.cat-tag { padding: 2px 8px; background: var(--c-brand-soft); color: var(--c-brand); border-radius: var(--r-xs); font-size: 11px; }

.status { display: inline-flex; gap: 4px; align-items: center; padding: 2px 8px; border-radius: var(--r-xs); font-size: 12px; font-weight: 500; }
.status.ok { color: var(--c-brand-deep); background: var(--c-brand-soft); }
.status.warn { color: var(--c-danger-strong); background: var(--c-danger-bg); }
.status.off { color: var(--c-text-2); background: var(--c-bg); }

.ops { display: flex; gap: 6px; }
.ghost-btn.small, .primary-btn.small { padding: 5px 10px; font-size: 11px; }
.ghost-btn { display: inline-flex; gap: 4px; align-items: center; padding: 6px 10px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-sm); color: var(--c-text); cursor: pointer; font-size: 12px; }
.ghost-btn:hover { background: var(--c-bg); }
.ghost-btn:disabled { opacity: .5; cursor: not-allowed; }
.primary-btn { display: inline-flex; gap: 4px; align-items: center; padding: 6px 10px; border: 0; background: var(--c-brand); color: var(--c-on-brand); border-radius: var(--r-sm); cursor: pointer; font-size: 12px; }
.primary-btn:disabled { opacity: .5; cursor: not-allowed; }

.modal-mask { position: fixed; inset: 0; background: var(--c-overlay); display: grid; place-items: center; z-index: 100; }
.modal-card { width: min(440px, 92vw); background: var(--c-surface); border-radius: var(--r-lg); padding: 22px; }
.modal-card h3 { margin: 0 0 16px; color: var(--c-text); display: flex; gap: 6px; align-items: center; }
.modal-card label { display: block; margin-bottom: 14px; color: var(--c-brand-deep); font-size: 12px; font-weight: 600; }
.modal-card input, .modal-card select { display: block; width: 100%; margin-top: 6px; padding: 8px 10px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); background: var(--c-surface-2); }
.modal-card .checkbox-label { display: flex; align-items: center; gap: 8px; font-weight: 500; }
.modal-card .checkbox-label input { display: inline-block; width: auto; margin-top: 0; }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 18px; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
/* 设计令牌收尾：模板静态色内联样式 → 类（2026-10-09） */
.ds-kpi-on { color: var(--c-brand-deep); }
.ds-kpi-ok { color: var(--c-brand); }
.ds-kpi-bad { color: var(--c-danger-strong); }
.ds-req { color: var(--c-danger-strong); }
</style>
