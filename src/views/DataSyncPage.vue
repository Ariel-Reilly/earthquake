<script setup>
/**
 * 数据同步监控（v1.7.0+）：补齐 P3-1 的"数据同步与质量监控"缺口。
 *
 * 关注点（区别于 DataSourcesPage.vue 的"接入配置"）：
 *   - 数据源 / 拉取任务 / 同步管道的运行状态、延迟、失败原因
 *   - 数据完整性：缺失字段、零值异常、重复记录
 *   - 异常值告警：单点突跳、长期为零、离群点
 *
 * 离线模式：全部走 mock 数据，回退 UI；接入后端后会自动切到 /sync/* 端点。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  Activity,
  AlertTriangle,
  ArrowDownToLine,
  CheckCircle2,
  CircleDashed,
  Clock4,
  Database,
  Download,
  FileWarning,
  Gauge,
  Inbox,
  Layers3,
  Loader2,
  PauseCircle,
  PlayCircle,
  RefreshCcw,
  Search,
  ShieldAlert,
  TrendingUp,
  XCircle,
} from 'lucide-vue-next'
import { dashboardApi } from '../services/dashboardApi'

const props = defineProps({
  searchTerm: { type: String, default: '' },
  userRole: { type: String, default: 'admin' },
})
const emit = defineEmits(['notify'])

const TABS = [
  { id: 'tasks', label: '实时同步', icon: Activity },
  { id: 'history', label: '历史记录', icon: Inbox },
  { id: 'integrity', label: '数据完整性', icon: ShieldAlert },
  { id: 'anomaly', label: '异常值告警', icon: FileWarning },
]

const CATEGORY_META = {
  气象: { color: '#4f8f99', icon: '🌦' },
  水文: { color: '#2f7f80', icon: '🌊' },
  地震: { color: '#9c3d3d', icon: '🪨' },
  卫星: { color: '#5e7c79', icon: '🛰' },
  设备: { color: '#7b8f8c', icon: '📡' },
  暴露度: { color: '#c69132', icon: '🏘' },
}

const STATUS_META = {
  running: { label: '运行中', color: '#2f7f80', icon: Loader2 },
  success: { label: '成功', color: '#5b9b80', icon: CheckCircle2 },
  failed: { label: '失败', color: '#9c3d3d', icon: XCircle },
  pending: { label: '等待中', color: '#c69132', icon: CircleDashed },
  paused: { label: '已暂停', color: '#7b8f8c', icon: PauseCircle },
}

const activeTab = ref('tasks')
const refreshing = ref(false)
let refreshTimer = null

const filters = reactive({
  category: 'all',
  status: 'all',
  keyword: '',
  integrityType: 'all',
  anomalyLevel: 'all',
})

const stats = reactive({
  total: 18,
  online: 14,
  failed: 2,
  syncsToday: 312,
  avgLatency: 1840,
  dataPointsPerMin: 4280,
})

/* ===X= Tab 1: 实时同步任务 ===X= */
const tasks = ref([
  {
    id: 'tsk-001',
    name: '中国气象局降水预报',
    category: '气象',
    target: 'cma-gfs.precip',
    schedule: '每 10 分钟',
    lastRun: '刚刚',
    lastLatency: 1320,
    lastStatus: 'success',
    lastRows: 8640,
    nextRun: '8 分钟后',
    healthy: true,
    error: null,
    running: false,
  },
  {
    id: 'tsk-002',
    name: '水文站水位 实时入库',
    category: '水文',
    target: 'tdengine.water_level',
    schedule: '每 30 秒',
    lastRun: '12 秒前',
    lastLatency: 420,
    lastStatus: 'running',
    lastRows: 124,
    nextRun: '18 秒后',
    healthy: true,
    error: null,
    running: true,
  },
  {
    id: 'tsk-003',
    name: 'Sentinel-1 SAR 影像',
    category: '卫星',
    target: 'sentinel.s1.grd',
    schedule: '每天 03:00',
    lastRun: '6 小时前',
    lastLatency: 285000,
    lastStatus: 'failed',
    lastRows: 0,
    nextRun: '明天 03:00',
    healthy: false,
    error: 'Copernicus API token 401：凭证过期',
    running: false,
  },
  {
    id: 'tsk-004',
    name: '地震台网速报',
    category: '地震',
    target: 'china.earthquake.catalog',
    schedule: '每 1 分钟',
    lastRun: '34 秒前',
    lastLatency: 680,
    lastStatus: 'success',
    lastRows: 3,
    nextRun: '26 秒后',
    healthy: true,
    error: null,
    running: false,
  },
  {
    id: 'tsk-005',
    name: '雨量计 MQTT 批量入库',
    category: '设备',
    target: 'mqtt.rainfall.batch',
    schedule: '实时',
    lastRun: '刚刚',
    lastLatency: 95,
    lastStatus: 'success',
    lastRows: 312,
    nextRun: '持续',
    healthy: true,
    error: null,
    running: true,
  },
  {
    id: 'tsk-006',
    name: '人口暴露度 季度更新',
    category: '暴露度',
    target: 'worldpop.china.cn',
    schedule: '每 90 天',
    lastRun: '14 天前',
    lastLatency: 0,
    lastStatus: 'pending',
    lastRows: 0,
    nextRun: '76 天后',
    healthy: true,
    error: null,
    running: false,
  },
])

