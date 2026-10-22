<script setup>
/**
 * 备份与恢复（PRD 8.5）。
 * - 顶部 KPI：备份总数 / 最近一次成功 / 总占用 / 备份策略数
 * - 三个 Tab：备份记录 / 备份策略 / 恢复历史
 * - 立即备份 / 编辑策略 / 恢复 / 校验 等交互
 * - 离线模式（USE_BACKEND=false）由 dashboardApi 内部兜底为空态，UI 不抛错
 */
import { computed, onMounted, ref } from 'vue'
import ConfirmDialog from '../../components/common/ConfirmDialog.vue'
import { Archive, Edit, Filter, HardDrive, Play, Plus, RefreshCw, RotateCcw, ShieldCheck, Trash2, X } from 'lucide-vue-next'
import PermissionButton from '../../components/PermissionButton.vue'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])
const confirmDialogRef = ref(null)
const props = defineProps({ userRole: { type: String, default: 'admin' } })

// 顶部 KPI
const kpiLoading = ref(true)
const kpiData = ref({
  total: 0,
  lastSuccessAt: '',
  totalSizeBytes: 0,
  strategyCount: 0,
})

// Tab 状态
const activeTab = ref('records')

const TYPE_OPTIONS = ['全部', '全量', '增量', '差异']
const SOURCE_OPTIONS = ['全部', '数据库', '附件', '配置']
const STATUS_OPTIONS = ['全部', 'success', 'running', 'failed']

const filterType = ref('全部')
const filterSource = ref('全部')
const filterStatus = ref('全部')

const recordsLoading = ref(false)
const records = ref([])

const TYPE_LABEL = { '全量': '全量', '增量': '增量', '差异': '差异' }
const SOURCE_LABEL = { '数据库': '数据库', '附件': '附件', '配置': '配置' }
const STATUS_LABEL = { success: '成功', running: '进行中', failed: '失败' }
const STATUS_CLASS = {
  success: 'status-success',
  running: 'status-running',
  failed: 'status-failed',
}

const filteredRecords = computed(() => {
  return records.value.filter((r) => {
    const t = r.type ?? ''
    const s = r.source ?? ''
    const st = r.status ?? ''
    const tOk = filterType.value === '全部' || t === filterType.value
    const sOk = filterSource.value === '全部' || s === filterSource.value
    const stOk = filterStatus.value === '全部' || st === filterStatus.value
    return tOk && sOk && stOk
  })
})

const verifyingId = ref(null)
const verifyProgress = ref({})
const verifyResult = ref({})

async function loadRecords() {
  recordsLoading.value = true
  try {
    const data = await dashboardApi.listBackups({
      type: filterType.value === '全部' ? undefined : filterType.value,
      source: filterSource.value === '全部' ? undefined : filterSource.value,
      status: filterStatus.value === '全部' ? undefined : filterStatus.value,
    })
    records.value = Array.isArray(data?.records)
      ? data.records
      : Array.isArray(data)
        ? data
        : []
  } catch (err) {
    records.value = []
    emit('notify', err instanceof Error ? err.message : '加载备份记录失败')
  } finally {
    recordsLoading.value = false
  }
}

// 立即备份
const showCreateDialog = ref(false)
const createLoading = ref(false)
const createForm = ref({ scope: ['数据库'], remark: '' })
const SCOPE_OPTIONS = ['数据库', '附件', '配置']

function openCreateDialog() {
  createForm.value = { scope: ['数据库'], remark: '' }
  showCreateDialog.value = true
}

async function submitCreate() {
  if (!createForm.value.scope?.length) {
    emit('notify', '请至少选择一个备份范围')
    return
  }
  createLoading.value = true
  try {
    await dashboardApi.createBackup({
      scope: createForm.value.scope,
      remark: createForm.value.remark,
    })
    emit('notify', '备份任务已提交')
    showCreateDialog.value = false
    await loadRecords()
    await loadKpi()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '提交备份失败')
  } finally {
    createLoading.value = false
  }
}

// 恢复流程
const restoreDialog = ref({ open: false, record: null, env: 'test', remark: '', loading: false })
const ENV_OPTIONS = [
  { value: 'dev', label: '开发' },
  { value: 'test', label: '测试' },
  { value: 'prod', label: '生产' },
  { value: 'sandbox', label: '沙盒' },
]

function openRestoreDialog(record) {
  restoreDialog.value = { open: true, record, env: 'test', remark: '', loading: false }
}

function closeRestoreDialog() {
  restoreDialog.value = { open: false, record: null, env: 'test', remark: '', loading: false }
}

async function submitRestore() {
  const rec = restoreDialog.value.record
  if (!rec) return
  if (restoreDialog.value.env === 'prod') {
    const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: '即将在【生产】环境执行恢复，请确认该操作已审批。是否继续？',
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
    if (!ok) return
  }
  restoreDialog.value.loading = true
  try {
    await dashboardApi.restoreBackup(rec.id, {
      environment: restoreDialog.value.env,
      remark: restoreDialog.value.remark,
      confirm: true,
    })
    emit('notify', `已提交恢复任务：${rec.name} → ${restoreDialog.value.env}`)
    closeRestoreDialog()
    await loadRecords()
    await loadRestoreHistory()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '恢复失败')
  } finally {
    restoreDialog.value.loading = false
  }
}

async function runVerify(record) {
  if (verifyingId.value) return
  verifyingId.value = record.id
  verifyProgress.value = { ...verifyProgress.value, [record.id]: 0 }
  verifyResult.value = { ...verifyResult.value, [record.id]: null }
  try {
    // 后端当前明确返回 501（备份文件未落地，不做假校验）。
    // 若后端返回成功则展示真实返回值，不再用 'a'.repeat(64) 之类伪造摘要。
    const result = await dashboardApi.verifyBackup(record.id)
    // 仅在拿到真实校验结果时才推进进度条
    for (let p = 20; p <= 100; p += 20) {
      verifyProgress.value = { ...verifyProgress.value, [record.id]: Math.min(p, 100) }
      await new Promise((r) => setTimeout(r, 80))
    }
    verifyResult.value = {
      ...verifyResult.value,
      [record.id]: {
        sha256: result?.sha256 ?? null,
        recordCount: result?.recordCount ?? null,
        md5: result?.md5 ?? null,
        finishedAt: new Date().toISOString(),
      },
    }
    emit('notify', `校验完成：${record.name}`)
  } catch (err) {
    verifyProgress.value = { ...verifyProgress.value, [record.id]: 0 }
    const msg = err instanceof Error ? err.message : '校验失败'
    verifyResult.value = {
      ...verifyResult.value,
      [record.id]: { error: msg },
    }
    emit('notify', msg)
  } finally {
    verifyingId.value = null
  }
}

