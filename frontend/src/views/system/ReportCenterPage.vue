<script setup>
/**
 * 报表中心：模板库 + 生成历史 + 订阅配置。
 * 依赖 dashboardApi：listReportTemplates / createReportTemplate / deleteReportTemplate /
 *                   runReportTemplate / updateReportSubscription / listReportHistory /
 *                   rerunReportHistory / cancelReportHistory。
 * 后端未启用时由 requireBackend / USE_BACKEND 拦截，UI 上层以「暂无数据」占位。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import ConfirmDialog from '../../components/common/ConfirmDialog.vue'
import {
  FileBarChart,
  FileText,
  Play,
  Trash2,
  Edit,
  Copy,
  MailPlus,
  Download,
  RefreshCw,
  Plus,
  X,
  Check,
  AlertTriangle,
  Clock4,
  Layers,
  Users,
  AlertCircle,
  CalendarClock,
  Search,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])
const confirmDialogRef = ref(null)

const CATEGORY_LABEL = { operation: '运营', supervision: '监管', custom: '自定义' }
const CATEGORY_COLOR = { operation: '#87b096', supervision: '#6f9e7f', custom: '#cbaa76' }
const TRIGGER_LABEL = { manual: '手动', scheduled: '定时', event: '事件' }
const TRIGGER_COLOR = { manual: '#6f9e7f', scheduled: '#87b096', event: '#cbaa76' }
const STATUS_META = {
  pending: { label: '排队中', color: '#d9c040', bg: '#fbf6e1' },
  running: { label: '执行中', color: '#6f9e7f', bg: '#e6f1f2' },
  success: { label: '成功', color: '#87b096', bg: '#e6f4ee' },
  failed: { label: '失败', color: '#e06060', bg: '#fdf1ee' },
}
const FIELD_LIBRARY = [
  { key: 'kpi', label: 'KPI 指标' },
  { key: 'trend', label: '趋势曲线' },
  { key: 'table', label: '数据表格' },
  { key: 'map', label: '地图分布' },
  { key: 'pie', label: '饼图占比' },
]
const FIELD_LABEL = Object.fromEntries(FIELD_LIBRARY.map((f) => [f.key, f.label]))
const PALETTE_OPTIONS = [
  { value: 'teal', label: '青绿', color: '#87b096' },
  { value: 'amber', label: '暖橙', color: '#cbaa76' },
  { value: 'rose', label: '玫红', color: '#e06060' },
  { value: 'mist', label: '冷雾', color: '#6f9e7f' },
]
const TAB_KEYS = [
  { value: 'templates', label: '报表模板库' },
  { value: 'history', label: '生成历史' },
]

const activeTab = ref('templates')
const loading = ref(false)
const historyLoading = ref(false)
const lastUpdated = ref(null)
const templates = ref([])
const history = ref([])
const kpi = reactive({ total: 0, month: 0, subscribers: 0, failed: 0 })
const filters = reactive({ category: 'all', keyword: '' })
const historyFilters = reactive({ status: 'all', trigger: 'all' })

// 运行弹窗
const runModalOpen = ref(false)
const runForm = reactive({ range: '7d', format: 'pdf', recipients: '当前用户' })
const runTarget = ref(null)
const submitting = ref(false)

// 订阅抽屉
const subDrawerOpen = ref(false)
const subForm = reactive({
  templateId: null,
  cron: 'weekly',
  time: '09:00',
  recipients: '',
  enabled: true,
})

// 新建模板弹窗
const createModalOpen = ref(false)
const createForm = reactive({
  name: '',
  category: 'operation',
  description: '',
  fields: [],
  palette: 'teal',
})
const createError = ref('')

const filteredTemplates = computed(() => {
  const kw = filters.keyword.trim().toLowerCase()
  return templates.value.filter((tpl) => {
    if (filters.category !== 'all' && tpl.category !== filters.category) return false
    if (kw && !`${tpl.name ?? ''}`.toLowerCase().includes(kw)) return false
    return true
  })
})

const filteredHistory = computed(() => {
  return history.value.filter((row) => {
    if (historyFilters.status !== 'all' && row.status !== historyFilters.status) return false
    if (historyFilters.trigger !== 'all' && row.trigger !== historyFilters.trigger) return false
    return true
  })
})

const KPI_LIST = computed(() => [
  { key: 'total', label: '模板总数', value: kpi.total, suffix: '份', color: '#6f9e7f', desc: '运营 / 监管 / 自定义', icon: Layers },
  { key: 'month', label: '本月生成', value: kpi.month, suffix: '次', color: '#87b096', desc: '本自然月累计', icon: FileBarChart },
  { key: 'subscribers', label: '订阅人数', value: kpi.subscribers, suffix: '人', color: '#cbaa76', desc: '累计订阅者', icon: Users },
  { key: 'failed', label: '失败次数', value: kpi.failed, suffix: '次', color: '#e06060', desc: '近 30 天累计失败', icon: AlertCircle },
])

function categoryLabel(value) { return CATEGORY_LABEL[value] ?? value ?? '—' }
function categoryColor(value) { return CATEGORY_COLOR[value] ?? '#76847a' }
function triggerLabel(value) { return TRIGGER_LABEL[value] ?? value ?? '—' }
function statusMeta(value) { return STATUS_META[value] ?? { label: value ?? '—', color: '#76847a', bg: '#eaf1ec' } }
function fieldLabel(key) { return FIELD_LABEL[key] ?? key ?? '—' }
function triggerColor(value) { return TRIGGER_COLOR[value] ?? '#76847a' }
function formatBytes(size) {
  if (size === null || size === undefined) return '—'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(2)} MB`
  return `${(size / 1024 / 1024 / 1024).toFixed(2)} GB`
}
function formatDuration(ms) {
  if (ms === null || ms === undefined) return '—'
  if (ms < 1000) return `${ms} 毫秒`
  const sec = Math.floor(ms / 1000)
  if (sec < 60) return `${sec} 秒`
  const min = Math.floor(sec / 60)
  return `${min} 分 ${sec % 60} 秒`
}
function formatDateTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

async function loadTemplates(showToast = false) {
  loading.value = true
  try {
    const data = await dashboardApi.listReportTemplates({
      category: filters.category !== 'all' ? filters.category : undefined,
      keyword: filters.keyword || undefined,
    })
    const records = Array.isArray(data?.records) ? data.records : []
    templates.value = records.map((item, idx) => ({
      id: item.id ?? `tpl-${idx + 1}`,
      name: item.name ?? '未命名模板',
      category: item.category ?? 'custom',
      description: item.description ?? '',
      fields: Array.isArray(item.fields) ? item.fields : [],
      fieldCount: item.fieldCount ?? (Array.isArray(item.fields) ? item.fields.length : 0),
      subscribers: item.subscribers ?? 0,
      lastRunAt: item.lastRunAt ?? item.updatedAt ?? null,
      lastStatus: item.lastStatus ?? null,
    }))
    kpi.total = templates.value.length
    kpi.subscribers = templates.value.reduce((s, t) => s + (t.subscribers || 0), 0)
    if (showToast) emit('notify', '模板列表已刷新')
  } catch (err) {
    templates.value = []
    kpi.total = 0
    kpi.subscribers = 0
    if (showToast) emit('notify', err?.message ?? '模板加载失败')
  } finally {
    loading.value = false
    lastUpdated.value = new Date()
  }
}

async function loadHistory(showToast = false) {
  historyLoading.value = true
  try {
    const data = await dashboardApi.listReportHistory({
      status: historyFilters.status !== 'all' ? historyFilters.status : undefined,
      trigger: historyFilters.trigger !== 'all' ? historyFilters.trigger : undefined,
    })
    const records = Array.isArray(data?.records) ? data.records : []
    history.value = records.map((item, idx) => ({
      id: item.id ?? `h-${idx + 1}`,
      name: item.name ?? item.templateName ?? '未命名报表',
      templateId: item.templateId ?? null,
      trigger: item.trigger ?? 'manual',
      triggerBy: item.triggerBy ?? item.operator ?? '—',
      triggeredAt: item.triggeredAt ?? item.createdAt ?? new Date().toISOString(),
      durationMs: item.durationMs ?? item.duration ?? null,
      size: item.size ?? 0,
      status: item.status ?? 'pending',
      errorMessage: item.errorMessage ?? '',
    }))
    const now = new Date()
    kpi.month = history.value.filter((row) => {
      const dt = new Date(row.triggeredAt)
      return dt.getMonth() === now.getMonth() && dt.getFullYear() === now.getFullYear()
    }).length
    kpi.failed = history.value.filter((row) => row.status === 'failed').length
    if (showToast) emit('notify', '生成历史已刷新')
  } catch (err) {
    history.value = []
    kpi.month = 0
    kpi.failed = 0
    if (showToast) emit('notify', err?.message ?? '历史加载失败')
  } finally {
    historyLoading.value = false
    lastUpdated.value = new Date()
  }
}

async function refresh() {
  await Promise.all([loadTemplates(), loadHistory()])
}

// 模板行操作
function openRunModal(item) {
  runTarget.value = item
  runForm.range = '7d'
  runForm.format = 'pdf'
  runForm.recipients = '当前用户'
  runModalOpen.value = true
}
function cancelRun() { runModalOpen.value = false; runTarget.value = null }

async function submitRun() {
  if (!runTarget.value) return
  if (!runForm.recipients.trim()) { emit('notify', '请填写接收人'); return }
  submitting.value = true
  try {
    await dashboardApi.runReportTemplate(runTarget.value.id, {
      range: runForm.range,
      format: runForm.format,
      recipients: runForm.recipients.split(/[,，;；\s]+/).filter(Boolean),
    })
    emit('notify', `「${runTarget.value.name}」已加入生成队列`)
    runModalOpen.value = false
    runTarget.value = null
    await loadHistory(true)
  } catch (err) {
    emit('notify', err?.message ?? '运行失败')
  } finally {
    submitting.value = false
  }
}

function openSubDrawer(item) {
  subForm.templateId = item.id
  subForm.cron = 'weekly'
  subForm.time = '09:00'
  subForm.recipients = item.lastRecipients ?? ''
  subForm.enabled = true
  subDrawerOpen.value = true
}
function closeSubDrawer() { subDrawerOpen.value = false }

async function submitSubscription() {
  if (!subForm.templateId) return
  if (!subForm.recipients.trim()) { emit('notify', '请填写订阅接收人'); return }
  try {
    await dashboardApi.updateReportSubscription({
      templateId: subForm.templateId,
      cron: subForm.cron,
      time: subForm.time,
      recipients: subForm.recipients.split(/[,，;；\s]+/).filter(Boolean),
      enabled: subForm.enabled,
    })
    emit('notify', '订阅已保存')
    subDrawerOpen.value = false
  } catch (err) {
    emit('notify', err?.message ?? '订阅保存失败')
  }
}

function editTemplate(item) { emit('notify', `已加载模板「${item.name}」进入编辑器（演示）`) }
function duplicateTemplate(item) { emit('notify', `已复制模板「${item.name}」`) }

async function deleteTemplate(item) {
  const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: `确认删除模板「${item.name}」？`,
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  try {
    await dashboardApi.deleteReportTemplate(item.id)
    emit('notify', '模板已删除')
    await loadTemplates(true)
  } catch (err) {
    emit('notify', err?.message ?? '删除失败')
  }
}

// 历史行操作
async function rerunHistory(item) {
  try {
    await dashboardApi.rerunReportHistory(item.id)
    emit('notify', `「${item.name}」已重新加入队列`)
    await loadHistory(true)
  } catch (err) { emit('notify', err?.message ?? '重跑失败') }
}

async function cancelHistory(item) {
  if (item.status !== 'pending' && item.status !== 'running') {
    emit('notify', '当前状态不可取消')
    return
  }
  try {
    await dashboardApi.cancelReportHistory(item.id)
    emit('notify', '任务已取消')
    await loadHistory(true)
  } catch (err) { emit('notify', err?.message ?? '取消失败') }
}

function downloadHistory(item) {
  if (item.status !== 'success') { emit('notify', '当前状态不可下载'); return }
  emit('notify', `已开始下载「${item.name}」（演示）`)
}

// 新建模板
function openCreateModal() {
  createForm.name = ''
  createForm.category = 'operation'
  createForm.description = ''
  createForm.fields = []
  createForm.palette = 'teal'
  createError.value = ''
  createModalOpen.value = true
}
function closeCreateModal() { createModalOpen.value = false }
function toggleField(key) {
  const idx = createForm.fields.indexOf(key)
  if (idx >= 0) createForm.fields.splice(idx, 1)
  else createForm.fields.push(key)
}

async function submitCreate() {
  if (!createForm.name.trim()) { createError.value = '请填写模板名称'; return }
  if (!createForm.fields.length) { createError.value = '至少选择一种字段类型'; return }
  try {
    await dashboardApi.createReportTemplate({
      name: createForm.name.trim(),
      category: createForm.category,
      description: createForm.description.trim(),
      fields: [...createForm.fields],
      palette: createForm.palette,
    })
    emit('notify', '模板已保存为草稿')
    createModalOpen.value = false
    await loadTemplates(true)
  } catch (err) { emit('notify', err?.message ?? '保存失败') }
}

// 监听过滤变化
watch(() => ({ category: filters.category, keyword: filters.keyword }), () => loadTemplates(), { deep: true })
watch(() => ({ ...historyFilters }), () => loadHistory(), { deep: true })

onMounted(refresh)
defineExpose({ refresh })
</script>

<template>
  <div class="report-center-page">
    <!-- 顶部工具栏 -->
    <div class="page-toolbar">
      <div class="toolbar-title">
        <FileBarChart :size="18" />
        <div>
          <h2>报表中心</h2>
          <p>模板管理 · 订阅分发 · 生成历史 · 全流程报表工作台</p>
        </div>
      </div>
      <div class="toolbar-actions">
        <div class="search-input">
          <Search :size="13" />
          <input v-model="filters.keyword" type="search" placeholder="搜索模板名称" />
        </div>
        <button class="secondary-btn" :disabled="loading || historyLoading" @click="refresh">
          <RefreshCw :size="13" />刷新
        </button>
        <button class="primary-btn" @click="openCreateModal">
          <Plus :size="13" />新建模板
        </button>
      </div>
    </div>

    <!-- KPI -->
    <div class="stats-grid">
      <div v-for="kpiItem in KPI_LIST" :key="kpiItem.key" class="stat-card" :style="{ '--accent': kpiItem.color }">
        <div class="stat-icon" :style="{ background: kpiItem.color + '22', color: kpiItem.color }">
          <component :is="kpiItem.icon" :size="19" />
        </div>
        <div class="stat-copy">
          <span>{{ kpiItem.label }}</span>
          <strong>{{ kpiItem.value }} <small>{{ kpiItem.suffix }}</small></strong>
          <em class="positive">{{ kpiItem.desc }}</em>
        </div>
      </div>
    </div>

    <!-- Tab 切换 -->
    <div class="tab-strip">
      <button v-for="tab in TAB_KEYS" :key="tab.value" class="tab-btn"
        :class="{ active: activeTab === tab.value }" @click="activeTab = tab.value">
        {{ tab.label }}
      </button>
      <span v-if="lastUpdated" class="tab-meta">最近更新：{{ formatDateTime(lastUpdated) }}</span>
    </div>

    <!-- Tab 1：模板库 -->
    <section v-if="activeTab === 'templates'" class="panel system-panel">
      <div class="panel-header compact">
        <div>
          <h2><Layers :size="15" />模板筛选</h2>
          <p>按分类 / 关键字快速定位模板</p>
        </div>
        <div class="panel-tools">
          <button class="chip" :class="{ active: filters.category === 'all' }" @click="filters.category = 'all'">全部</button>
          <button class="chip" :class="{ active: filters.category === 'operation' }" @click="filters.category = 'operation'">运营</button>
          <button class="chip" :class="{ active: filters.category === 'supervision' }" @click="filters.category = 'supervision'">监管</button>
          <button class="chip" :class="{ active: filters.category === 'custom' }" @click="filters.category = 'custom'">自定义</button>
        </div>
      </div>
      <div v-if="loading" class="loading-block">模板加载中…</div>
      <div v-else-if="!filteredTemplates.length" class="empty-block">
        <FileText :size="22" />
        <p>暂无模板</p>
        <small>点击右上角「新建模板」创建第一份报表模板</small>
      </div>
      <div v-else class="table-wrap">
        <table class="report-table">
          <thead>
            <tr>
              <th>模板名</th>
              <th>分类</th>
              <th>字段数</th>
              <th>订阅人数</th>
              <th>最近生成</th>
              <th class="th-action">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in filteredTemplates" :key="item.id">
              <td>
                <div class="cell-title">
                  <strong>{{ item.name }}</strong>
                  <small v-if="item.description">{{ item.description }}</small>
                </div>
              </td>
              <td>
                <span class="cat-pill"
                  :style="{ color: categoryColor(item.category), borderColor: categoryColor(item.category) + '66', background: categoryColor(item.category) + '11' }">
                  {{ categoryLabel(item.category) }}
                </span>
              </td>
              <td><span class="num-tag">{{ item.fieldCount }}</span></td>
              <td><span class="num-tag alt"><Users :size="11" /> {{ item.subscribers }}</span></td>
              <td>
                <span class="time-text">{{ formatDateTime(item.lastRunAt) }}</span>
                <small v-if="item.lastStatus" class="status-mini" :style="{ color: statusMeta(item.lastStatus).color }">
                  · {{ statusMeta(item.lastStatus).label }}
                </small>
              </td>
              <td class="td-action">
                <button class="primary-btn small" @click="openRunModal(item)"><Play :size="11" />运行</button>
                <button class="secondary-btn small" @click="editTemplate(item)"><Edit :size="11" />编辑</button>
                <button class="secondary-btn small" @click="duplicateTemplate(item)"><Copy :size="11" />复制</button>
                <button class="secondary-btn small" @click="openSubDrawer(item)"><MailPlus :size="11" />订阅</button>
                <button class="secondary-btn small danger" @click="deleteTemplate(item)"><Trash2 :size="11" />删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- Tab 2：生成历史 -->
    <section v-if="activeTab === 'history'" class="panel warning-panel">
      <div class="panel-header compact">
        <div>
          <h2><Clock4 :size="15" />历史筛选</h2>
          <p>查看执行结果、耗时、状态，支持重跑与取消</p>
        </div>
        <div class="panel-tools">
          <select v-model="historyFilters.status" class="mini-select">
            <option value="all">全部状态</option>
            <option value="pending">排队中</option>
            <option value="running">执行中</option>
            <option value="success">成功</option>
            <option value="failed">失败</option>
          </select>
          <select v-model="historyFilters.trigger" class="mini-select">
            <option value="all">全部触发</option>
            <option value="manual">手动</option>
            <option value="scheduled">定时</option>
            <option value="event">事件</option>
          </select>
        </div>
      </div>
      <div v-if="historyLoading" class="loading-block">历史加载中…</div>
      <div v-else-if="!filteredHistory.length" class="empty-block">
        <FileText :size="22" />
        <p>暂无生成记录</p>
        <small>触发模板运行后将在此处展示执行结果</small>
      </div>
      <div v-else class="table-wrap">
        <table class="report-table">
          <thead>
            <tr>
              <th>报表名</th>
              <th>触发人</th>
              <th>触发时间</th>
              <th>耗时</th>
              <th>大小</th>
              <th>状态</th>
              <th class="th-action">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in filteredHistory" :key="row.id">
              <td>
                <div class="cell-title">
                  <strong>{{ row.name }}</strong>
                  <small v-if="row.errorMessage" class="error-line">
                    <AlertTriangle :size="10" /> {{ row.errorMessage }}
                  </small>
                </div>
              </td>
              <td>
                <span class="trigger-pill"
                  :style="{ color: triggerColor(row.trigger), borderColor: triggerColor(row.trigger) + '66', background: triggerColor(row.trigger) + '11' }">
                  <span class="trigger-dot" :style="{ background: triggerColor(row.trigger) }"></span>
                  {{ triggerLabel(row.trigger) }}
                </span>
                <small class="muted"> · {{ row.triggerBy }}</small>
              </td>
              <td><span class="time-text">{{ formatDateTime(row.triggeredAt) }}</span></td>
              <td>{{ formatDuration(row.durationMs) }}</td>
              <td>{{ formatBytes(row.size) }}</td>
              <td>
                <span class="status-badge"
                  :style="{ color: statusMeta(row.status).color, background: statusMeta(row.status).bg, borderColor: statusMeta(row.status).color + '55' }">
                  {{ statusMeta(row.status).label }}
                </span>
              </td>
              <td class="td-action">
                <button class="primary-btn small" :disabled="row.status !== 'success'" @click="downloadHistory(row)">
                  <Download :size="11" />下载
                </button>
                <button class="secondary-btn small" :disabled="row.status === 'running'" @click="rerunHistory(row)">
                  <RefreshCw :size="11" />重跑
                </button>
                <button class="secondary-btn small danger"
                  :disabled="row.status !== 'pending' && row.status !== 'running'" @click="cancelHistory(row)">
                  <X :size="11" />取消
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 运行弹窗 -->
    <transition name="modal">
      <div v-if="runModalOpen" class="modal-mask" @click.self="cancelRun">
        <div class="modal-card">
          <div class="modal-header">
            <h3><Play :size="15" />运行报表</h3>
            <button class="icon-btn" @click="cancelRun">×</button>
          </div>
          <div class="modal-body">
            <p class="modal-target">目标模板：<strong>{{ runTarget?.name ?? '—' }}</strong></p>
            <label>
              <span>时间范围</span>
              <select v-model="runForm.range">
                <option value="today">今天</option>
                <option value="7d">近 7 天</option>
                <option value="30d">近 30 天</option>
                <option value="quarter">本季度</option>
                <option value="year">本年</option>
              </select>
            </label>
            <label>
              <span>输出格式</span>
              <div class="radio-group">
                <label class="radio-pill" :class="{ active: runForm.format === 'pdf' }">
                  <input type="radio" v-model="runForm.format" value="pdf" />PDF
                </label>
                <label class="radio-pill" :class="{ active: runForm.format === 'excel' }">
                  <input type="radio" v-model="runForm.format" value="excel" />Excel
                </label>
                <label class="radio-pill" :class="{ active: runForm.format === 'csv' }">
                  <input type="radio" v-model="runForm.format" value="csv" />CSV
                </label>
              </div>
            </label>
            <label>
              <span>接收人</span>
              <input v-model="runForm.recipients" type="text" placeholder="多个接收人使用逗号或空格分隔" />
            </label>
          </div>
          <div class="modal-footer">
            <button class="secondary-btn" @click="cancelRun">取消</button>
            <button class="primary-btn" :disabled="submitting" @click="submitRun">
              {{ submitting ? '提交中…' : '立即生成' }}
            </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 订阅抽屉 -->
    <transition name="drawer">
      <aside v-if="subDrawerOpen" class="drawer-panel">
        <div class="drawer-header">
          <div>
            <small><MailPlus :size="11" />订阅配置</small>
            <h3>定时推送</h3>
          </div>
          <button class="icon-btn" @click="closeSubDrawer">×</button>
        </div>
        <div class="drawer-body">
          <label class="field-block">
            <span>推送周期</span>
            <div class="chip-group">
              <button class="chip" :class="{ active: subForm.cron === 'daily' }" @click="subForm.cron = 'daily'">每日</button>
              <button class="chip" :class="{ active: subForm.cron === 'weekly' }" @click="subForm.cron = 'weekly'">每周</button>
              <button class="chip" :class="{ active: subForm.cron === 'monthly' }" @click="subForm.cron = 'monthly'">每月</button>
            </div>
          </label>
          <label class="field-block">
            <span>推送时间</span>
            <input v-model="subForm.time" type="time" />
          </label>
          <label class="field-block">
            <span>接收人列表</span>
            <textarea v-model="subForm.recipients" rows="3" placeholder="例如：张三, 李四, 王五"></textarea>
          </label>
          <div class="switch-row">
            <span>启用订阅</span>
            <button class="switch" :class="{ on: subForm.enabled }" @click="subForm.enabled = !subForm.enabled">
              <span class="knob"></span>
            </button>
          </div>
          <div class="drawer-summary">
            <CalendarClock :size="12" />
            <span>{{ subForm.enabled ? '已启用' : '已停用' }} · {{ subForm.cron === 'daily' ? '每日' : subForm.cron === 'weekly' ? '每周' : '每月' }} {{ subForm.time }}</span>
          </div>
        </div>
        <div class="drawer-footer">
          <button class="secondary-btn" @click="closeSubDrawer">取消</button>
          <button class="primary-btn" @click="submitSubscription">
            <Check :size="13" />保存订阅
          </button>
        </div>
      </aside>
    </transition>
    <div v-if="subDrawerOpen" class="drawer-mask" @click="closeSubDrawer"></div>

    <!-- 新建模板弹窗 -->
    <transition name="modal">
      <div v-if="createModalOpen" class="modal-mask" @click.self="closeCreateModal">
        <div class="modal-card wide">
          <div class="modal-header">
            <h3><Plus :size="15" />新建报表模板</h3>
            <button class="icon-btn" @click="closeCreateModal">×</button>
          </div>
          <div class="modal-body">
            <label class="field-block">
              <span>模板名称 <em>*</em></span>
              <input v-model="createForm.name" type="text" placeholder="例如：监测运行周报" />
            </label>
            <label class="field-block">
              <span>分类</span>
              <div class="chip-group">
                <button class="chip" :class="{ active: createForm.category === 'operation' }" @click="createForm.category = 'operation'">运营</button>
                <button class="chip" :class="{ active: createForm.category === 'supervision' }" @click="createForm.category = 'supervision'">监管</button>
                <button class="chip" :class="{ active: createForm.category === 'custom' }" @click="createForm.category = 'custom'">自定义</button>
              </div>
            </label>
            <label class="field-block">
              <span>描述</span>
              <textarea v-model="createForm.description" rows="2" placeholder="可选：补充模板用途 / 数据口径说明"></textarea>
            </label>
            <div class="field-block">
              <span>字段选择 <em>*</em></span>
              <div class="field-grid">
                <button v-for="field in FIELD_LIBRARY" :key="field.key" type="button" class="field-chip"
                  :class="{ active: createForm.fields.includes(field.key) }" @click="toggleField(field.key)">
                  <span class="field-icon">
                    <Check v-if="createForm.fields.includes(field.key)" :size="11" />
                  </span>
                  {{ field.label }}
                </button>
              </div>
              <small class="field-hint">已选 {{ createForm.fields.length }} 项<em v-if="!createForm.fields.length">（至少 1 项）</em></small>
            </div>
            <div class="field-block">
              <span>样式配色</span>
              <div class="palette-grid">
                <label v-for="p in PALETTE_OPTIONS" :key="p.value" class="palette-card"
                  :class="{ active: createForm.palette === p.value }">
                  <input type="radio" v-model="createForm.palette" :value="p.value" />
                  <span class="palette-swatch" :style="{ background: p.color }"></span>
                  <span class="palette-label">
                    <strong>{{ p.label }}</strong>
                    <small>{{ p.color }}</small>
                  </span>
                  <Check v-if="createForm.palette === p.value" :size="13" class="palette-check" />
                </label>
              </div>
            </div>
            <p v-if="createError" class="error-text">
              <AlertTriangle :size="11" /> {{ createError }}
            </p>
          </div>
          <div class="modal-footer">
            <button class="secondary-btn" @click="closeCreateModal">取消</button>
            <button class="primary-btn" @click="submitCreate">
              <Check :size="13" />保存草稿
            </button>
          </div>
        </div>
      </div>
    </transition>
  </div>
    <ConfirmDialog ref="confirmDialogRef" />
</template>

<style scoped>
.report-center-page { display: flex; flex-direction: column; gap: 15px; position: relative; }

/* 工具栏 */
.page-toolbar { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; flex-wrap: wrap; }
.toolbar-title { display: flex; align-items: center; gap: 10px; color: var(--c-text); }
.toolbar-title h2 { margin: 0; font-size: 17px; color: var(--c-brand-deeper); }
.toolbar-title p { margin: 2px 0 0; font-size: 11px; color: var(--c-text-3); }
.toolbar-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.search-input {
  display: inline-flex; align-items: center; gap: 6px;
  border: 1px solid var(--c-separator); background: var(--c-surface); padding: 6px 10px;
  border-radius: var(--r-sm); min-width: 200px;
}
.search-input input { border: none; outline: none; background: transparent; font-size: 12px; color: var(--c-brand-deeper); width: 100%; }
.search-input:focus-within { border-color: var(--c-brand-hover); box-shadow: 0 0 0 3px rgba(109, 191, 165, .15); }