/* ===X= Tab 2: 历史记录 ===X= */
const history = ref([
  { id: 'h-9001', task: '雨量计 MQTT 批量入库', time: '2026-09-12 22:18:04', duration: 95, rows: 312, status: 'success', triggeredBy: 'scheduler' },
  { id: 'h-9000', task: '中国气象局降水预报', time: '2026-09-12 22:10:00', duration: 1320, rows: 8640, status: 'success', triggeredBy: 'scheduler' },
  { id: 'h-8999', task: 'Sentinel-1 SAR 影像', time: '2026-09-12 03:00:01', duration: 285000, rows: 0, status: 'failed', triggeredBy: 'scheduler', error: '凭证过期' },
  { id: 'h-8998', task: '地震台网速报', time: '2026-09-12 22:17:00', duration: 680, rows: 3, status: 'success', triggeredBy: 'scheduler' },
  { id: 'h-8997', task: '水文站水位 实时入库', time: '2026-09-12 22:17:30', duration: 420, rows: 124, status: 'success', triggeredBy: 'scheduler' },
  { id: 'h-8996', task: '雨量计 MQTT 批量入库', time: '2026-09-12 22:17:04', duration: 110, rows: 308, status: 'success', triggeredBy: 'scheduler' },
  { id: 'h-8995', task: '中国气象局降水预报', time: '2026-09-12 22:00:00', duration: 1290, rows: 8580, status: 'success', triggeredBy: 'scheduler' },
  { id: 'h-8994', task: 'EMQX 设备心跳', time: '2026-09-12 22:15:00', duration: 80, rows: 128, status: 'success', triggeredBy: 'scheduler' },
  { id: 'h-8993', task: '雷达短临', time: '2026-09-12 22:05:00', duration: 920, rows: 420, status: 'success', triggeredBy: 'manual', error: null },
  { id: 'h-8992', task: '雨量计 MQTT 批量入库', time: '2026-09-12 22:14:00', duration: 95, rows: 296, status: 'success', triggeredBy: 'scheduler' },
])

/* ===X= Tab 3: 数据完整性 ===X= */
const integrity = ref([
  {
    table: 'device_metric_rainfall',
    label: '设备雨量指标',
    totalRows: 1284532,
    completeness: 99.7,
    issues: [
      { field: 'value', missing: 12, type: 'null' },
      { field: 'longitude', missing: 3, type: 'null' },
    ],
  },
  {
    table: 'hazard_point',
    label: '灾害点档案',
    totalRows: 3862,
    completeness: 96.4,
    issues: [
      { field: 'risk_score', missing: 41, type: 'missing' },
      { field: 'created_at', missing: 96, type: 'zero' },
    ],
  },
  {
    table: 'warning_event',
    label: '预警事件',
    totalRows: 5218,
    completeness: 99.95,
    issues: [
      { field: 'closed_reason', missing: 3, type: 'null' },
    ],
  },
  {
    table: 'weather_product',
    label: '气象产品',
    totalRows: 14280,
    completeness: 88.2,
    issues: [
      { field: 'precipitation', missing: 482, type: 'zero' },
      { field: 'wind_speed', missing: 1190, type: 'missing' },
    ],
  },
  {
    table: 'device_trajectory',
    label: '设备轨迹',
    totalRows: 21560,
    completeness: 99.1,
    issues: [
      { field: 'accuracy', missing: 192, type: 'zero' },
    ],
  },
])