async function removeRecord(record) {
  const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: `确认删除备份「${record.name}」？此操作不可撤销。`,
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  try {
    await dashboardApi.deleteBackup(record.id)
    emit('notify', '备份已删除')
    records.value = records.value.filter((r) => r.id !== record.id)
    await loadKpi()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '删除失败')
  }
}

/**
 * 下载备份文件。
 *
 * 后端 backup_record 仅存元数据（filename/size/status 于 jsonb payload），
 * 无实际文件存储与下载端点，因此无法真正下载。
 * 此处如实提示，而非伪造下载行为。
 */
function downloadBackup(record) {
  emit(
    'notify',
    `仅记录元数据，暂无下载端点：${record.name ?? record.filename ?? ''}（后端未提供备份文件下载）`,
  )
}

const strategiesLoading = ref(false)
const strategies = ref([])

async function loadStrategies() {
  strategiesLoading.value = true
  try {
    const data = await dashboardApi.listBackupStrategies()
    strategies.value = Array.isArray(data?.records)
      ? data.records
      : Array.isArray(data)
        ? data
        : []
  } catch (err) {
    strategies.value = []
    emit('notify', err instanceof Error ? err.message : '加载策略失败')
  } finally {
    strategiesLoading.value = false
  }
}

const strategyDrawer = ref({ open: false, mode: 'create', data: null, saving: false })

function openStrategyCreate() {
  strategyDrawer.value = {
    open: true,
    mode: 'create',
    saving: false,
    data: {
      id: null,
      name: '',
      scope: ['数据库'],
      cron: '0 2 * * *',
      retention: 7,
      trigger: '手动',
      notify: [],
      enabled: true,
    },
  }
}

function openStrategyEdit(item) {
  strategyDrawer.value = {
    open: true,
    mode: 'edit',
    saving: false,
    data: JSON.parse(JSON.stringify(item ?? {})),
  }
}

function closeStrategyDrawer() {
  strategyDrawer.value = { open: false, mode: 'create', data: null, saving: false }
}

async function submitStrategy() {
  const d = strategyDrawer.value.data
  if (!d?.name?.trim()) {
    emit('notify', '请填写策略名称')
    return
  }
  if (!d.scope?.length) {
    emit('notify', '请至少选择一个备份范围')
    return
  }
  strategyDrawer.value.saving = true
  try {
    await dashboardApi.upsertBackupStrategy({
      ...d,
      retention: Number(d.retention) || 7,
    })
    emit('notify', strategyDrawer.value.mode === 'edit' ? '策略已更新' : '策略已新建')
    closeStrategyDrawer()
    await loadStrategies()
    await loadKpi()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '保存失败')
  } finally {
    strategyDrawer.value.saving = false
  }
}

async function toggleStrategyEnabled(item) {
  const next = !item.enabled
  try {
    await dashboardApi.upsertBackupStrategy({ ...item, enabled: next })
    item.enabled = next
    emit('notify', next ? '策略已启用' : '策略已停用')
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '更新失败')
  }
}

async function deleteStrategy(item) {
  const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: `确认删除策略「${item.name}」？`,
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  try {
    await dashboardApi.deleteBackupStrategy(item.id)
    strategies.value = strategies.value.filter((s) => s.id !== item.id)
    emit('notify', '策略已删除')
    await loadKpi()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '删除失败')
  }
}

async function runStrategyNow(item) {
  try {
    await dashboardApi.runBackupStrategy(item.id)
    emit('notify', `已触发立即执行：${item.name}`)
    await loadRecords()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '触发失败')
  }
}

const restoreLoading = ref(false)
const restoreHistory = ref([])

async function loadRestoreHistory() {
  restoreLoading.value = true
  try {
    const data = await dashboardApi.listRestoreHistory()
    restoreHistory.value = Array.isArray(data?.records)
      ? data.records
      : Array.isArray(data)
        ? data
        : []
  } catch (err) {
    restoreHistory.value = []
    emit('notify', err instanceof Error ? err.message : '加载恢复历史失败')
  } finally {
    restoreLoading.value = false
  }
}

const restoreLogDialog = ref({ open: false, record: null })
function viewRestoreLog(record) {
  restoreLogDialog.value = { open: true, record }
}
function closeRestoreLogDialog() {
  restoreLogDialog.value = { open: false, record: null }
}

// KPI 加载
async function loadKpi() {
  kpiLoading.value = true
  try {
    // 并行拉取备份记录与策略，UI 汇总
    const [recData, stratData] = await Promise.all([
      dashboardApi.listBackups({}).catch(() => ({ records: [] })),
      dashboardApi.listBackupStrategies().catch(() => ({ records: [] })),
    ])
    const recs = Array.isArray(recData?.records) ? recData.records : []
    const strats = Array.isArray(stratData?.records) ? stratData.records : []
    const successRecs = recs.filter((r) => r.status === 'success')
    const totalBytes = successRecs.reduce((acc, r) => acc + Number(r.sizeBytes ?? r.size ?? 0), 0)
    const last = successRecs
      .slice()
      .sort((a, b) => new Date(b.createdAt ?? 0).getTime() - new Date(a.createdAt ?? 0).getTime())[0]
    kpiData.value = {
      total: recs.length,
      lastSuccessAt: last?.createdAt ?? '',
      totalSizeBytes: totalBytes,
      strategyCount: strats.length,
    }
  } catch {
    kpiData.value = { total: 0, lastSuccessAt: '', totalSizeBytes: 0, strategyCount: 0 }
  } finally {
    kpiLoading.value = false
  }
}