/* Tab */
.tab-strip { display: flex; align-items: center; gap: 4px; border-bottom: 1px solid var(--c-separator); }
.tab-btn {
  background: transparent; border: none; padding: 10px 16px;
  font-size: 13px; color: var(--c-ink-2); cursor: pointer;
  border-bottom: 2px solid transparent; transition: all .2s ease;
}
.tab-btn:hover { color: var(--c-brand-deeper); }
.tab-btn.active { color: var(--c-brand-deeper); font-weight: 600; border-bottom-color: var(--c-brand-hover); }
.tab-meta { margin-left: auto; font-size: 11px; color: var(--c-text-3); padding-right: 8px; }

/* 状态块 */
.loading-block, .empty-block { padding: 32px 20px; text-align: center; color: var(--c-text-3); }
.empty-block { display: flex; flex-direction: column; align-items: center; gap: 4px; }
.empty-block p { margin: 6px 0 0; font-size: 14px; color: var(--c-ink-1); }
.empty-block small { color: var(--c-text-3); font-size: 12px; }

/* 表格 */
.table-wrap { padding: 0 20px 20px; overflow-x: auto; }
.report-table { width: 100%; border-collapse: collapse; font-size: 13px; min-width: 780px; }
.report-table thead th {
  text-align: left; color: var(--c-ink-2); font-weight: 500;
  padding: 10px 8px; border-bottom: 1px solid var(--c-separator); white-space: nowrap;
}
.report-table tbody td { padding: 10px 8px; border-bottom: 1px solid var(--c-brand-mist); color: var(--c-brand-deeper); vertical-align: middle; }
.report-table tbody tr:hover { background: var(--c-brand-mist); }
.cell-title { display: flex; flex-direction: column; gap: 2px; min-width: 180px; }
.cell-title strong { color: var(--c-brand-deeper); font-size: 13px; }
.cell-title small { color: var(--c-text-3); font-size: 11px; }
.cell-title .error-line { color: var(--c-danger); display: inline-flex; align-items: center; gap: 4px; }