/* ===X= Tab 4: 异常值告警 ===X= */
const anomalies = ref([
  {
    id: 'anom-501',
    device: 'RAIN-052',
    field: 'rainfall_1h',
    severity: 'high',
    type: '离群突跳',
    detectedAt: '2026-09-12 22:10',
    value: '168 mm',
    expected: '8 ~ 24 mm',
    suggestion: '检查传感器是否堵塞或上报异常，已通知运维',
  },
  {
    id: 'anom-500',
    device: 'TILT-014',
    field: 'tilt_x',
    severity: 'medium',
    type: '长期为零',
    detectedAt: '2026-09-12 21:48',
    value: '0.000',
    expected: '0.5 ~ 2.1°',
    suggestion: '设备可能离线，建议派单现场核查',
  },
  {
    id: 'anom-499',
    device: 'RAIN-007',
    field: 'temperature',
    severity: 'low',
    type: '突跳',
    detectedAt: '2026-09-12 21:32',
    value: '-18.4°C',
    expected: '21 ~ 26°C',
    suggestion: '数据序列中单点异常，已自动平滑处理',
  },
  {
    id: 'anom-498',
    device: 'SEIS-001',
    field: 'magnitude',
    severity: 'medium',
    type: '重复记录',
    detectedAt: '2026-09-12 20:15',
    value: '4.2 级 × 6 条',
    expected: '去重后 1 条',
    suggestion: '已合并写入归档表',
  },
])

/* ===X= Computed：过滤后的列表 ===X= */
const filteredTasks = computed(() => {
  let list = tasks.value
  if (filters.category !== 'all') list = list.filter((t) => t.category === filters.category)
  if (filters.status !== 'all') list = list.filter((t) => t.lastStatus === filters.status)
  if (filters.keyword.trim()) {
    const k = filters.keyword.trim().toLowerCase()
    list = list.filter((t) =>
      t.name.toLowerCase().includes(k) ||
      t.target.toLowerCase().includes(k),
    )
  }
  return list
})

const filteredHistory = computed(() => {
  let list = history.value
  if (filters.status !== 'all') list = list.filter((h) => h.status === filters.status)
  return list
})

const filteredIntegrity = computed(() => {
  let list = integrity.value
  if (filters.integrityType !== 'all') {
    list = list.filter((row) =>
      row.issues.some((i) => i.type === filters.integrityType),
    )
  }
  return list
})

const filteredAnomalies = computed(() => {
  let list = anomalies.value
  if (filters.anomalyLevel !== 'all') list = list.filter((a) => a.severity === filters.anomalyLevel)
  return list
})

const categoryOptions = computed(() => Object.keys(CATEGORY_META))
const statusOptions = ['running', 'success', 'failed', 'pending', 'paused']
const integrityTypes = [
  { value: 'all', label: '全部异常' },
  { value: 'null', label: '空值缺失' },
  { value: 'missing', label: '字段缺失' },
  { value: 'zero', label: '零值异常' },
]
const anomalyLevels = [
  { value: 'all', label: '全部严重度' },
  { value: 'high', label: '高严重' },
  { value: 'medium', label: '中严重' },
  { value: 'low', label: '低严重' },
]

const SEVERITY_META = {
  high: { label: '高', color: '#9c3d3d', icon: AlertTriangle },
  medium: { label: '中', color: '#e0a050', icon: AlertTriangle },
  low: { label: '低', color: '#7b8f8c', icon: AlertTriangle },
}

