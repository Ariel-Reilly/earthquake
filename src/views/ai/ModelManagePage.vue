<script setup>
import { ref, computed, onMounted } from 'vue'
import {
  Activity, AlertTriangle, Box, BrainCircuit, Cpu, Database, Gauge, GitBranch,
  Layers3, LineChart, PlayCircle, PauseCircle, RefreshCcw, RefreshCw, Save,
  Settings, Sparkles, Square, Terminal, Trash2, TrendingUp,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'
import {
  detectDrift,
  getDriftTrend,
  triggerRetrain,
  getRetrainStatus,
  getDriftLevelInfo,
} from '../../services/driftMonitorService'

const emit = defineEmits(['notify'])

// ==================== 顶部 KPI ====================
const kpiLoading = ref(true)
const kpiData = ref({ onlineCount: 0, todayInferences: 0, avgLatency: 0,综合F1: 0 })

// ==================== Tab 状态 ====================
const activeTab = ref('versions')

// ==================== Tab1: 模型版本库 ====================
const VERSIONS_COLS = ['versionId', 'family', 'trainSize', 'valF1', 'latencyMs', 'status', 'onlineAt', 'actions']
const FAMILY_OPTIONS = ['全部', 'XGBoost fusion', 'YOLO', 'LSTM', '综合研判', 'Device Health']
const STATUS_OPTIONS = ['全部', '在线', '灰度', '已下线']

const filterFamily = ref('全部')
const filterStatus = ref('全部')

const versionsLoading = ref(false)
const versionsList = ref([])

const versionDrawerOpen = ref(false)
const versionDrawerData = ref(null)
const drawerLoading = ref(false)

const FAMILY_LABEL = {
  'XGBoost fusion': 'XGBoost Fusion',
  'YOLO': 'YOLO',
  'LSTM': 'LSTM',
  '综合研判': '综合研判',
  'Device Health': 'Device Health',
}
const STATUS_LABEL = { '在线': '在线', '灰度': '灰度', '已下线': '已下线' }
const STATUS_CLASS = { '在线': 'status-success', '灰度': 'status-pending', '已下线': 'status-failed' }

const filteredVersions = computed(() => {
  return versionsList.value.filter((m) => {
    const famOk = filterFamily.value === '全部' || m.family === filterFamily.value
    const staOk = filterStatus.value === '全部' || m.status === filterStatus.value
    return famOk && staOk
  })
})

async function loadKpi() {
  kpiLoading.value = true
  try {
    const data = await dashboardApi.getModelFamilyStats()
    kpiData.value = {
      onlineCount: data.onlineCount ?? 0,
      todayInferences: data.todayInferences ?? 0,
      avgLatency: data.avgLatency ?? 0,
      综合F1: data.综合F1 ?? 0,
    }
  } catch {
    kpiData.value = { onlineCount: 0, todayInferences: 0, avgLatency: 0, 综合F1: 0 }
  } finally {
    kpiLoading.value = false
  }
}

async function loadVersions() {
  versionsLoading.value = true
  try {
    const data = await dashboardApi.listModels({})
    versionsList.value = Array.isArray(data?.records) ? data.records : Array.isArray(data) ? data : []
  } catch {
    versionsList.value = []
  } finally {
    versionsLoading.value = false
  }
}

async function openVersionDrawer(versionId) {
  versionDrawerOpen.value = true
  drawerLoading.value = true
  versionDrawerData.value = null
  try {
    const data = await dashboardApi.getModelDetail(versionId)
    versionDrawerData.value = data
  } catch {
    versionDrawerData.value = null
  } finally {
    drawerLoading.value = false
  }
}

function closeDrawer() {
  versionDrawerOpen.value = false
  versionDrawerData.value = null
}

async function activateVersion(versionId) {
  try {
    await dashboardApi.activateModelVersion(versionId)
    emit('notify', '模型已激活')
    await loadVersions()
    await loadKpi()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '激活失败')
  }
}

async function offlineVersion(versionId) {
  try {
    await dashboardApi.deleteModelVersion(versionId)
    emit('notify', '模型已下线')
    await loadVersions()
    await loadKpi()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '下线失败')
  }
}

// ==================== Tab2: 训练任务 ====================
const JOB_STATUS_OPTIONS = [
  { value: '全部', label: '全部' },
  { value: 'pending', label: '排队' },
  { value: 'running', label: '运行中' },
  { value: 'completed', label: '已完成' },
  { value: 'failed', label: '失败' },
]
const JOB_FAMILY_OPTIONS = ['全部', 'XGBoost fusion', 'YOLO', 'LSTM', '综合研判', 'Device Health']

const trainingJobs = ref([])
const jobsLoading = ref(false)

const showCreateDialog = ref(false)
const createLoading = ref(false)
const createForm = ref({
  family: 'XGBoost fusion',
  dataset: '',
  epochs: 50,
  lr: 0.001,
  batchSize: 32,
  remark: '',
})

const expandedJobId = ref(null)
const jobLogs = ref({})
const jobLogLoading = ref({})
const JOB_LOG_MAX = 300

async function loadTrainingJobs() {
  jobsLoading.value = true
  try {
    const data = await dashboardApi.getModelTrainingJobs()
    trainingJobs.value = Array.isArray(data) ? data : (data?.records ?? [])
  } catch {
    trainingJobs.value = []
  } finally {
    jobsLoading.value = false
  }
}

async function openCreateDialog() {
  showCreateDialog.value = true
  createForm.value = { family: 'XGBoost fusion', dataset: '', epochs: 50, lr: 0.001, batchSize: 32, remark: '' }
}

async function submitCreateJob() {
  createLoading.value = true
  try {
    await dashboardApi.createModelTrainingJob({ ...createForm.value })
    emit('notify', '训练任务已创建')
    showCreateDialog.value = false
    await loadTrainingJobs()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '创建失败')
  } finally {
    createLoading.value = false
  }
}

async function startJob(id) {
  try {
    await dashboardApi.startTrainingJob(id)
    emit('notify', '训练已启动')
    await loadTrainingJobs()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '启动失败')
  }
}

async function stopJob(id) {
  try {
    await dashboardApi.stopTrainingJob(id)
    emit('notify', '训练已停止')
    await loadTrainingJobs()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '停止失败')
  }
}

async function removeJob(id) {
  trainingJobs.value = trainingJobs.value.filter((j) => j.id !== id)
  delete jobLogs.value[id]
  emit('notify', '任务已删除')
}