.cat-pill { font-size: 11px; padding: 2px 8px; border-radius: var(--r-pill); border: 1px solid; font-weight: 500; }
.num-tag { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-brand-soft); color: var(--c-brand-deeper); }
.num-tag.alt { background: var(--c-warning-bg); color: var(--c-warning); }
.time-text { font-size: 12px; color: var(--c-brand-deeper); }
.status-mini { font-size: 11px; margin-left: 4px; }
.status-badge { display: inline-block; font-size: 11px; padding: 2px 10px; border-radius: var(--r-pill); border: 1px solid; font-weight: 500; }
.trigger-pill { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; padding: 2px 8px; border-radius: var(--r-pill); border: 1px solid; font-weight: 500; }
.trigger-dot { width: 6px; height: 6px; border-radius: 50%; }
.muted { color: var(--c-text-3); font-size: 11px; }

.td-action { display: flex; gap: 4px; white-space: nowrap; flex-wrap: wrap; }
.td-action button.small { padding: 4px 8px; font-size: 12px; border-radius: var(--r-sm); display: inline-flex; align-items: center; gap: 4px; }
.td-action .secondary-btn.danger { color: var(--c-danger); border-color: var(--c-danger-bg); }
.td-action .secondary-btn.danger:hover { background: var(--c-danger-bg); }
.td-action button:disabled { opacity: .5; cursor: not-allowed; }