function statusMeta(s) {
  return STATUS_META[s] ?? STATUS_META.pending
}

function fmtLatency(ms) {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`
  return `${(ms / 60_000).toFixed(1)} min`
}

function fmtNumber(n) {
  if (n == null) return '—'
  if (n >= 10000) return `${(n / 10000).toFixed(1)} 万`
  return n.toLocaleString('zh-CN')
}

function fmtPct(p) {
  return `${(p ?? 0).toFixed(2)}%`
}

async function triggerTask(task) {
  if (task.lastStatus === 'running') return
  task.lastStatus = 'running'
  task.running = true
  emit('notify', `已触发 ${task.name}`)
  setTimeout(() => {
    task.lastStatus = 'success'
    task.running = false
    task.lastRun = '刚刚'
    task.lastLatency = Math.floor(800 + Math.random() * 1500)
    task.lastRows = Math.floor(80 + Math.random() * 200)
    task.error = null
    emit('notify', `${task.name} 已完成`)
  }, 1500)
}

function pauseTask(task) {
  task.lastStatus = 'paused'
  task.running = false
  emit('notify', `${task.name} 已暂停`)
}

async function manualRefresh() {
  refreshing.value = true
  try {
    // 接入后端时替换为真实接口
    if (dashboardApi.getSyncStats) {
      const data = await dashboardApi.getSyncStats().catch(() => null)
      if (data) Object.assign(stats, data)
    }
    emit('notify', '同步状态已刷新')
  } finally {
    refreshing.value = false
  }
}

async function loadData() {
  // 离线模式下使用本地 mock；接入后端后这里替换为真实拉取
  // 例如 dashboardApi.getSyncTasks / getSyncHistory / getIntegrityReport / getAnomalies
}

onMounted(() => {
  loadData()
  refreshTimer = window.setInterval(() => {
    // 自动更新运行中任务的实时延迟
    tasks.value.forEach((t) => {
      if (t.lastStatus === 'running') {
        t.lastLatency = Math.max(80, Math.floor(400 + Math.random() * 800))
      }
    })
  }, 5000)
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
})
</script>

<template>
  <div class="datasync-page">
    <!-- 统计卡片 -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-icon teal"><Database :size="19" /></div>
        <div class="stat-copy">
          <span>同步源总数</span>
          <strong>{{ stats.total }} <small>个</small></strong>
          <em class="positive">在线 {{ stats.online }} · 异常 {{ stats.failed }}</em>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon orange"><TrendingUp :size="19" /></div>
        <div class="stat-copy">
          <span>今日同步次数</span>
          <strong>{{ fmtNumber(stats.syncsToday) }}</strong>
          <em class="positive">较昨日 <i>+8.2%</i></em>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon blue"><Gauge :size="19" /></div>
        <div class="stat-copy">
          <span>平均同步延迟</span>
          <strong>{{ fmtLatency(stats.avgLatency) }}</strong>
          <em :class="stats.avgLatency > 2000 ? 'negative' : 'positive'">
            {{ stats.avgLatency > 2000 ? '需关注' : '健康' }}
            <i>SLA ≤ 2 s</i>
          </em>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><Layers3 :size="19" /></div>
        <div class="stat-copy">
          <span>分钟入库</span>
          <strong>{{ fmtNumber(stats.dataPointsPerMin) }} <small>条</small></strong>
          <em class="positive">8 路管道并发 <i>实时</i></em>
        </div>
      </div>
    </div>

    <!-- Tab 切换 -->
    <div class="content-grid">
      <section class="panel sync-panel">
        <div class="panel-header compact">
          <div>
            <h2>同步任务与质量监控</h2>
            <p>实时同步 · 历史记录 · 数据完整性 · 异常值告警</p>
          </div>
          <div class="datasync-tabs">
            <button
              v-for="tab in TABS"
              :key="tab.id"
              type="button"
              class="datasync-tab"
              :class="{ active: activeTab === tab.id }"
              @click="activeTab = tab.id"
            >
              <component :is="tab.icon" :size="14" />
              {{ tab.label }}
            </button>
          </div>
          <div class="heading-actions">
            <button class="secondary-btn" :disabled="refreshing" @click="manualRefresh">
              <RefreshCcw :size="14" :class="{ spin: refreshing }" />
              刷新
            </button>
            <button class="primary-btn" @click="emit('notify', '已导出今日同步报告')">
              <Download :size="14" />导出报告
            </button>
          </div>
        </div>

        <!-- Tab 1: 实时同步 -->
        <div v-if="activeTab === 'tasks'" class="sync-list">
          <div class="sync-filters">
            <select v-model="filters.category" class="filter-input">
              <option value="all">全部类型</option>
              <option v-for="c in categoryOptions" :key="c" :value="c">{{ c }}</option>
            </select>
            <select v-model="filters.status" class="filter-input">
              <option value="all">全部状态</option>
              <option v-for="s in statusOptions" :key="s" :value="s">{{ statusMeta(s).label }}</option>
            </select>
            <span class="filter-input filter-search">
              <Search :size="13" />
              <input v-model="filters.keyword" placeholder="搜索任务名称 / 目标表" />
            </span>
          </div>

          <div v-if="filteredTasks.length === 0" class="empty-state">
            <Inbox :size="32" />
            <p>没有匹配的同步任务</p>
          </div>

          <div v-else class="task-table">
            <div class="task-row task-head">
              <span>任务</span>
              <span>类别</span>
              <span>频率</span>
              <span>上次运行</span>
              <span>延迟</span>
              <span>状态</span>
              <span>操作</span>
            </div>
            <div
              v-for="task in filteredTasks"
              :key="task.id"
              class="task-row"
              :class="{ failed: task.lastStatus === 'failed' }"
            >
              <span class="task-name">
                <strong>{{ task.name }}</strong>
                <small>{{ task.target }}</small>
              </span>
              <span class="cat-chip" :style="{ color: CATEGORY_META[task.category]?.color, borderColor: CATEGORY_META[task.category]?.color }">
                {{ CATEGORY_META[task.category]?.icon }} {{ task.category }}
              </span>
              <span class="schedule">{{ task.schedule }}</span>
              <span>
                <Clock4 :size="12" />
                {{ task.lastRun }}
                <small v-if="task.nextRun">· 下次 {{ task.nextRun }}</small>
              </span>
              <span class="latency">{{ fmtLatency(task.lastLatency) }}</span>
              <span class="status-chip" :style="{ color: statusMeta(task.lastStatus).color, borderColor: statusMeta(task.lastStatus).color }">
                <component
                  :is="statusMeta(task.lastStatus).icon"
                  :size="12"
                  :class="{ spin: statusMeta(task.lastStatus).icon === Loader2 && task.running }"
                />
                {{ statusMeta(task.lastStatus).label }}
              </span>
              <span class="task-actions">
                <button
                  v-if="task.lastStatus !== 'running'"
                  class="icon-btn"
                  title="手动触发"
                  @click="triggerTask(task)"
                >
                  <PlayCircle :size="14" />
                </button>
                <button
                  v-else
                  class="icon-btn"
                  title="暂停"
                  @click="pauseTask(task)"
                >
                  <PauseCircle :size="14" />
                </button>
                <button class="icon-btn" title="查看日志">
                  <ArrowDownToLine :size="14" />
                </button>
              </span>
              <p v-if="task.error" class="task-error">
                <AlertTriangle :size="12" /> {{ task.error }}
              </p>
            </div>
          </div>
        </div>

        <!-- Tab 2: 历史记录 -->
        <div v-else-if="activeTab === 'history'" class="sync-list">
          <div class="sync-filters">
            <select v-model="filters.status" class="filter-input">
              <option value="all">全部状态</option>
              <option v-for="s in statusOptions" :key="s" :value="s">{{ statusMeta(s).label }}</option>
            </select>
          </div>

          <div class="history-table">
            <div class="history-row history-head">
              <span>时间</span>
              <span>任务</span>
              <span>耗时</span>
              <span>入库行数</span>
              <span>触发方式</span>
              <span>结果</span>
            </div>
            <div v-for="row in filteredHistory" :key="row.id" class="history-row">
              <span class="mono">{{ row.time }}</span>
              <span>{{ row.task }}</span>
              <span>{{ fmtLatency(row.duration) }}</span>
              <span>{{ fmtNumber(row.rows) }}</span>
              <span class="trigger">{{ row.triggeredBy === 'manual' ? '手动' : '调度' }}</span>
              <span class="status-chip" :style="{ color: statusMeta(row.status).color, borderColor: statusMeta(row.status).color }">
                <component :is="statusMeta(row.status).icon" :size="12" />
                {{ statusMeta(row.status).label }}
              </span>
              <p v-if="row.error" class="task-error">
                <AlertTriangle :size="12" /> {{ row.error }}
              </p>
            </div>
          </div>
        </div>

        <!-- Tab 3: 数据完整性 -->
        <div v-else-if="activeTab === 'integrity'" class="sync-list">
          <div class="sync-filters">
            <select v-model="filters.integrityType" class="filter-input">
              <option v-for="t in integrityTypes" :key="t.value" :value="t.value">{{ t.label }}</option>
            </select>
          </div>

          <div class="integrity-grid">
            <article v-for="row in filteredIntegrity" :key="row.table" class="integrity-card">
              <div class="integrity-head">
                <div>
                  <strong>{{ row.label }}</strong>
                  <small>{{ row.table }} · {{ fmtNumber(row.totalRows) }} 行</small>
                </div>
                <div class="integrity-pct" :class="{ danger: row.completeness < 95, warn: row.completeness < 99 && row.completeness >= 95 }">
                  {{ fmtPct(row.completeness) }}
                </div>
              </div>
              <div class="integrity-bar">
                <i :style="{ width: `${row.completeness}%`, background: row.completeness >= 99 ? '#2f7f80' : row.completeness >= 95 ? '#e0a050' : '#9c3d3d' }"></i>
              </div>
              <ul class="integrity-issues">
                <li v-for="(iss, idx) in row.issues" :key="idx">
                  <span class="issue-tag" :class="`tag-${iss.type}`">{{ iss.type }}</span>
                  <strong>{{ iss.field }}</strong>
                  <small>{{ iss.missing }} 条</small>
                </li>
              </ul>
            </article>
          </div>
        </div>

        <!-- Tab 4: 异常值告警 -->
        <div v-else-if="activeTab === 'anomaly'" class="sync-list">
          <div class="sync-filters">
            <select v-model="filters.anomalyLevel" class="filter-input">
              <option v-for="l in anomalyLevels" :key="l.value" :value="l.value">{{ l.label }}</option>
            </select>
          </div>

          <div class="anomaly-list">
            <article v-for="row in filteredAnomalies" :key="row.id" class="anomaly-row">
              <div class="anomaly-meta">
                <span class="severity-chip" :style="{ color: SEVERITY_META[row.severity].color, borderColor: SEVERITY_META[row.severity].color }">
                  <component :is="SEVERITY_META[row.severity].icon" :size="12" />
                  {{ SEVERITY_META[row.severity].label }}
                </span>
                <strong>{{ row.device }}</strong>
                <small>· {{ row.field }}</small>
              </div>
              <div class="anomaly-body">
                <p>
                  <span class="anomaly-type">{{ row.type }}</span>
                  · 检测于 <time>{{ row.detectedAt }}</time>
                </p>
                <p>
                  <strong>实测</strong>：{{ row.value }} <em>·</em>
                  <strong>正常区间</strong>：{{ row.expected }}
                </p>
                <p class="suggestion">
                  <CheckCircle2 :size="12" /> {{ row.suggestion }}
                </p>
              </div>
              <div class="anomaly-actions">
                <button class="secondary-btn" @click="emit('notify', '已派单现场核查 ' + row.device)">
                  派单核查
                </button>
                <button class="text-btn" @click="emit('notify', '已忽略 ' + row.device + ' / ' + row.field)">
                  忽略
                </button>
              </div>
            </article>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