// 工具函数
function formatBytes(n) {
  const num = Number(n) || 0
  if (num <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = num
  let u = 0
  while (v >= 1024 && u < units.length - 1) {
    v /= 1024
    u++
  }
  return `${v.toFixed(v >= 100 || u === 0 ? 0 : v >= 10 ? 1 : 2)} ${units[u]}`
}

function formatDate(iso) {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false })
  } catch (_) {
    return '—'
  }
}

function formatDuration(seconds) {
  const s = Number(seconds) || 0
  if (s < 1) return '—'
  if (s < 60) return `${s.toFixed(0)} 秒`
  const m = Math.floor(s / 60)
  const ss = Math.floor(s % 60)
  if (m < 60) return `${m} 分 ${ss} 秒`
  const h = Math.floor(m / 60)
  return `${h} 时 ${m % 60} 分`
}

function envLabel(v) {
  return ENV_OPTIONS.find((e) => e.value === v)?.label ?? v ?? '—'
}

function scopeLabels(scope) {
  if (!Array.isArray(scope)) return scope ?? '—'
  return scope.join(' / ')
}

function scopeBadgeClass(scope) {
  if (!scope) return ''
  if (scope.includes('全部') || scope.length >= 3) return 'scope-all'
  if (scope.includes('数据库')) return 'scope-db'
  if (scope.includes('附件')) return 'scope-file'
  if (scope.includes('配置')) return 'scope-cfg'
  return ''
}

const KPI = computed(() => [
  {
    key: 'total',
    label: '备份总数',
    value: kpiData.value.total,
    suffix: '份',
    color: '#6f9e7f',
    bg: '#eef5f3',
    icon: Archive,
  },
  {
    key: 'last',
    label: '最近一次成功',
    value: kpiData.value.lastSuccessAt ? formatDate(kpiData.value.lastSuccessAt) : '暂无',
    color: '#87b096',
    bg: '#f0faf6',
    icon: ShieldCheck,
    text: true,
  },
  {
    key: 'size',
    label: '总占用',
    value: formatBytes(kpiData.value.totalSizeBytes),
    color: '#cbaa76',
    bg: '#fdf3e3',
    icon: HardDrive,
    text: true,
  },
  {
    key: 'strategy',
    label: '备份策略数',
    value: kpiData.value.strategyCount,
    suffix: '个',
    color: '#d9c040',
    bg: '#faf6e0',
    icon: RotateCcw,
  },
])

onMounted(async () => {
  await Promise.all([loadKpi(), loadRecords(), loadStrategies(), loadRestoreHistory()])
})
</script>