async function toggleJobLog(id) {
  if (expandedJobId.value === id) {
    expandedJobId.value = null
    return
  }
  expandedJobId.value = id
  if (!jobLogs.value[id]) {
    await fetchJobLog(id)
  }
}

async function fetchJobLog(id) {
  jobLogLoading.value = { ...jobLogLoading.value, [id]: true }
  try {
    const data = await dashboardApi.getTrainingJobLog(id, {})
    const entries = Array.isArray(data?.entries) ? data.entries : []
    const existing = jobLogs.value[id] ?? []
    jobLogs.value = { ...jobLogs.value, [id]: [...existing, ...entries].slice(-JOB_LOG_MAX) }
  } catch {
    // 静默失败
  } finally {
    jobLogLoading.value = { ...jobLogLoading.value, [id]: false }
  }
}

// ==================== Mini Chart (纯 CSS/SVG) ====================
function buildMiniLine(dataPoints, color) {
  if (!dataPoints || dataPoints.length < 2) return ''
  const w = 280, h = 60, pad = 4
  const vals = dataPoints.map(Number)
  const minV = Math.min(...vals), maxV = Math.max(...vals)
  const range = maxV - minV || 1
  const coords = vals.map((v, i) => {
    const x = pad + (i / (vals.length - 1)) * (w - pad * 2)
    const y = h - pad - ((v - minV) / range) * (h - pad * 2)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  return `<polyline points="${coords.join(' ')}" fill="none" stroke="${color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/><circle cx="${coords[coords.length - 1].split(',')[0]}" cy="${coords[coords.length - 1].split(',')[1]}" r="3" fill="${color}"/>`
}

function buildFeatureBars(features) {
  if (!features || features.length === 0) return []
  const maxVal = Math.max(...features.map((f) => Math.abs(Number(f.value) || 0)))
  return features.slice(0, 8).map((f) => ({
    name: f.name || f.feature || '',
    value: f.value ?? f.score ?? 0,
    pct: maxVal > 0 ? (Math.abs(Number(f.value) || 0) / maxVal) * 100 : 0,
  }))
}

function formatDate(iso) {
  if (!iso) return '—'
  try { return new Date(iso).toLocaleString('zh-CN', { hour12: false }) } catch (_) { return '—' }
}

function fmtNum(n) {
  return Number.isNaN(n) ? '—' : Number(n).toFixed(2)
}

const FAMILY_COLOR = {
  'XGBoost fusion': '#6dbfa5',
  'YOLO': '#4f8f99',
  'LSTM': '#d9c040',
  '综合研判': '#e0a050',
  'Device Health': '#7a8d89',
}
const JOB_COLOR = {
  pending: '#d9c040',
  running: '#4f8f99',
  completed: '#6dbfa5',
  failed: '#e06060',
}
const JOB_LABEL = {
  pending: '排队',
  running: '运行中',
  completed: '已完成',
  failed: '失败',
}

// ==================== Tab3: 漂移监控 ====================
const driftModels = ref([
  { id: 'xgboost_fusion', name: 'XGBoost 融合模型', version: 'v2.3', family: 'XGBoost fusion' },
  { id: 'lstm_forecast', name: 'LSTM 时序预测', version: 'v1.5', family: 'LSTM' },
  { id: 'device_health', name: '设备健康模型', version: 'v1.2', family: 'Device Health' },
])
const selectedDriftModel = ref(null)
const driftResult = ref(null)
const driftTrend = ref([])
const driftLoading = ref(false)
const retrainJobs = ref([])
const retrainLoading = ref(false)

async function loadDriftMonitor() {
  driftLoading.value = true
  try {
    if (selectedDriftModel.value) {
      const [drift, trend] = await Promise.all([
        detectDrift({ modelId: selectedDriftModel.value.id, currentData: [] }),
        getDriftTrend(selectedDriftModel.value.id, { days: 30 }),
      ])
      driftResult.value = drift
      driftTrend.value = trend.trend || []
    }
  } catch (e) {
    // 使用模拟数据
    driftResult.value = {
      modelId: selectedDriftModel.value?.id || 'xgboost_fusion',
      overallStatus: 'SLIGHT',
      features: {
        rainfall_24h: { psi: 0.12, ks: 0.08, level: 'SLIGHT' },
        displacement_rate: { psi: 0.28, ks: 0.15, level: 'SIGNIFICANT' },
        soil_moisture: { psi: 0.05, ks: 0.03, level: 'NORMAL' },
        slope: { psi: 0.02, ks: 0.01, level: 'NORMAL' },
      },
      recommendation: '位移速率特征显著漂移，建议重训模型',
    }
    driftTrend.value = Array.from({ length: 30 }, (_, i) => ({
      timestamp: new Date(Date.now() - i * 86400000).toISOString(),
      psi: 0.05 + (i % 5) * 0.02 + Math.random() * 0.03,
    })).reverse()
  } finally {
    driftLoading.value = false
  }
}

async function handleRetrain() {
  if (!selectedDriftModel.value) return
  retrainLoading.value = true
  try {
    const result = await triggerRetrain({
      modelId: selectedDriftModel.value.id,
      reason: driftResult.value?.recommendation || '模型漂移检测触发',
    })
    retrainJobs.value.unshift({
      id: result.jobId,
      modelId: selectedDriftModel.value.id,
      modelName: selectedDriftModel.value.name,
      status: 'RUNNING',
      progress: 0,
      startedAt: new Date().toISOString(),
    })
    emit('notify', '重训任务已创建')
  } catch (e) {
    // 模拟添加
    retrainJobs.value.unshift({
      id: `retrain-${Date.now()}`,
      modelId: selectedDriftModel.value.id,
      modelName: selectedDriftModel.value.name,
      status: 'RUNNING',
      progress: 0,
      startedAt: new Date().toISOString(),
    })
    emit('notify', '重训任务已创建（模拟）')
  } finally {
    retrainLoading.value = false
  }
}

function selectDriftModel(model) {
  selectedDriftModel.value = model
  loadDriftMonitor()
}

function buildDriftMiniLine() {
  if (!driftTrend.value || driftTrend.value.length < 2) return ''
  const w = 280, h = 50, pad = 4
  const vals = driftTrend.value.map(t => t.psi || t.value || 0)
  const minV = Math.min(...vals), maxV = Math.max(...vals)
  const range = maxV - minV || 1
  const coords = vals.map((v, i) => {
    const x = pad + (i / (vals.length - 1)) * (w - pad * 2)
    const y = h - pad - ((v - minV) / range) * (h - pad * 2)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  const color = driftResult.value?.overallStatus === 'SIGNIFICANT' ? '#e06060' 
    : driftResult.value?.overallStatus === 'SLIGHT' ? '#d18f2f' : '#38a169'
  return `<polyline points="${coords.join(' ')}" fill="none" stroke="${color}" stroke-width="2" stroke-linejoin="round"/><circle cx="${coords[coords.length - 1].split(',')[0]}" cy="${coords[coords.length - 1].split(',')[1]}" r="3" fill="${color}"/>`
}

// Initialize
if (driftModels.value.length > 0) {
  selectedDriftModel.value = driftModels.value[0]
  loadDriftMonitor()
}

onMounted(async () => {
  await Promise.all([loadKpi(), loadVersions(), loadTrainingJobs()])
})
</script>

<template>
  <div class="model-manage-page">
    <!-- 页面标题 -->
    <div class="page-eyebrow">
      <div>
        <h1>AI 模型管理</h1>
        <p>模型版本库、训练任务与运行时监控</p>
      </div>
    </div>

    <!-- KPI 区域 -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-icon" style="background:#eff8f5;color:#6dbfa5"><Box :size="20" /></div>
        <div class="stat-body">
          <strong>{{ kpiLoading ? '—' : kpiData.onlineCount }}</strong>
          <span>在线模型数</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background:#f5f0f9;color:#4f8f99"><Activity :size="20" /></div>
        <div class="stat-body">
          <strong>{{ kpiLoading ? '—' : kpiData.todayInferences.toLocaleString() }}</strong>
          <span>总推理次数（今日）</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background:#faf5ee;color:#e0a050"><Gauge :size="20" /></div>
        <div class="stat-body">
          <strong>{{ kpiLoading ? '—' : kpiData.avgLatency + ' ms' }}</strong>
          <span>平均推理时延</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background:#f5f8ee;color:#d9c040"><Sparkles :size="20" /></div>
        <div class="stat-body">
          <strong>{{ kpiLoading ? '—' : kpiData.综合F1 }}</strong>
          <span>综合 F1 评分</span>
        </div>
      </div>
    </div>

    <!-- Tab 切换 -->
    <div class="tab-bar">
      <button :class="{ active: activeTab === 'versions' }" @click="activeTab = 'versions'">
        <Layers3 :size="15" />模型版本库
      </button>
      <button :class="{ active: activeTab === 'training' }" @click="activeTab = 'training'">
        <BrainCircuit :size="15" />训练任务
      </button>
      <button :class="{ active: activeTab === 'drift' }" @click="activeTab = 'drift'; loadDriftMonitor()">
        <TrendingUp :size="15" />漂移监控
      </button>
    </div>

    <!-- ===== Tab1: 模型版本库 ===== -->
    <div v-if="activeTab === 'versions'" class="tab-content">
      <!-- 过滤条 -->
      <div class="filter-bar">
        <div class="filter-group">
          <label>模型族</label>
          <select v-model="filterFamily">
            <option v-for="f in FAMILY_OPTIONS" :key="f" :value="f">{{ f }}</option>
          </select>
        </div>
        <div class="filter-group">
          <label>状态</label>
          <select v-model="filterStatus">
            <option v-for="s in STATUS_OPTIONS" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>
        <div class="filter-spacer"></div>
        <button class="text-btn" :disabled="versionsLoading" @click="loadVersions">
          <RefreshCcw :size="14" />刷新
        </button>
      </div>

      <!-- 表格 -->
      <div class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2>模型版本列表</h2>
            <p>共 {{ filteredVersions.length }} 个版本</p>
          </div>
        </div>
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>版本号</th>
                <th>模型族</th>
                <th>训练集</th>
                <th>验证集 F1</th>
                <th>推理时延 (ms)</th>
                <th>部署状态</th>
                <th>上线时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="versionsLoading">
                <td colspan="8" class="table-empty">加载中...</td>
              </tr>
              <tr v-else-if="!filteredVersions.length">
                <td colspan="8" class="table-empty">暂无数据</td>
              </tr>
              <tr v-for="m in filteredVersions" :key="m.versionId" class="table-row-hover" @click="openVersionDrawer(m.versionId)">
                <td><code>{{ m.versionId }}</code></td>
                <td>
                  <span class="family-badge" :style="{ color: FAMILY_COLOR[m.family] || '#4f8f99' }">
                    {{ FAMILY_LABEL[m.family] || m.family }}
                  </span>
                </td>
                <td>{{ m.trainSize != null ? m.trainSize.toLocaleString() : '—' }}</td>
                <td>
                  <span v-if="m.valF1 != null" class="f1-chip">{{ Number(m.valF1).toFixed(4) }}</span>
                  <span v-else>—</span>
                </td>
                <td>{{ m.latencyMs != null ? m.latencyMs + ' ms' : '—' }}</td>
                <td>
                  <span class="status-badge" :class="STATUS_CLASS[m.status] || ''">
                    {{ STATUS_LABEL[m.status] || m.status || '—' }}
                  </span>
                </td>
                <td>{{ formatDate(m.onlineAt) }}</td>
                <td @click.stop>
                  <div class="row-actions">
                    <button class="icon-btn" title="查看" @click="openVersionDrawer(m.versionId)">
                      <Settings :size="14" />
                    </button>
                    <PermissionButton permission="ai:model:edit" variant="secondary" @click="async ({ allowed, reason }) => {
                      if (!allowed) return emit('notify', reason)
                      await activateVersion(m.versionId)
                    }">
                      激活
                    </PermissionButton>
                    <PermissionButton permission="ai:model:edit" variant="secondary" @click="async ({ allowed, reason }) => {
                      if (!allowed) return emit('notify', reason)
                      await offlineVersion(m.versionId)
                    }">
                      下线
                    </PermissionButton>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- ===== Tab2: 训练任务 ===== -->
    <div v-if="activeTab === 'training'" class="tab-content">
      <div class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2>训练任务</h2>
            <p>共 {{ trainingJobs.length }} 个训练任务</p>
          </div>
          <div class="heading-actions">
            <button class="primary-btn" @click="openCreateDialog">
              <Sparkles :size="14" />新建训练
            </button>
          </div>
        </div>

        <!-- 任务表格 -->
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>任务 ID</th>
                <th>模型族</th>
                <th>数据集</th>
                <th>Epochs</th>
                <th>进度</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <template v-if="jobsLoading">
                <tr><td colspan="7" class="table-empty">加载中...</td></tr>
              </template>
              <template v-else-if="!trainingJobs.length">
                <tr><td colspan="7" class="table-empty">暂无训练任务</td></tr>
              </template>
              <template v-else>
                <template v-for="job in trainingJobs" :key="job.id">
                  <tr class="table-row-hover" @click="toggleJobLog(job.id)">
                    <td><code>{{ job.id }}</code></td>
                    <td>
                      <span class="family-badge" :style="{ color: FAMILY_COLOR[job.family] || '#4f8f99' }">
                        {{ FAMILY_LABEL[job.family] || job.family }}
                      </span>
                    </td>
                    <td>{{ job.dataset || '—' }}</td>
                    <td>{{ job.epochs ?? '—' }}</td>
                    <td>
                      <div class="progress-cell">
                        <div class="pie-track">
                          <i :style="{
                            width: (job.progress ?? 0) + '%',
                            background: JOB_COLOR[job.status] || '#4f8f99'
                          }"></i>
                        </div>
                        <span class="progress-label">{{ job.progress ?? 0 }}%</span>
                      </div>
                    </td>
                    <td>
                      <span class="status-badge" :class="`status-${job.status || 'pending'}`">
                        {{ JOB_LABEL[job.status] || job.status || '—' }}
                      </span>
                    </td>
                    <td @click.stop>
                      <div class="row-actions">
                        <button
                          v-if="job.status === 'pending' || job.status === 'paused'"
                          class="icon-btn green" title="启动"
                          :disabled="job.status === 'running'"
                          @click="startJob(job.id)"
                        >
                          <PlayCircle :size="14" />
                        </button>
                        <button
                          v-if="job.status === 'running'"
                          class="icon-btn yellow" title="停止"
                          @click="stopJob(job.id)"
                        >
                          <PauseCircle :size="14" />
                        </button>
                        <button class="icon-btn" title="查看日志" @click="toggleJobLog(job.id)">
                          <Terminal :size="14" />
                        </button>
                        <button
                          class="icon-btn red" title="删除"
                          @click="removeJob(job.id)"
                        >
                          <Trash2 :size="14" />
                        </button>
                      </div>
                    </td>
                  </tr>
                  <!-- 展开的日志面板 -->
                  <tr v-if="expandedJobId === job.id" class="log-expand-row">
                    <td colspan="7">
                      <div class="log-panel">
                        <div class="log-panel-header">
                          <span>训练日志 <small>{{ job.id }}</small></span>
                          <div class="log-panel-actions">
                            <span v-if="jobLogLoading[job.id]" class="log-loading-hint">加载中...</span>
                            <button class="text-btn" @click="fetchJobLog(job.id)">
                              <RefreshCcw :size="12" />刷新
                            </button>
                          </div>
                        </div>
                        <div class="log-scroller-mini">
                          <div v-if="!jobLogs[job.id]?.length" class="log-empty-mini">
                            暂无日志记录
                          </div>
                          <div
                            v-for="(line, lidx) in jobLogs[job.id]" :key="`${line.ts}-${lidx}`"
                            class="log-line"
                            :class="`log-${(line.level || 'info').toLowerCase()}`"
                          >
                            <span class="log-time">[{{ line.ts || '—' }}]</span>
                            <span class="log-level">{{ line.level || 'INFO' }}</span>
                            <span class="log-msg">{{ line.message }}</span>
                          </div>
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

    <!-- ===== 模型详情抽屉 ===== -->
    <Teleport to="body">
      <div v-if="versionDrawerOpen" class="drawer-overlay" @click.self="closeDrawer">
        <div class="drawer-panel">
          <div class="drawer-header">
            <div>
              <h3>模型详情</h3>
              <p v-if="versionDrawerData">{{ versionDrawerData.versionId }}</p>
            </div>
            <button class="icon-btn close-btn" @click="closeDrawer"><Square :size="16" /></button>
          </div>

          <div v-if="drawerLoading" class="drawer-body">
            <div class="loading-row">加载中...</div>
          </div>
          <div v-else-if="!versionDrawerData" class="drawer-body">
            <div class="loading-row">暂无数据</div>
          </div>
          <div v-else class="drawer-body">
            <!-- 元数据卡片 -->
            <div class="drawer-section">
              <h4><Database :size="14" />元数据</h4>
              <div class="meta-grid">
                <div class="meta-item"><span>版本号</span><strong>{{ versionDrawerData.versionId }}</strong></div>
                <div class="meta-item"><span>模型族</span><strong>{{ FAMILY_LABEL[versionDrawerData.family] || versionDrawerData.family }}</strong></div>
                <div class="meta-item"><span>模型路径</span><strong class="path-text">{{ versionDrawerData.path || '—' }}</strong></div>
                <div class="meta-item"><span>训练完成</span><strong>{{ formatDate(versionDrawerData.finishedAt) }}</strong></div>
                <div class="meta-item"><span>训练集大小</span><strong>{{ versionDrawerData.trainSize != null ? versionDrawerData.trainSize.toLocaleString() : '—' }}</strong></div>
                <div class="meta-item"><span>验证集大小</span><strong>{{ versionDrawerData.valSize != null ? versionDrawerData.valSize.toLocaleString() : '—' }}</strong></div>
              </div>
            </div>

            <!-- 评估指标卡 -->
            <div class="drawer-section">
              <h4><LineChart :size="14" />评估指标</h4>
              <div class="eval-grid">
                <div class="eval-card">
                  <small>Precision</small>
                  <strong>{{ fmtNum(versionDrawerData.precision) }}</strong>
                </div>
                <div class="eval-card">
                  <small>Recall</small>
                  <strong>{{ fmtNum(versionDrawerData.recall) }}</strong>
                </div>
                <div class="eval-card">
                  <small>F1 Score</small>
                  <strong>{{ fmtNum(versionDrawerData.f1Score) }}</strong>
                </div>
                <div class="eval-card">
                  <small>AUC</small>
                  <strong>{{ fmtNum(versionDrawerData.auc) }}</strong>
                </div>
              </div>
            </div>

            <!-- 特征重要性 -->
            <div class="drawer-section">
              <h4><Cpu :size="14" />特征重要性 (Top 8)</h4>
              <div class="feature-bars">
                <div v-for="bar in buildFeatureBars(versionDrawerData.features)" :key="bar.name" class="feature-row">
                  <span class="feature-name">{{ bar.name }}</span>
                  <div class="feature-track">
                    <i :style="{ width: bar.pct + '%', background: FAMILY_COLOR[versionDrawerData.family] || '#4f8f99' }"></i>
                  </div>
                  <span class="feature-val">{{ Number(bar.value).toFixed(4) }}</span>
                </div>
                <div v-if="!buildFeatureBars(versionDrawerData.features).length" class="chart-empty-mini">
                  暂无特征数据
                </div>
              </div>
            </div>

            <!-- 训练 / 验证 / 测试曲线 -->
            <div class="drawer-section">
              <h4><LineChart :size="14" />训练曲线</h4>
              <div class="curves-grid">
                <div class="curve-card">
                  <span>训练集损失</span>
                  <svg viewBox="0 0 280 60" class="mini-svg" v-html="buildMiniLine(versionDrawerData.trainLoss, '#4f8f99')"></svg>
                </div>
                <div class="curve-card">
                  <span>验证集损失</span>
                  <svg viewBox="0 0 280 60" class="mini-svg" v-html="buildMiniLine(versionDrawerData.valLoss, '#6dbfa5')"></svg>
                </div>
                <div class="curve-card">
                  <span>测试集指标</span>
                  <svg viewBox="0 0 280 60" class="mini-svg" v-html="buildMiniLine(versionDrawerData.testMetric, '#d9c040')"></svg>
                </div>
              </div>
            </div>

            <!-- 激活按钮 -->
            <div class="drawer-footer">
              <PermissionButton
                permission="ai:model:edit"
                variant="primary"
                @click="async ({ allowed, reason }) => {
                  if (!allowed) return emit('notify', reason)
                  await activateVersion(versionDrawerData.versionId)
                  closeDrawer()
                }"
              >
                <Save :size="14" />切换为当前激活模型
              </PermissionButton>
            </div>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ===== Tab3: 漂移监控 ===== -->
    <div v-if="activeTab === 'drift'" class="tab-content">
      <div class="drift-layout">
        <!-- 左侧：模型选择列表 -->
        <div class="drift-model-list">
          <div class="panel-header compact">
            <h2>监控模型</h2>
            <p>选择要监控的模型</p>
          </div>
          <div class="model-items">
            <button
              v-for="model in driftModels"
              :key="model.id"
              class="model-item"
              :class="{ active: selectedDriftModel?.id === model.id }"
              @click="selectDriftModel(model)"
            >
              <div class="model-item-info">
                <strong>{{ model.name }}</strong>
                <small>{{ model.version }} · {{ model.family }}</small>
              </div>
            </button>
          </div>
        </div>

        <!-- 右侧：漂移详情 -->
        <div class="drift-detail">
          <template v-if="selectedDriftModel && driftResult">
            <!-- 状态卡片 -->
            <div class="drift-status-card" :class="`level-${driftResult.overallStatus?.toLowerCase() || 'normal'}`">
              <div class="drift-status-header">
                <AlertTriangle :size="20" />
                <span>模型漂移监控</span>
              </div>
              <div class="drift-model-info">
                <strong>{{ selectedDriftModel.name }}</strong>
                <span>{{ selectedDriftModel.version }}</span>
              </div>
              <div class="drift-status-badge" :class="`status-${driftResult.overallStatus?.toLowerCase() || 'normal'}`">
                {{ getDriftLevelInfo(driftResult.overallStatus)?.label || driftResult.overallStatus || '未知' }}
              </div>
            </div>

            <!-- 特征漂移列表 -->
            <div class="panel system-panel">
              <div class="panel-header compact">
                <div>
                  <h2>特征漂移详情</h2>
                  <p>各输入特征的分布漂移程度</p>
                </div>
                <button class="text-btn" :disabled="driftLoading" @click="loadDriftMonitor">
                  <RefreshCw :size="14" />刷新
                </button>
              </div>
              <div class="drift-features">
                <div
                  v-for="(feat, name) in driftResult.features"
                  :key="name"
                  class="drift-feature-item"
                  :class="`level-${feat.level?.toLowerCase() || 'normal'}`"
                >
                  <span class="feat-name">{{ name }}</span>
                  <div class="feat-psi-wrap">
                    <div class="feat-psi-bar" :style="{ width: Math.min(feat.psi * 100 / 0.3, 100) + '%' }"></div>
                  </div>
                  <span class="feat-psi-val">PSI={{ feat.psi?.toFixed(3) || '—' }}</span>
                  <span class="feat-level" :class="`level-${feat.level?.toLowerCase() || 'normal'}`">
                    {{ getDriftLevelInfo(feat.level)?.label || feat.level || '未知' }}
                  </span>
                </div>
              </div>
            </div>

            <!-- 漂移趋势图 -->
            <div class="panel system-panel">
              <div class="panel-header compact">
                <div>
                  <h2>漂移趋势（近30天）</h2>
                  <p>PSI 指标随时间变化</p>
                </div>
              </div>
              <div class="drift-trend-chart">
                <svg viewBox="0 0 280 50" class="mini-svg" v-html="buildDriftMiniLine()"></svg>
              </div>
            </div>

            <!-- 建议动作 -->
            <div class="drift-recommendation">
              <div class="recommendation-header">
                <AlertTriangle :size="16" />
                <h3>建议动作</h3>
              </div>
              <p>{{ driftResult.recommendation || '暂无建议' }}</p>
              <div class="recommendation-actions">
                <button class="primary-btn" :disabled="retrainLoading || driftResult.overallStatus === 'NORMAL'" @click="handleRetrain">
                  <Sparkles :size="14" />
                  {{ retrainLoading ? '创建中...' : '一键重训' }}
                </button>
                <button class="ghost-btn" :disabled="driftLoading" @click="loadDriftMonitor">
                  <RefreshCw :size="14" />忽略本次
                </button>
              </div>
            </div>

            <!-- 重训记录 -->
            <div v-if="retrainJobs.length > 0" class="panel system-panel">
              <div class="panel-header compact">
                <h2>最近重训记录</h2>
              </div>
              <div class="retrain-list">
                <div v-for="job in retrainJobs.slice(0, 5)" :key="job.id" class="retrain-item">
                  <div class="retrain-info">
                    <strong>{{ job.modelName }}</strong>
                    <small>{{ job.id }}</small>
                  </div>
                  <div class="retrain-progress">
                    <div class="progress-bar">
                      <div class="progress-fill" :style="{ width: job.progress + '%' }"></div>
                    </div>
                    <span>{{ job.progress }}%</span>
                  </div>
                  <span class="retrain-status" :class="`status-${job.status?.toLowerCase()}`">
                    {{ JOB_LABEL[job.status?.toLowerCase()] || job.status }}
                  </span>
                </div>
              </div>
            </div>
          </template>

          <div v-else class="drift-empty">
            <TrendingUp :size="32" />
            <p>请从左侧选择一个模型查看漂移监控详情</p>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== 新建训练弹窗 ===== -->
    <Teleport to="body">
      <div v-if="showCreateDialog" class="modal-mask" @click.self="showCreateDialog = false">
        <div class="modal">
          <div class="modal-head">
            <div>
              <strong>新建训练任务</strong>
              <p>配置模型族、数据集与超参数</p>
            </div>
            <button class="icon-btn close-btn" @click="showCreateDialog = false"><Square :size="15" /></button>
          </div>
          <div class="modal-body">
            <div class="modal-row">
              <label>模型族</label>
              <select v-model="createForm.family">
                <option v-for="f in JOB_FAMILY_OPTIONS.filter(f => f !== '全部')" :key="f" :value="f">{{ f }}</option>
              </select>
            </div>
            <div class="modal-row">
              <label>数据集</label>
              <select v-model="createForm.dataset">
                <option value="">请选择数据集</option>
                <option value="seismic_standard">地震标准数据集</option>
                <option value="seismic_extended">地震扩展数据集</option>
                <option value="seismic_sentinel">遥感影像数据集</option>
                <option value="device_normal">设备正常数据</option>
                <option value="device_anomaly">设备异常数据</option>
              </select>
            </div>
            <div class="modal-row three-col">
              <div>
                <label>Epochs</label>
                <input v-model.number="createForm.epochs" type="number" min="1" max="1000" />
              </div>
              <div>
                <label>学习率</label>
                <input v-model.number="createForm.lr" type="number" min="0" max="1" step="0.0001" />
              </div>
              <div>
                <label>Batch Size</label>
                <input v-model.number="createForm.batchSize" type="number" min="1" max="512" />
              </div>
            </div>
            <div class="modal-row">
              <label>备注</label>
              <input v-model="createForm.remark" placeholder="可选，描述本次训练的用途" />
            </div>
          </div>
          <div class="modal-foot">
            <button class="secondary-btn" @click="showCreateDialog = false">取消</button>
            <button class="primary-btn" :disabled="createLoading" @click="submitCreateJob">
              <Sparkles v-if="!createLoading" :size="13" />
              {{ createLoading ? '创建中...' : '创建任务' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/* ---- 页面框架 ---- */
<style scoped>
.model-manage-page { padding: 0 30px 40px; min-height: 100%; }
.stat-icon { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 12px; flex: 0 0 auto; }
.stat-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.stat-body strong { display: block; color: #24494d; font-size: 22px; font-weight: 700; line-height: 1; }
.stat-body span { display: block; color: #8aa3a0; font-size: 10px; }
.tab-bar { display: flex; gap: 4px; margin-bottom: 16px; border-bottom: 1px solid #e3ebe7; }
.tab-bar button { display: inline-flex; align-items: center; gap: 6px; padding: 9px 18px; color: #8aa3a0; border: 0; border-bottom: 2px solid transparent; border-radius: 0; background: transparent; font-size: 12px; font-weight: 500; cursor: pointer; transition: color .2s, border-color .2s; margin-bottom: -1px; }
.tab-bar button:hover { color: #4f8f99; }
.tab-bar button.active { color: #2f7f80; border-bottom-color: #2f7f80; }
.filter-bar { display: flex; align-items: center; gap: 14px; padding: 12px 16px; margin-bottom: 12px; background: #fff; border: 1px solid #e3ebe7; border-radius: 12px; }
.filter-group { display: flex; align-items: center; gap: 8px; }
.filter-group label { color: #8aa3a0; font-size: 10px; white-space: nowrap; }
.filter-group select { padding: 6px 10px; border: 1px solid #dbe6e2; border-radius: 8px; background: #fbfdfc; color: #36585b; font-size: 11px; outline: none; }
.filter-spacer { flex: 1; }
.table-scroll { overflow-x: auto; }
.data-table { width: 100%; border-collapse: collapse; font-size: 11px; }
.data-table th { padding: 10px 14px; color: #8aa3a0; font-size: 10px; font-weight: 600; text-align: left; background: #f7faf9; border-bottom: 1px solid #e3ebe7; white-space: nowrap; }
.data-table td { padding: 11px 14px; color: #36585b; border-bottom: 1px solid #edf2f0; vertical-align: middle; }
.data-table td:first-child { color: #2f7f80; }
.table-row-hover { cursor: pointer; transition: background .15s; }
.table-row-hover:hover td { background: #f7faf9; }
.table-empty { text-align: center; color: #a4b3b0; padding: 24px 0; font-size: 11px; }
code { display: inline-block; padding: 2px 7px; background: #f0f7f5; border-radius: 6px; font-size: 10px; color: #2f7f80; }
.family-badge { display: inline-block; font-weight: 600; font-size: 11px; }
.f1-chip { display: inline-block; padding: 2px 8px; background: #f5f8ee; border-radius: 6px; color: #6dbfa5; font-size: 10px; font-weight: 600; }
.status-badge { display: inline-block; padding: 3px 10px; border-radius: 999px; font-size: 10px; font-weight: 600; }
.status-success { background: #eef8f4; color: #6dbfa5; }
.status-pending { background: #faf5e4; color: #d9c040; }
.status-failed  { background: #fdeeed; color: #e06060; }
.row-actions { display: flex; align-items: center; gap: 5px; }
.icon-btn { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: 1px solid #dbe6e2; border-radius: 7px; background: #fff; color: #6a8780; cursor: pointer; transition: border-color .15s, color .15s; }
.icon-btn:hover { border-color: #2f7f80; color: #2f7f80; }
.icon-btn.green { color: #6dbfa5; border-color: #c5e5d9; }
.icon-btn.green:hover { background: #f0faf7; }
.icon-btn.yellow { color: #d9c040; border-color: #ede9c4; }
.icon-btn.yellow:hover { background: #faf8e8; }
.icon-btn.red { color: #e06060; border-color: #f0c8c4; }
.icon-btn.red:hover { background: #fdeeed; }
.icon-btn:disabled { opacity: .4; cursor: not-allowed; }
.progress-cell { display: flex; align-items: center; gap: 8px; }
.progress-cell .pie-track { flex: 1; min-width: 60px; }
.progress-label { font-size: 10px; color: #8aa3a0; min-width: 30px; text-align: right; }
.log-expand-row > td { padding: 0; background: #0e1e22; }
.log-panel { margin: 8px 16px 12px; border-radius: 10px; overflow: hidden; }
.log-panel-header { display: flex; align-items: center; justify-content: space-between; padding: 8px 12px; background: #163038; border-radius: 10px 10px 0 0; font-size: 11px; color: #97aead; }
.log-panel-header strong { color: #dce8e4; }
.log-panel-header small { margin-left: 6px; color: #6c8084; }
.log-panel-actions { display: flex; align-items: center; gap: 8px; }
.log-loading-hint { color: #4f8f99; font-size: 10px; }
.log-scroller-mini { max-height: 220px; overflow-y: auto; padding: 10px 12px; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 10px; line-height: 1.6; }
.log-scroller-mini::-webkit-scrollbar { width: 5px; }
.log-scroller-mini::-webkit-scrollbar-thumb { background: rgba(255,255,255,.12); border-radius: 3px; }
.log-empty-mini { color: #6c8084; text-align: center; padding: 16px; }
.log-line { display: grid; grid-template-columns: 90px 50px 1fr; gap: 8px; padding: 1px 0; white-space: pre-wrap; word-break: break-all; }
.log-time { color: #7a9094; }
.log-level { font-weight: 700; }
.log-msg { color: #cfd8d8; }
.log-line.log-info .log-level { color: #4f8f99; }
.log-line.log-warn .log-level { color: #e0a050; }
.log-line.log-warn .log-msg { color: #f0d8b3; }
.log-line.log-err .log-level { color: #e06060; }
.log-line.log-err .log-msg { color: #f1b4ad; }
.drawer-overlay { position: fixed; inset: 0; z-index: 40; background: rgba(8, 38, 42, .3); backdrop-filter: blur(2px); display: flex; justify-content: flex-end; }
.drawer-panel { width: min(480px, 100vw); height: 100%; background: #fff; display: flex; flex-direction: column; box-shadow: -8px 0 30px rgba(20, 60, 56, .15); }
.drawer-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px; border-bottom: 1px solid #e3ebe7; }
.drawer-header h3 { margin: 0; color: #24494d; font-size: 15px; }
.drawer-header p { margin: 3px 0 0; color: #8aa3a0; font-size: 10px; }
.drawer-body { flex: 1; overflow-y: auto; padding: 18px 20px; }
.drawer-body::-webkit-scrollbar { width: 5px; }
.drawer-body::-webkit-scrollbar-thumb { background: #dbe6e2; border-radius: 3px; }
.loading-row { color: #8aa3a0; font-size: 11px; text-align: center; padding: 24px; }
.drawer-section { margin-bottom: 22px; }
.drawer-section h4 { display: flex; align-items: center; gap: 6px; margin: 0 0 10px; color: #2a4d52; font-size: 12px; font-weight: 600; }
.meta-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.meta-item { display: flex; flex-direction: column; gap: 3px; padding: 10px 12px; background: #f7faf9; border: 1px solid #edf2f0; border-radius: 9px; }
.meta-item span { color: #8aa3a0; font-size: 9px; }
.meta-item strong { color: #36585b; font-size: 11px; font-weight: 600; }
.path-text { font-size: 10px !important; word-break: break-all; }
.eval-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.eval-card { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 12px 8px; border: 1px solid #e3ebe7; border-radius: 10px; background: linear-gradient(180deg, #fff, #f8fbfa); text-align: center; }
.eval-card small { color: #8aa3a0; font-size: 9px; }
.eval-card strong { color: #24494d; font-size: 18px; font-weight: 700; }
.feature-bars { display: flex; flex-direction: column; gap: 8px; }
.feature-row { display: flex; align-items: center; gap: 8px; }
.feature-name { flex: 0 0 90px; color: #5b7769; font-size: 10px; text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.feature-track { flex: 1; height: 8px; background: #edf3f0; border-radius: 999px; overflow: hidden; }
.feature-track i { display: block; height: 100%; border-radius: inherit; transition: width .4s ease; }
.feature-val { flex: 0 0 50px; color: #8aa3a0; font-size: 9px; text-align: right; }
.chart-empty-mini { color: #a4b3b0; font-size: 10px; text-align: center; padding: 12px; }
.curves-grid { display: grid; grid-template-columns: 1fr; gap: 10px; }
.curve-card { padding: 12px; border: 1px solid #e3ebe7; border-radius: 10px; background: linear-gradient(180deg, #fff, #f8fbfa); }
.curve-card span { display: block; margin-bottom: 6px; color: #8aa3a0; font-size: 9px; }
.mini-svg { width: 100%; height: 60px; overflow: visible; }
.drawer-footer { padding: 16px 0 8px; border-top: 1px solid #edf2f0; display: flex; justify-content: flex-end; }
.modal-mask { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; background: rgba(8, 38, 42, .35); backdrop-filter: blur(3px); }
.modal { width: min(500px, 92vw); border: 1px solid #e3ebe7; border-radius: 14px; background: #fff; box-shadow: 0 24px 60px rgba(20, 60, 56, .2); }
.modal-head { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px; border-bottom: 1px solid #edf2f0; }
.modal-head strong { color: #24494d; font-size: 14px; }
.modal-head p { margin: 3px 0 0; color: #8aa3a0; font-size: 10px; }
.modal-body { padding: 16px 20px; display: flex; flex-direction: column; gap: 12px; }
.modal-row { display: flex; flex-direction: column; gap: 5px; }
.modal-row label { color: #5e7675; font-size: 10px; font-weight: 600; }
.modal-row input, .modal-row select { width: 100%; padding: 9px 12px; color: #36585b; border: 1px solid #dfeae6; border-radius: 8px; outline: none; background: #fbfdfc; font-size: 12px; transition: border-color .2s; }
.modal-row input:focus, .modal-row select:focus { border-color: #2f7f80; }
.modal-row.three-col { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.modal-foot { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 20px; border-top: 1px solid #edf2f0; }
@media (max-width: 900px) { .eval-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 640px) {
  .model-manage-page { padding: 0 14px 30px; }
  .stats-grid { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .filter-bar { flex-wrap: wrap; }
  .data-table { font-size: 10px; }
  .meta-grid { grid-template-columns: 1fr; }
  .modal-row.three-col { grid-template-columns: 1fr; }
  .eval-grid { grid-template-columns: repeat(2, 1fr); }
}

/* ===== 漂移监控样式 ===== */
.drift-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
  min-height: 400px;
}

.drift-model-list {
  background: #fff;
  border: 1px solid #e2ebe8;
  border-radius: 12px;
  overflow: hidden;
}

.drift-model-list .panel-header {
  padding: 14px 16px;
  border-bottom: 1px solid #f0f5f3;
  background: #fafdfc;
}

.model-items {
  padding: 8px;
}

.model-item {
  display: block;
  width: 100%;
  padding: 12px 14px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: all 0.15s;
  margin-bottom: 4px;
}

.model-item:hover {
  background: #f6faf9;
}

.model-item.active {
  background: #e8f5f1;
  border-color: #b6d8d2;
}

.model-item-info strong {
  display: block;
  color: #24494d;
  font-size: 13px;
  margin-bottom: 2px;
}

.model-item-info small {
  color: #99a7a1;
  font-size: 11px;
}

.drift-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.drift-status-card {
  background: #fff;
  border: 1px solid #e2ebe8;
  border-radius: 12px;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.drift-status-card.level-normal {
  border-left: 4px solid #38a169;
  background: linear-gradient(135deg, #ecf8f2 0%, #fff 80%);
}

.drift-status-card.level-slight {
  border-left: 4px solid #d18f2f;
  background: linear-gradient(135deg, #fdf6e9 0%, #fff 80%);
}

.drift-status-card.level-significant {
  border-left: 4px solid #e53e3e;
  background: linear-gradient(135deg, #fbecec 0%, #fff 80%);
}

.drift-status-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #56716e;
  font-size: 12px;
  font-weight: 600;
}

.drift-model-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.drift-model-info strong {
  font-size: 16px;
  color: #24494d;
}

.drift-model-info span {
  font-size: 12px;
  color: #99a7a1;
  background: #f0f5f4;
  padding: 3px 10px;
  border-radius: 12px;
}

.drift-status-badge {
  display: inline-block;
  padding: 6px 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  width: fit-content;
}

.drift-status-badge.status-normal {
  background: #d6f5e3;
  color: #276b47;
}

.drift-status-badge.status-slight {
  background: #fef3c7;
  color: #92400e;
}

.drift-status-badge.status-significant {
  background: #fee2e2;
  color: #991b1b;
}

.drift-features {
  padding: 12px 16px;
}

.drift-feature-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f5f4;
}

.drift-feature-item:last-child {
  border-bottom: none;
}

.feat-name {
  flex: 0 0 120px;
  font-size: 12px;
  color: #36585b;
  font-weight: 500;
}

.feat-psi-wrap {
  flex: 1;
  height: 8px;
  background: #f0f5f4;
  border-radius: 4px;
  overflow: hidden;
}

.feat-psi-bar {
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s;
}

.drift-feature-item.level-normal .feat-psi-bar {
  background: #38a169;
}

.drift-feature-item.level-slight .feat-psi-bar {
  background: #d18f2f;
}

.drift-feature-item.level-significant .feat-psi-bar {
  background: #e53e3e;
}

.feat-psi-val {
  flex: 0 0 80px;
  font-size: 11px;
  color: #6f807d;
  font-family: monospace;
}

.feat-level {
  flex: 0 0 70px;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
  text-align: center;
}

.feat-level.level-normal {
  background: #ecf8f2;
  color: #276b47;
}

.feat-level.level-slight {
  background: #fef3c7;
  color: #92400e;
}

.feat-level.level-significant {
  background: #fee2e2;
  color: #991b1b;
}

.drift-trend-chart {
  padding: 12px 16px;
}

.mini-svg {
  width: 100%;
  height: 50px;
}

.drift-recommendation {
  background: #fff;
  border: 1px solid #e2ebe8;
  border-radius: 12px;
  padding: 18px 20px;
}

.recommendation-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  color: #d18f2f;
}

.recommendation-header h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.drift-recommendation p {
  margin: 0 0 14px;
  color: #36585b;
  font-size: 13px;
  line-height: 1.6;
}

.recommendation-actions {
  display: flex;
  gap: 10px;
}

.retrain-list {
  padding: 8px 12px;
}

.retrain-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f5f4;
}

.retrain-item:last-child {
  border-bottom: none;
}

.retrain-info {
  flex: 0 0 150px;
}

.retrain-info strong {
  display: block;
  font-size: 12px;
  color: #24494d;
}

.retrain-info small {
  font-size: 10px;
  color: #99a7a1;
}

.retrain-progress {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
}

.progress-bar {
  flex: 1;
  height: 6px;
  background: #f0f5f4;
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #4f8f99;
  border-radius: 3px;
  transition: width 0.3s;
}

.retrain-progress span {
  font-size: 11px;
  color: #6f807d;
  min-width: 35px;
}

.retrain-status {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
}

.retrain-status.status-running {
  background: #e8f5f3;
  color: #2f7f80;
}

.retrain-status.status-completed {
  background: #ecf8f2;
  color: #276b47;
}

.drift-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: #99a7a1;
  text-align: center;
  background: #fff;
  border: 1px solid #e2ebe8;
  border-radius: 12px;
}

.drift-empty p {
  margin: 12px 0 0;
  font-size: 13px;
}

.text-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border: none;
  background: transparent;
  color: #4f8f99;
  font-size: 12px;
  cursor: pointer;
}

.text-btn:hover {
  background: #e8f5f3;
  border-radius: 4px;
}

.text-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.primary-btn {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 8px 14px;
  border: 0;
  background: #2f7f80;
  color: #fff;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}

.primary-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ghost-btn {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 6px 10px;
  border: 1px solid #d9e6e1;
  background: #fff;
  border-radius: 6px;
  color: #37555a;
  font-size: 12px;
  cursor: pointer;
}

.ghost-btn:hover {
  background: #f4f7f5;
}

@media (max-width: 900px) {
  .drift-layout {
    grid-template-columns: 1fr;
  }
}
</style>