.mini-select { border: 1px solid var(--c-separator); background: var(--c-surface); color: var(--c-brand-deeper); padding: 6px 10px; border-radius: var(--r-sm); font-size: 12px; }

/* chip（用于筛选与表单 chip） */
.chip-group { display: flex; gap: 6px; flex-wrap: wrap; }
.chip {
  border: 1px solid var(--c-separator); background: var(--c-surface); color: var(--c-ink-1);
  padding: 4px 12px; border-radius: var(--r-pill); font-size: 12px; cursor: pointer;
  transition: all .15s ease;
}
.chip:hover { border-color: var(--c-brand-hover); color: var(--c-brand-deeper); }
.chip.active { background: var(--c-brand-deeper); color: var(--c-on-brand); border-color: var(--c-brand-deeper); }

/* 弹窗 */
.modal-mask { position: fixed; inset: 0; background: var(--c-overlay); display: flex; align-items: center; justify-content: center; z-index: 9999; padding: 20px; }
.modal-card { background: var(--c-surface); border-radius: var(--r-lg); width: 100%; max-width: 440px; box-shadow: 0 20px 60px rgba(0, 0, 0, .18); overflow: hidden; }
.modal-card.wide { max-width: 560px; }
.modal-header { padding: 14px 18px; border-bottom: 1px solid var(--c-brand-soft); display: flex; align-items: center; justify-content: space-between; }
.modal-header h3 { margin: 0; font-size: 15px; color: var(--c-brand-deeper); display: inline-flex; gap: 6px; align-items: center; }
.icon-btn { background: transparent; border: 1px solid transparent; cursor: pointer; font-size: 18px; color: var(--c-ink-2); width: 28px; height: 28px; border-radius: var(--r-sm); }
.icon-btn:hover { background: var(--c-brand-mist); color: var(--c-brand-deeper); }
.modal-body { padding: 16px 18px; display: flex; flex-direction: column; gap: 12px; max-height: 70vh; overflow-y: auto; }
.modal-target { font-size: 13px; color: var(--c-ink-2); margin: 0; }
.modal-body label { display: flex; flex-direction: column; gap: 6px; font-size: 12px; color: var(--c-ink-2); }
.modal-body label em { color: var(--c-danger); font-style: normal; }
.modal-body select,
.modal-body input[type='text'],
.modal-body input[type='time'],
.modal-body textarea {
  border: 1px solid var(--c-separator); border-radius: var(--r-sm); padding: 8px 10px;
  font-size: 13px; color: var(--c-brand-deeper); background: var(--c-border-2); font-family: inherit;
}
.modal-body textarea { resize: vertical; min-height: 60px; }
.modal-body input:focus, .modal-body select:focus, .modal-body textarea:focus {
  outline: none; border-color: var(--c-brand-hover); box-shadow: 0 0 0 3px rgba(109, 191, 165, .15);
}