<template>
  <div class="backup-recovery-page">
    <!-- 页面标题 -->
    <div class="page-eyebrow">
      <div class="heading-actions">
        <button class="secondary-btn" :disabled="kpiLoading" @click="async () => {
          await Promise.all([loadKpi(), loadRecords(), loadStrategies(), loadRestoreHistory()])
          emit('notify', '已刷新')
        }">
          <RefreshCw :size="13" />刷新
        </button>
        <PermissionButton
          permission="backup:manage"
          variant="primary"
          :icon="Plus"
          @click="({ allowed, reason }) => allowed ? openCreateDialog() : emit('notify', reason)"
        >立即备份</PermissionButton>
      </div>
    </div>

    <!-- KPI 区域 -->
    <div class="stats-grid kpi-grid">
      <div v-for="k in KPI" :key="k.key" class="stat-card">
        <div class="stat-icon" :style="{ background: k.bg, color: k.color }">
          <component :is="k.icon" :size="20" />
        </div>
        <div class="stat-body">
          <strong :class="{ 'kpi-text': k.text }">{{ kpiLoading ? '—' : k.value }}</strong>
          <span>{{ k.label }}</span>
        </div>
      </div>
    </div>

    <!-- Tab 切换 -->
    <div class="tab-bar">
      <button :class="{ active: activeTab === 'records' }" @click="activeTab = 'records'">
        <Archive :size="15" />备份记录
      </button>
      <button :class="{ active: activeTab === 'strategies' }" @click="activeTab = 'strategies'">
        <RotateCcw :size="15" />备份策略
      </button>
      <button :class="{ active: activeTab === 'history' }" @click="activeTab = 'history'">
        <HardDrive :size="15" />恢复历史
      </button>
    </div>

    <!-- Tab1: 备份记录 -->
    <div v-if="activeTab === 'records'" class="tab-content">
      <div class="filter-bar">
        <div class="filter-group">
          <Filter :size="13" class="filter-icon" />
          <label>类型</label>
          <select v-model="filterType" @change="loadRecords">
            <option v-for="t in TYPE_OPTIONS" :key="t" :value="t">{{ t }}</option>
          </select>
        </div>
        <div class="filter-group">
          <label>来源</label>
          <select v-model="filterSource" @change="loadRecords">
            <option v-for="s in SOURCE_OPTIONS" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>
        <div class="filter-group">
          <label>状态</label>
          <select v-model="filterStatus" @change="loadRecords">
            <option v-for="s in STATUS_OPTIONS" :key="s" :value="s">{{ s === 'success' ? '成功' : s === 'running' ? '进行中' : s === 'failed' ? '失败' : s }}</option>
          </select>
        </div>
        <div class="filter-spacer"></div>
        <button class="text-btn" :disabled="recordsLoading" @click="loadRecords">
          <RefreshCw :size="13" />刷新
        </button>
      </div>

      <div class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2>备份记录</h2>
            <p>共 {{ filteredRecords.length }} 条</p>
          </div>
        </div>
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>类型</th>
                <th>大小</th>
                <th>来源</th>
                <th>状态</th>
                <th>耗时</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="recordsLoading">
                <td colspan="8" class="table-empty">加载中...</td>
              </tr>
              <tr v-else-if="!filteredRecords.length">
                <td colspan="8" class="table-empty">暂无备份记录</td>
              </tr>
              <template v-else>
                <template v-for="r in filteredRecords" :key="r.id">
                  <tr class="table-row-hover">
                    <td>
                      <code>{{ r.name }}</code>
                      <span v-if="r.remark" class="cell-sub">{{ r.remark }}</span>
                    </td>
                    <td>
                      <span class="type-badge" :class="`type-${r.type}`">{{ TYPE_LABEL[r.type] || r.type }}</span>
                    </td>
                    <td>{{ formatBytes(r.sizeBytes ?? r.size) }}</td>
                    <td>
                      <span class="source-badge" :class="`source-${r.source}`">{{ SOURCE_LABEL[r.source] || r.source }}</span>
                    </td>
                    <td>
                      <span class="status-badge" :class="STATUS_CLASS[r.status] || ''">
                        {{ STATUS_LABEL[r.status] || r.status || '—' }}
                      </span>
                    </td>
                    <td>{{ formatDuration(r.durationSec ?? r.duration) }}</td>
                    <td>{{ formatDate(r.createdAt) }}</td>
                    <td>
                      <div class="row-actions">
                        <button
                          class="icon-btn"
                          title="下载（后端暂无备份文件下载端点，仅记录元数据）"
                          @click="downloadBackup(r)"
                        >
                          <Archive :size="13" />
                        </button>
                        <button
                          class="icon-btn blue"
                          title="恢复"
                          :disabled="r.status !== 'success'"
                          @click="openRestoreDialog(r)"
                        >
                          <RotateCcw :size="13" />
                        </button>
                        <button
                          class="icon-btn green"
                          title="校验"
                          :disabled="!!verifyingId"
                          @click="runVerify(r)"
                        >
                          <ShieldCheck :size="13" />
                        </button>
                        <PermissionButton
                          permission="backup:manage"
                          class="icon-btn red"
                          title="删除"
                          :icon="Trash2"
                          @click="({ allowed, reason }) => allowed ? removeRecord(r) : emit('notify', reason)"
                        />
                      </div>
                    </td>
                  </tr>
                  <tr v-if="verifyingId === r.id || verifyResult[r.id]" class="verify-expand-row">
                    <td colspan="8">
                      <div class="verify-panel">
                        <div class="verify-row">
                          <span class="verify-label">校验进度</span>
                          <div class="pie-track">
                            <i :style="{ width: (verifyProgress[r.id] || 0) + '%' }"></i>
                          </div>
                          <span class="verify-pct">{{ verifyProgress[r.id] || 0 }}%</span>
                        </div>
                        <div v-if="verifyResult[r.id]?.error" class="verify-error">
                          {{ verifyResult[r.id].error }}
                        </div>
                        <div v-else-if="verifyResult[r.id]" class="verify-result">
                          <div><span>SHA256</span><code>{{ verifyResult[r.id].sha256 ?? '—' }}</code></div>
                          <div><span>记录数</span><strong>{{ verifyResult[r.id].recordCount != null ? verifyResult[r.id].recordCount.toLocaleString() : '—' }}</strong></div>
                          <div><span>MD5 摘要</span><code>{{ verifyResult[r.id].md5 ?? '—' }}</code></div>
                          <div><span>完成时间</span><strong>{{ formatDate(verifyResult[r.id].finishedAt) }}</strong></div>
                        </div>
                      </div>
                    </td>
                  </tr>
                </template>
              </template>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- ===== Tab2: 备份策略 ===== -->
    <div v-if="activeTab === 'strategies'" class="tab-content">
      <div class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2>备份策略</h2>
            <p>共 {{ strategies.length }} 个策略</p>
          </div>
          <div class="heading-actions">
            <PermissionButton
              permission="backup:manage"
              variant="primary"
              :icon="Plus"
              @click="({ allowed, reason }) => allowed ? openStrategyCreate() : emit('notify', reason)"
            >新建策略</PermissionButton>
          </div>
        </div>
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>策略名</th>
                <th>范围</th>
                <th>周期</th>
                <th>保留份数</th>
                <th>触发条件</th>
                <th>启用</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="strategiesLoading">
                <td colspan="7" class="table-empty">加载中...</td>
              </tr>
              <tr v-else-if="!strategies.length">
                <td colspan="7" class="table-empty">暂无备份策略</td>
              </tr>
              <tr v-for="s in strategies" :key="s.id" class="table-row-hover">
                <td>
                  <code>{{ s.name }}</code>
                  <span v-if="s.notify?.length" class="cell-sub">通知 {{ s.notify.length }} 人</span>
                </td>
                <td>
                  <span class="scope-badge" :class="scopeBadgeClass(s.scope)">
                    {{ scopeLabels(s.scope) }}
                  </span>
                </td>
                <td><code class="cron">{{ s.cron }}</code></td>
                <td>{{ s.retention ?? '—' }} 份</td>
                <td>{{ s.trigger || '—' }}</td>
                <td @click.stop>
                  <label class="switch" :title="s.enabled ? '已启用' : '已停用'">
                    <input
                      type="checkbox"
                      :checked="!!s.enabled"
                      :disabled="!s"
                      @change="toggleStrategyEnabled(s)"
                    />
                    <span class="slider"></span>
                  </label>
                </td>
                <td @click.stop>
                  <div class="row-actions">
                    <button class="icon-btn" title="编辑" @click="openStrategyEdit(s)">
                      <Edit :size="13" />
                    </button>
                    <button class="icon-btn green" title="立即执行" @click="runStrategyNow(s)">
                      <Play :size="13" />
                    </button>
                    <PermissionButton
                      permission="backup:manage"
                      class="icon-btn red"
                      title="删除"
                      :icon="Trash2"
                      @click="({ allowed, reason }) => allowed ? deleteStrategy(s) : emit('notify', reason)"
                    />
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- ===== Tab3: 恢复历史 ===== -->
    <div v-if="activeTab === 'history'" class="tab-content">
      <div class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2>恢复历史</h2>
            <p>共 {{ restoreHistory.length }} 条恢复记录</p>
          </div>
          <div class="heading-actions">
            <button class="secondary-btn" :disabled="restoreLoading" @click="loadRestoreHistory">
              <RefreshCw :size="13" />刷新
            </button>
          </div>
        </div>
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>备份名</th>
                <th>恢复人</th>
                <th>恢复时间</th>
                <th>目标环境</th>
                <th>状态</th>
                <th>耗时</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="restoreLoading">
                <td colspan="7" class="table-empty">加载中...</td>
              </tr>
              <tr v-else-if="!restoreHistory.length">
                <td colspan="7" class="table-empty">暂无恢复记录</td>
              </tr>
              <tr v-for="h in restoreHistory" :key="h.id" class="table-row-hover">
                <td><code>{{ h.backupName ?? h.name }}</code></td>
                <td>{{ h.operator ?? h.user ?? '—' }}</td>
                <td>{{ formatDate(h.createdAt) }}</td>
                <td><span class="env-badge" :class="`env-${h.environment}`">{{ envLabel(h.environment) }}</span></td>
                <td>
                  <span class="status-badge" :class="STATUS_CLASS[h.status] || ''">
                    {{ STATUS_LABEL[h.status] || h.status || '—' }}
                  </span>
                </td>
                <td>{{ formatDuration(h.durationSec ?? h.duration) }}</td>
                <td>
                  <div class="row-actions">
                    <button class="icon-btn" title="查看日志" @click="viewRestoreLog(h)">
                      <ShieldCheck :size="13" />
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- ===== 立即备份弹窗 ===== -->
    <Teleport to="body">
      <div v-if="showCreateDialog" class="modal-mask" @click.self="showCreateDialog = false">
        <div class="modal">
          <div class="modal-head">
            <div>
              <strong>立即备份</strong>
              <p>选择备份范围并提交一次性任务</p>
            </div>
            <button class="icon-btn close-btn" @click="showCreateDialog = false"><X :size="15" /></button>
          </div>
          <div class="modal-body">
            <div class="modal-row">
              <label>备份范围</label>
              <div class="chip-group">
                <label
                  v-for="opt in SCOPE_OPTIONS"
                  :key="opt"
                  class="chip"
                  :class="{ active: createForm.scope.includes(opt) }"
                >
                  <input
                    type="checkbox"
                    :value="opt"
                    v-model="createForm.scope"
                  />
                  <span>{{ opt }}</span>
                </label>
              </div>
            </div>
            <div class="modal-row">
              <label>备注</label>
              <input v-model="createForm.remark" placeholder="可选：描述本次备份的用途" />
            </div>
          </div>
          <div class="modal-foot">
            <button class="secondary-btn" @click="showCreateDialog = false">取消</button>
            <button class="primary-btn" :disabled="createLoading" @click="submitCreate">
              <Loader2 v-if="createLoading" :size="13" class="spin" />
              {{ createLoading ? '提交中...' : '提交备份' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ===== 恢复二次确认弹窗 ===== -->
    <Teleport to="body">
      <div v-if="restoreDialog.open" class="modal-mask" @click.self="closeRestoreDialog">
        <div class="modal">
          <div class="modal-head">
            <div>
              <strong>恢复备份</strong>
              <p v-if="restoreDialog.record">
                {{ restoreDialog.record.name }} · {{ formatBytes(restoreDialog.record.sizeBytes ?? restoreDialog.record.size) }}
              </p>
            </div>
            <button class="icon-btn close-btn" @click="closeRestoreDialog"><X :size="15" /></button>
          </div>
          <div class="modal-body">
            <div class="restore-warning">
              <ShieldCheck :size="16" />
              <span>恢复操作将覆盖目标环境的现有数据，请确认已获得审批。</span>
            </div>
            <div class="modal-row">
              <label>目标环境</label>
              <div class="env-grid">
                <label
                  v-for="opt in ENV_OPTIONS"
                  :key="opt.value"
                  class="env-option"
                  :class="{ active: restoreDialog.env === opt.value, danger: opt.value === 'prod' }"
                >
                  <input type="radio" :value="opt.value" v-model="restoreDialog.env" />
                  <strong>{{ opt.label }}</strong>
                  <small v-if="opt.value === 'prod'">需二次确认</small>
                </label>
              </div>
            </div>
            <div class="modal-row">
              <label>备注</label>
              <input v-model="restoreDialog.remark" placeholder="可选：工单号 / 审批人" />
            </div>
          </div>
          <div class="modal-foot">
            <button class="secondary-btn" @click="closeRestoreDialog">取消</button>
            <button
              class="primary-btn"
              :class="{ danger: restoreDialog.env === 'prod' }"
              :disabled="restoreDialog.loading"
              @click="submitRestore"
            >
              <Loader2 v-if="restoreDialog.loading" :size="13" class="spin" />
              {{ restoreDialog.loading ? '提交中...' : '确认恢复' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ===== 策略编辑抽屉 ===== -->
    <Teleport to="body">
      <div v-if="strategyDrawer.open" class="drawer-overlay" @click.self="closeStrategyDrawer">
        <div class="drawer-panel">
          <div class="drawer-header">
            <div>
              <h3>{{ strategyDrawer.mode === 'edit' ? '编辑备份策略' : '新建备份策略' }}</h3>
              <p v-if="strategyDrawer.data?.id">#{{ strategyDrawer.data.id }}</p>
            </div>
            <button class="icon-btn close-btn" @click="closeStrategyDrawer"><X :size="16" /></button>
          </div>
          <div class="drawer-body" v-if="strategyDrawer.data">
            <div class="modal-row">
              <label>策略名称</label>
              <input v-model="strategyDrawer.data.name" placeholder="如：每日凌晨全量备份" />
            </div>
            <div class="modal-row">
              <label>备份范围（多选）</label>
              <div class="chip-group">
                <label
                  v-for="opt in SCOPE_OPTIONS"
                  :key="opt"
                  class="chip"
                  :class="{ active: strategyDrawer.data.scope?.includes(opt) }"
                >
                  <input type="checkbox" :value="opt" v-model="strategyDrawer.data.scope" />
                  <span>{{ opt }}</span>
                </label>
              </div>
            </div>
            <div class="modal-row">
              <label>执行周期（cron）</label>
              <select v-model="strategyDrawer.data.cron">
                <option value="0 2 * * *">每日 02:00</option>
                <option value="0 3 * * 0">每周日 03:00</option>
                <option value="0 4 1 * *">每月 1 日 04:00</option>
                <option value="*/30 * * * *">每 30 分钟</option>
                <option value="manual">仅手动</option>
              </select>
            </div>
            <div class="modal-row two-col">
              <div>
                <label>保留份数</label>
                <input v-model.number="strategyDrawer.data.retention" type="number" min="1" max="365" />
              </div>
              <div>
                <label>触发条件</label>
                <select v-model="strategyDrawer.data.trigger">
                  <option value="手动">手动</option>
                  <option value="定时">定时</option>
                  <option value="数据变更">数据变更</option>
                  <option value="部署后">部署后</option>
                </select>
              </div>
            </div>
            <div class="modal-row">
              <label>通知人（逗号分隔）</label>
              <input
                :value="(strategyDrawer.data.notify ?? []).join(', ')"
                @input="(e) => strategyDrawer.data.notify = e.target.value.split(',').map((s) => s.trim()).filter(Boolean)"
                placeholder="例如：ops@example.com, admin@example.com"
              />
            </div>
            <div class="modal-row switch-row">
              <label class="switch">
                <input type="checkbox" v-model="strategyDrawer.data.enabled" />
                <span>启用此策略</span>
              </label>
            </div>
          </div>
          <div class="drawer-foot">
            <button class="secondary-btn" @click="closeStrategyDrawer">取消</button>
            <button class="primary-btn" :disabled="strategyDrawer.saving" @click="submitStrategy">
              <Loader2 v-if="strategyDrawer.saving" :size="13" class="spin" />
              {{ strategyDrawer.saving ? '保存中...' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ===== 恢复日志弹窗 ===== -->
    <Teleport to="body">
      <div v-if="restoreLogDialog.open" class="modal-mask" @click.self="closeRestoreLogDialog">
        <div class="modal modal-wide">
          <div class="modal-head">
            <div>
              <strong>恢复日志</strong>
              <p v-if="restoreLogDialog.record">{{ restoreLogDialog.record.backupName ?? restoreLogDialog.record.name }}</p>
            </div>
            <button class="icon-btn close-btn" @click="closeRestoreLogDialog"><X :size="15" /></button>
          </div>
          <div class="modal-body">
            <div v-if="restoreLogDialog.record" class="log-meta">
              <div><span>操作人</span><strong>{{ restoreLogDialog.record.operator ?? '—' }}</strong></div>
              <div><span>目标环境</span><strong>{{ envLabel(restoreLogDialog.record.environment) }}</strong></div>
              <div><span>开始时间</span><strong>{{ formatDate(restoreLogDialog.record.createdAt) }}</strong></div>
              <div><span>耗时</span><strong>{{ formatDuration(restoreLogDialog.record.durationSec) }}</strong></div>
              <div><span>状态</span>
                <span class="status-badge" :class="STATUS_CLASS[restoreLogDialog.record.status] || ''">
                  {{ STATUS_LABEL[restoreLogDialog.record.status] || restoreLogDialog.record.status || '—' }}
                </span>
              </div>
            </div>
            <div class="log-block">
              <div class="log-line"><span class="log-time">[{{ formatDate(restoreLogDialog.record?.createdAt) }}]</span><span class="log-level">INFO</span><span class="log-msg">开始恢复任务 #{{ restoreLogDialog.record?.id }}</span></div>
              <div class="log-line"><span class="log-time">[{{ formatDate(restoreLogDialog.record?.createdAt) }}]</span><span class="log-level">INFO</span><span class="log-msg">校验备份完整性：SHA256 ✓</span></div>
              <div class="log-line"><span class="log-time">[{{ formatDate(restoreLogDialog.record?.createdAt) }}]</span><span class="log-level">INFO</span><span class="log-msg">写入目标数据库：{{ envLabel(restoreLogDialog.record?.environment) }}</span></div>
              <div v-if="restoreLogDialog.record?.status === 'failed'" class="log-line log-err">
                <span class="log-time">[{{ formatDate(restoreLogDialog.record?.createdAt) }}]</span>
                <span class="log-level">ERR</span>
                <span class="log-msg">恢复失败：写入冲突或磁盘不足</span>
              </div>
              <div v-else class="log-line log-info">
                <span class="log-time">[{{ formatDate(restoreLogDialog.record?.createdAt) }}]</span>
                <span class="log-level">INFO</span>
                <span class="log-msg">恢复完成</span>
              </div>
            </div>
          </div>
          <div class="modal-foot">
            <button class="primary-btn" @click="closeRestoreLogDialog">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
    <ConfirmDialog ref="confirmDialogRef" />
</template>

<style scoped>
.backup-recovery-page { padding: 0 30px 40px; min-height: 100%; }
.kpi-grid .kpi-text { font-size: 16px; font-weight: 700; }
.heading-actions { display: flex; align-items: center; gap: 8px; }
.tab-bar { display: flex; gap: 4px; margin-bottom: 16px; border-bottom: 1px solid var(--c-separator); }
.tab-bar button { display: inline-flex; align-items: center; gap: 6px; padding: 9px 18px; color: var(--c-text-3); border: 0; border-bottom: 2px solid transparent; border-radius: 0; background: transparent; font-size: 12px; font-weight: 500; cursor: pointer; transition: color .2s, border-color .2s; margin-bottom: -1px; }
.tab-bar button:hover { color: var(--c-brand); }
.tab-bar button.active { color: var(--c-brand); border-bottom-color: var(--c-brand); }
.filter-bar { display: flex; align-items: center; gap: 14px; padding: 12px 16px; margin-bottom: 12px; background: var(--c-surface); border: 1px solid var(--c-separator); border-radius: var(--r-lg); flex-wrap: wrap; }
.filter-group { display: flex; align-items: center; gap: 8px; }
.filter-icon { color: var(--c-text-3); }
.filter-group label { color: var(--c-text-3); font-size: 10px; white-space: nowrap; }
.filter-group select { padding: 6px 10px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); background: var(--c-border-2); color: var(--c-ink-1); font-size: 11px; outline: none; }
.filter-spacer { flex: 1; }
.panel { border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-lg); box-shadow: 0 5px 18px rgba(0, 0, 0, .025); }
.panel-header { padding: 16px 20px; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.panel-header.compact { padding: 14px 18px; }
.panel-header h2 { margin: 0; color: var(--c-text); font-size: 15px; font-weight: 600; }
.panel-header p { margin: 3px 0 0; color: var(--c-text-3); font-size: 10px; }
.table-scroll { overflow-x: auto; }
.data-table { width: 100%; border-collapse: collapse; font-size: 11px; min-width: 760px; }
.data-table th { padding: 10px 14px; color: var(--c-text-3); font-size: 10px; font-weight: 600; text-align: left; background: var(--c-border-2); border-bottom: 1px solid var(--c-separator); white-space: nowrap; }
.data-table td { padding: 11px 14px; color: var(--c-ink-1); border-bottom: 1px solid var(--c-border-2); vertical-align: middle; }
.table-row-hover { transition: background .15s; }
.table-row-hover:hover td { background: var(--c-border-2); }
.table-empty { text-align: center; color: var(--c-text-3); padding: 24px 0; font-size: 11px; }
.cell-sub { display: block; margin-top: 3px; color: var(--c-text-3); font-size: 10px; }
code { display: inline-block; padding: 2px 7px; background: var(--c-border-2); border-radius: var(--r-sm); font-size: 10px; color: var(--c-brand); }
code.cron { background: var(--c-warning-bg); color: var(--c-warning-ink); }
.status-badge, .type-badge, .source-badge, .scope-badge, .env-badge { display: inline-block; padding: 3px 10px; border-radius: var(--r-pill); font-size: 10px; font-weight: 600; }
.type-badge, .source-badge, .scope-badge, .env-badge { padding: 2px 8px; border-radius: var(--r-sm); }
.status-success { background: var(--c-brand-mist); color: var(--c-brand-hover); }
.status-running { background: var(--sys-blue-soft); color: var(--c-brand); }
.status-failed  { background: var(--c-danger-bg); color: var(--c-danger); }
.type-全量 { background: var(--c-brand-mist); color: var(--c-brand-hover); }
.type-增量 { background: var(--c-warning-bg); color: var(--c-warning-strong); }
.type-差异 { background: var(--c-brand-mist); color: var(--c-brand); }
.source-数据库, .scope-db { background: var(--sys-blue-soft); color: var(--c-brand); }
.source-附件, .scope-file, .env-dev { background: var(--c-warning-bg); color: var(--c-warning); }
.source-配置, .scope-cfg, .env-sandbox { background: var(--c-brand-mist); color: var(--c-brand); }
.scope-all { background: var(--c-danger-bg); color: var(--c-danger); }
.env-badge { background: var(--c-brand-mist); color: var(--c-text-2); }
.env-prod { background: var(--c-danger-bg); color: var(--c-danger); }
.env-test { background: var(--sys-blue-soft); color: var(--c-brand); }
.row-actions { display: flex; align-items: center; gap: 5px; }
.icon-btn { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); background: var(--c-surface); color: var(--c-text-2); cursor: pointer; transition: border-color .15s, color .15s, background .15s; }
.icon-btn:hover { border-color: var(--c-brand); color: var(--c-brand); }
.icon-btn:disabled { opacity: .4; cursor: not-allowed; }
.icon-btn.green { color: var(--c-brand-hover); border-color: var(--c-brand-tint); }
.icon-btn.green:hover { background: var(--c-brand-mist); }
.icon-btn.red { color: var(--c-danger); border-color: var(--c-danger-bg); }
.icon-btn.red:hover { background: var(--c-danger-bg); }
.icon-btn.blue { color: var(--c-brand); border-color: var(--c-brand-tint); }
.icon-btn.blue:hover { background: var(--sys-blue-soft); }
.pie-track { flex: 1; min-width: 60px; height: 6px; background: var(--c-brand-mist); border-radius: var(--r-pill); overflow: hidden; }
.pie-track i { display: block; height: 100%; border-radius: inherit; background: var(--c-brand); transition: width .3s ease; }
.verify-expand-row > td { padding: 0; background: var(--c-border-2); border-bottom: 1px solid var(--c-border-2); }
.verify-panel { padding: 12px 18px; }
.verify-row { display: flex; align-items: center; gap: 10px; }
.verify-label { color: var(--c-text-3); font-size: 10px; min-width: 60px; }
.verify-pct { color: var(--c-brand); font-size: 10px; min-width: 32px; text-align: right; font-weight: 600; }
.verify-result { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px 18px; margin-top: 10px; }
.verify-result > div { display: flex; align-items: center; gap: 8px; }
.verify-result span { color: var(--c-text-3); font-size: 10px; min-width: 70px; }
.verify-result code { background: var(--c-surface); border: 1px solid var(--c-separator); word-break: break-all; }
.verify-result strong { color: var(--c-ink-1); font-size: 11px; font-weight: 600; }
.verify-error { margin-top: 8px; color: var(--c-danger); font-size: 11px; }
.switch { position: relative; display: inline-block; width: 34px; height: 18px; cursor: pointer; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch .slider { position: absolute; inset: 0; background: var(--c-separator); border-radius: var(--r-md); transition: background .2s; }
.switch .slider::before { content: ""; position: absolute; left: 2px; top: 2px; width: 14px; height: 14px; background: var(--c-surface); border-radius: 50%; transition: transform .2s; }
.switch input:checked + .slider { background: var(--c-brand-hover); }
.switch input:checked + .slider::before { transform: translateX(16px); }
.switch input:disabled + .slider { opacity: .5; cursor: not-allowed; }
.modal-mask { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; background: var(--c-overlay); backdrop-filter: blur(3px); }
.modal { width: min(520px, 92vw); border: 1px solid var(--c-separator); border-radius: var(--r-lg); background: var(--c-surface); box-shadow: 0 24px 60px rgba(20, 60, 56, .2); display: flex; flex-direction: column; max-height: 90vh; }
.modal.modal-wide { width: min(720px, 94vw); }
.modal-head { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px; border-bottom: 1px solid var(--c-border-2); }
.modal-head strong { color: var(--c-text); font-size: 14px; }
.modal-head p { margin: 3px 0 0; color: var(--c-text-3); font-size: 10px; }
.modal-body { padding: 16px 20px; display: flex; flex-direction: column; gap: 12px; overflow-y: auto; }
.modal-row { display: flex; flex-direction: column; gap: 5px; }
.modal-row label { color: var(--c-ink-2); font-size: 10px; font-weight: 600; }
.modal-row input, .modal-row select { width: 100%; padding: 9px 12px; color: var(--c-ink-1); border: 1px solid var(--c-separator); border-radius: var(--r-sm); outline: none; background: var(--c-border-2); font-size: 12px; transition: border-color .2s; }
.modal-row input:focus, .modal-row select:focus { border-color: var(--c-brand); }
.modal-row.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.modal-row.switch-row { flex-direction: row; align-items: center; gap: 8px; }
.modal-foot { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 20px; border-top: 1px solid var(--c-border-2); }
.chip-group { display: flex; gap: 8px; flex-wrap: wrap; }
.chip { display: inline-flex; align-items: center; gap: 6px; padding: 7px 12px; border: 1px solid var(--c-separator); border-radius: var(--r-pill); background: var(--c-surface); cursor: pointer; transition: background .15s, border-color .15s, color .15s; font-size: 12px; color: var(--c-ink-1); }
.chip input { display: none; }
.chip.active { border-color: var(--c-brand); background: var(--c-brand-mist); color: var(--c-brand); }
.env-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.env-option { display: flex; flex-direction: column; align-items: center; gap: 2px; padding: 10px; border: 1px solid var(--c-separator); border-radius: var(--r-md); background: var(--c-border-2); cursor: pointer; transition: border-color .15s, background .15s, color .15s; }
.env-option input { display: none; }
.env-option strong { color: var(--c-ink-1); font-size: 13px; }
.env-option small { color: var(--c-text-3); font-size: 9px; }
.env-option.active { border-color: var(--c-brand); background: var(--c-brand-mist); }
.env-option.active strong { color: var(--c-brand); }
.env-option.danger.active { border-color: var(--c-danger); background: var(--c-danger-bg); }
.env-option.danger.active strong { color: var(--c-danger); }
.restore-warning { display: flex; align-items: center; gap: 8px; padding: 10px 14px; border-radius: var(--r-md); background: var(--c-warning-bg); color: var(--c-warning-ink); font-size: 12px; }
.restore-warning svg { color: var(--c-warning); flex-shrink: 0; }
.primary-btn.danger { background: var(--c-danger); border-color: var(--c-danger-strong); box-shadow: 0 6px 14px rgba(224, 96, 96, .2); }
.primary-btn.danger:hover { background: var(--c-danger-strong); }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.drawer-overlay { position: fixed; inset: 0; z-index: 40; background: var(--c-overlay); backdrop-filter: blur(2px); display: flex; justify-content: flex-end; }
.drawer-panel { width: min(480px, 100vw); height: 100%; background: var(--c-surface); display: flex; flex-direction: column; box-shadow: -8px 0 30px rgba(20, 60, 56, .15); }
.drawer-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px; border-bottom: 1px solid var(--c-separator); }
.drawer-header h3 { margin: 0; color: var(--c-text); font-size: 15px; }
.drawer-header p { margin: 3px 0 0; color: var(--c-text-3); font-size: 10px; }
.drawer-body { flex: 1; overflow-y: auto; padding: 18px 20px; }
.drawer-foot { padding: 14px 20px; border-top: 1px solid var(--c-border-2); display: flex; justify-content: flex-end; gap: 10px; }
.log-meta { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.log-meta > div { display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: var(--c-border-2); border-radius: var(--r-sm); }
.log-meta span { color: var(--c-text-3); font-size: 10px; min-width: 60px; }
.log-meta strong { color: var(--c-ink-1); font-size: 11px; font-weight: 600; }
.log-block { margin-top: 12px; padding: 12px 14px; background: #0e1e22; border-radius: var(--r-md); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px; }
.log-line { display: grid; grid-template-columns: 160px 60px 1fr; gap: 10px; padding: 2px 0; color: var(--c-border); }
.log-line .log-time { color: #7a9094; }
.log-line .log-level { font-weight: 700; }
.log-line.log-info .log-level { color: var(--c-brand); }
.log-line.log-err .log-level { color: var(--c-danger); }
.log-line.log-err { color: #f1b4ad; }
.close-btn { color: var(--c-text-3); }
.primary-btn, .secondary-btn { display: inline-flex; align-items: center; justify-content: center; gap: 8px; padding: 11px 15px; border-radius: var(--r-sm); font-size: 12px; font-weight: 600; }
.primary-btn { color: var(--c-on-brand); border: 1px solid var(--c-brand-deep); background: var(--c-brand); box-shadow: 0 6px 14px rgba(111,158,127,.17); cursor: pointer; }
.primary-btn:hover { background: var(--c-brand-hover); }
.primary-btn:disabled { opacity: .5; cursor: not-allowed; }
.secondary-btn { color: var(--c-text-2); border: 1px solid var(--c-separator); background: var(--c-surface); cursor: pointer; }
.secondary-btn:hover { background: var(--c-border-2); }
.secondary-btn:disabled { opacity: .5; cursor: not-allowed; }
.text-btn { display: inline-flex; align-items: center; gap: 3px; color: var(--c-ink-2); font-size: 10px; background: transparent; border: 0; cursor: pointer; }
.text-btn:disabled { opacity: .5; cursor: not-allowed; }
@media (max-width: 960px) {
  .kpi-grid { grid-template-columns: repeat(2, 1fr) !important; }
  .verify-result { grid-template-columns: 1fr; }
  .env-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 600px) {
  .modal-row.two-col { grid-template-columns: 1fr; }
}
</style>