.radio-group { display: flex; gap: 6px; }
.radio-pill {
  display: inline-flex; align-items: center; gap: 4px;
  border: 1px solid var(--c-separator); background: var(--c-surface);
  padding: 6px 12px; border-radius: var(--r-pill); font-size: 12px;
  color: var(--c-ink-1); cursor: pointer;
}
.radio-pill input { display: none; }
.radio-pill.active { background: var(--c-brand-deeper); color: var(--c-on-brand); border-color: var(--c-brand-deeper); }

.field-block { display: flex; flex-direction: column; gap: 6px; font-size: 12px; color: var(--c-ink-2); }
.field-block input, .field-block textarea {
  border: 1px solid var(--c-separator); border-radius: var(--r-sm); padding: 8px 10px;
  font-size: 13px; color: var(--c-brand-deeper); background: var(--c-border-2); font-family: inherit;
}
.field-block input:focus, .field-block textarea:focus {
  outline: none; border-color: var(--c-brand-hover); box-shadow: 0 0 0 3px rgba(109, 191, 165, .15);
}
.field-hint { font-size: 11px; color: var(--c-text-3); }
.field-hint em { color: var(--c-warning); font-style: normal; }

.field-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.field-chip {
  display: inline-flex; align-items: center; gap: 8px;
  border: 1px solid var(--c-separator); background: var(--c-surface);
  padding: 10px 12px; border-radius: var(--r-sm); cursor: pointer;
  font-size: 13px; color: var(--c-ink-1); text-align: left;
  transition: all .15s ease;
}
.field-chip:hover { border-color: var(--c-brand-hover); }
.field-chip.active { background: var(--c-brand-soft); color: var(--c-brand-deeper); border-color: var(--c-brand-hover); font-weight: 500; }
.field-icon { width: 18px; height: 18px; border-radius: var(--r-xs); background: var(--c-brand-mist); color: var(--c-text-3); display: inline-flex; align-items: center; justify-content: center; }
.field-chip.active .field-icon { background: var(--c-brand-hover); color: var(--c-on-brand); }

.palette-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.palette-card {
  position: relative; display: flex; align-items: center; gap: 10px;
  padding: 12px; border: 1px solid var(--c-separator); border-radius: var(--r-md);
  background: var(--c-surface); cursor: pointer; transition: all .15s ease;
}
.palette-card:hover { border-color: var(--c-brand-hover); }
.palette-card.active { border-color: var(--c-brand-deeper); background: var(--c-brand-mist); }
.palette-card input { display: none; }
.palette-swatch { width: 32px; height: 32px; border-radius: var(--r-sm); box-shadow: 0 4px 10px rgba(0, 0, 0, .12); }
.palette-label { display: flex; flex-direction: column; gap: 2px; }
.palette-label strong { font-size: 13px; color: var(--c-brand-deeper); }
.palette-label small { font-size: 11px; color: var(--c-text-3); }
.palette-check { position: absolute; top: 8px; right: 8px; color: var(--c-brand-hover); }

.error-text { color: var(--c-danger); font-size: 12px; margin: 0; display: inline-flex; align-items: center; gap: 4px; }
.modal-footer { padding: 12px 18px; border-top: 1px solid var(--c-brand-soft); display: flex; justify-content: flex-end; gap: 8px; }
.modal-footer .primary-btn:disabled { opacity: .5; cursor: not-allowed; }

/* 抽屉 */
.drawer-panel {
  position: fixed; top: 0; right: 0; bottom: 0;
  width: 360px; max-width: 90vw;
  background: var(--c-surface); border-left: 1px solid var(--c-separator);
  display: flex; flex-direction: column;
  box-shadow: -8px 0 24px rgba(0, 0, 0, .08); z-index: 99;
}
.drawer-header { padding: 16px 18px; border-bottom: 1px solid var(--c-brand-soft); display: flex; justify-content: space-between; align-items: flex-start; }
.drawer-header h3 { margin: 4px 0 0; font-size: 15px; color: var(--c-brand-deeper); }
.drawer-header small { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--c-ink-2); }
.drawer-body { flex: 1; overflow-y: auto; padding: 16px 18px; display: flex; flex-direction: column; gap: 14px; }

.switch-row { display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; background: var(--c-surface-2); border-radius: var(--r-sm); border: 1px solid var(--c-separator); font-size: 12px; color: var(--c-ink-2); }
.switch { position: relative; width: 38px; height: 22px; border-radius: var(--r-pill); background: var(--c-separator); border: none; cursor: pointer; transition: background .2s ease; padding: 0; }
.switch .knob { position: absolute; top: 2px; left: 2px; width: 18px; height: 18px; border-radius: 50%; background: var(--c-surface); box-shadow: 0 2px 4px rgba(0, 0, 0, .15); transition: transform .2s ease; }
.switch.on { background: var(--c-brand-hover); }
.switch.on .knob { transform: translateX(16px); }

.drawer-summary { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; color: var(--c-ink-2); background: var(--c-brand-soft); padding: 6px 10px; border-radius: var(--r-sm); }
.drawer-footer { padding: 12px 18px; border-top: 1px solid var(--c-brand-soft); display: flex; justify-content: flex-end; gap: 8px; }
.drawer-mask { position: fixed; inset: 0; background: var(--c-overlay); z-index: 98; }

/* 过渡 */
.drawer-enter-active, .drawer-leave-active { transition: transform .25s ease, opacity .25s ease; }
.drawer-enter-from, .drawer-leave-to { transform: translateX(20px); opacity: 0; }
.modal-enter-active, .modal-leave-active { transition: opacity .2s ease; }
.modal-enter-from, .modal-leave-to { opacity: 0; }

/* 响应式 */
@media (max-width: 880px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
  .page-toolbar { flex-direction: column; align-items: stretch; }
  .toolbar-actions { width: 100%; }
  .search-input { flex: 1; min-width: 0; }
  .palette-grid, .field-grid { grid-template-columns: 1fr; }
}
</style>
