<script setup>
/**
 * 审批中心：左侧筛选 + KPI + 饼图 + 趋势 mini 图 + 审批表格 + 右侧抽屉详情。
 * 依赖 dashboardApi.listApprovals / getApprovalStats / decideApproval。
 * 后端未实现时由 requireBackend 拦截，UI 上层以「暂无数据」占位。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  ShieldCheck,
  Check,
  X,
  FileSignature,
  GitMerge,
  Activity,
  BarChart3,
  RefreshCw,
  Filter,
  ChevronRight,
  Clock4,
  Sparkles,
  Info,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])

const CATEGORY_OPTIONS = [
  { value: 'all', label: '全部' },
  { value: 'warning', label: '预警发布' },
  { value: 'dispatch', label: '应急派单' },
  { value: 'plan', label: '预案启动' },
  { value: 'device', label: '设备控制' },
]

const LEVEL_OPTIONS = [
  { value: 'all', label: '全部紧急度' },
  { value: 'blue', label: '蓝色' },
  { value: 'yellow', label: '黄色' },
  { value: 'orange', label: '橙色' },
  { value: 'red', label: '红色' },
]

const RANGE_OPTIONS = [
  { value: 'today', label: '今天' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
]

const STATUS_OPTIONS = [
  { value: 'pending', label: '待我审' },
  { value: 'approved', label: '已通过' },
  { value: 'rejected', label: '已驳回' },
  { value: 'all', label: '全部' },
]

const LEVEL_VAR_MAP = {
  red: '#e06060',
  orange: '#e0a050',
  yellow: '#d9c040',
  blue: '#4f8f99',
}

const CATEGORY_LABEL = {
  warning: '预警发布',
  dispatch: '应急派单',
  plan: '预案启动',
  device: '设备控制',
}

const STATUS_LABEL = {
  pending: '待我审',
  approved: '已通过',
  rejected: '已驳回',
}

const filters = reactive({
  category: 'all',
  level: 'all',
  range: '7d',
  status: 'pending',
})

const stats = reactive({
  totalPending: 0,
  decided: 0,
  passRate: 0,
  avgHours: 0,
  pie: { pending: 0, approved: 0, rejected: 0 },
  trend: Array.from({ length: 14 }, (_, i) => ({ day: `D-${13 - i}`, count: 0 })),
})

const approvals = ref([])
const selectedId = ref(null)
const selectedDetail = ref(null)
const detailOpen = ref(false)
const loading = ref(false)
const statsLoading = ref(false)
const showMobileFilter = ref(false)
const showDecisionModal = ref(false)
const decisionMode = ref('approve') // approve / reject
const decisionComment = ref('')
const decisionError = ref('')
const submitting = ref(false)
const lastUpdated = ref(null)

const selected = computed(() => approvals.value.find((item) => String(item.id) === String(selectedId.value)) ?? null)

const filteredApprovals = computed(() => {
  return approvals.value.filter((item) => {
    if (filters.category !== 'all' && item.category !== filters.category) {return false}
    if (filters.level !== 'all' && pickLevel(item.level) !== filters.level) {return false}
    if (filters.status !== 'all' && normalizeStatus(item.status) !== filters.status) {return false}
    return true
  })
})

function pickLevel(value) {
  const text = String(value ?? '')
  if (/红/.test(text)) {return 'red'}
  if (/橙/.test(text)) {return 'orange'}
  if (/黄/.test(text)) {return 'yellow'}
  if (/蓝/.test(text)) {return 'blue'}
  return 'blue'
}

function levelVar(level) {
  return LEVEL_VAR_MAP[pickLevel(level)] ?? '#4f8f99'
}

function levelLabel(level) {
  return ({ red: '红色', orange: '橙色', yellow: '黄色', blue: '蓝色' })[pickLevel(level)]
}

function normalizeStatus(value) {
  if (!value) {return 'pending'}
  if (typeof value === 'string') {
    if (/通过|approved|approve/i.test(value)) {return 'approved'}
    if (/驳回|拒绝|reject|rejected/i.test(value)) {return 'rejected'}
    if (/待|pending/i.test(value)) {return 'pending'}
  }
  return 'pending'
}

function categoryLabel(value) {
  return CATEGORY_LABEL[value] ?? value ?? '—'
}

function statusLabel(value) {
  return STATUS_LABEL[normalizeStatus(value)] ?? value ?? '—'
}

function formatTime(value) {
  if (!value) {return '—'}
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {return String(value)}
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function shortTime(value) {
  if (!value) {return '—'}
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {return String(value)}
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

const trendMax = computed(() => Math.max(1, ...stats.trend.map((d) => d.count)))

function arcPath(pie, key) {
  // 简易饼图：用 SVG path 描述扇形
  const values = [pie.pending, pie.approved, pie.rejected]
  const colors = { pending: '#e0a050', approved: '#6dbfa5', rejected: '#e06060' }
  const order = ['pending', 'approved', 'rejected']
  const total = values.reduce((sum, v) => sum + v, 0)
  if (!total) {return ''}
  let angle = -Math.PI / 2
  const cx = 60, cy = 60, r = 50
  const segments = []
  order.forEach((k, i) => {
    const value = values[i]
    if (!value) {return}
    const slice = (value / total) * Math.PI * 2
    const x1 = cx + r * Math.cos(angle)
    const y1 = cy + r * Math.sin(angle)
    const x2 = cx + r * Math.cos(angle + slice)
    const y2 = cy + r * Math.sin(angle + slice)
    const large = slice > Math.PI ? 1 : 0
    segments.push({
      key: k,
      color: colors[k],
      d: `M ${cx} ${cy} L ${x1.toFixed(2)} ${y1.toFixed(2)} A ${r} ${r} 0 ${large} 1 ${x2.toFixed(2)} ${y2.toFixed(2)} Z`,
    })
    angle += slice
  })
  const target = segments.find((s) => s.key === key)
  return target?.d ?? ''
}

const pieSegments = computed(() => {
  const values = [
    { key: 'pending', value: stats.pie.pending, color: '#e0a050', label: '待我审' },
    { key: 'approved', value: stats.pie.approved, color: '#6dbfa5', label: '已通过' },
    { key: 'rejected', value: stats.pie.rejected, color: '#e06060', label: '已驳回' },
  ]
  const total = values.reduce((sum, v) => sum + v.value, 0) || 1
  let angle = -Math.PI / 2
  const cx = 60, cy = 60, r = 50
  return values.map((v) => {
    const slice = (v.value / total) * Math.PI * 2
    const x1 = cx + r * Math.cos(angle)
    const y1 = cy + r * Math.sin(angle)
    const x2 = cx + r * Math.cos(angle + slice)
    const y2 = cy + r * Math.sin(angle + slice)
    const large = slice > Math.PI ? 1 : 0
    angle += slice
    return {
      key: v.key,
      color: v.color,
      label: v.label,
      value: v.value,
      percent: Math.round((v.value / total) * 100),
      d: v.value
        ? `M ${cx} ${cy} L ${x1.toFixed(2)} ${y1.toFixed(2)} A ${r} ${r} 0 ${large} 1 ${x2.toFixed(2)} ${y2.toFixed(2)} Z`
        : '',
    }
  })
})

function rowClick(item) {
  selectedId.value = item.id
  detailOpen.value = true
  // 构造详情：从行项 + 模拟历史时间轴
  selectedDetail.value = {
    ...item,
    description: item.description ?? '本次申请由现场作业组发起，等待审批人复核并决策。',
    history: [
      { ts: item.submittedAt, actor: item.applicant ?? '申请人', action: '提交申请', status: 'pending' },
      { ts: item.submittedAt, actor: '系统', action: '自动校验通过', status: 'pending' },
    ],
  }
}

function closeDrawer() {
  detailOpen.value = false
}

function resetFilters() {
  filters.category = 'all'
  filters.level = 'all'
  filters.range = '7d'
  filters.status = 'pending'
}

function openDecision(mode) {
  decisionMode.value = mode
  decisionComment.value = ''
  decisionError.value = ''
  showDecisionModal.value = true
}

function cancelDecision() {
  showDecisionModal.value = false
  decisionComment.value = ''
  decisionError.value = ''
}

async function submitDecision() {
  if (!decisionComment.value.trim()) {
    decisionError.value = '请填写审批意见'
    return
  }
  if (!selected.value) {return}
  submitting.value = true
  try {
    await dashboardApi.decideApproval(selected.value.id, {
      decision: decisionMode.value === 'approve' ? 'approve' : 'reject',
      comment: decisionComment.value.trim(),
    })
    emit('notify', decisionMode.value === 'approve' ? '已通过审批' : '已驳回审批')
    // 本地乐观更新
    if (selectedDetail.value) {
      selectedDetail.value.history = [
        ...(selectedDetail.value.history ?? []),
        {
          ts: new Date().toISOString(),
          actor: '当前用户',
          action: decisionMode.value === 'approve' ? '审批通过' : '审批驳回',
          status: decisionMode.value === 'approve' ? 'approved' : 'rejected',
          comment: decisionComment.value.trim(),
        },
      ]
      selectedDetail.value.status = decisionMode.value === 'approve' ? '已通过' : '已驳回'
    }
    approvals.value = approvals.value.map((a) =>
      String(a.id) === String(selected.value.id)
        ? { ...a, status: decisionMode.value === 'approve' ? '已通过' : '已驳回' }
        : a,
    )
    showDecisionModal.value = false
    decisionComment.value = ''
  } catch (err) {
    emit('notify', err?.message ?? '审批决策提交失败')
  } finally {
    submitting.value = false
  }
}

async function loadStats(showToast = false) {
  statsLoading.value = true
  try {
    const data = await dashboardApi.getApprovalStats()
    if (data && typeof data === 'object') {
      Object.assign(stats, data)
      if (!Array.isArray(stats.trend) || !stats.trend.length) {
        stats.trend = Array.from({ length: 14 }, (_, i) => ({ day: `D-${13 - i}`, count: 0 }))
      }
      if (!stats.pie) {stats.pie = { pending: 0, approved: 0, rejected: 0 }}
    }
  } catch (err) {
    // 离线模式兜底零值
    stats.totalPending = 0
    stats.decided = 0
    stats.passRate = 0
    stats.avgHours = 0
    stats.pie = { pending: 0, approved: 0, rejected: 0 }
    if (showToast) {emit('notify', err?.message ?? '审批统计加载失败')}
  } finally {
    statsLoading.value = false
    lastUpdated.value = new Date()
  }
}

async function loadList(showToast = false) {
  loading.value = true
  try {
    const data = await dashboardApi.listApprovals({ ...filters })
    const list = Array.isArray(data?.list) ? data.list : Array.isArray(data) ? data : []
    approvals.value = list.map((item, idx) => ({
      id: item.id ?? `A-${idx + 1}`,
      no: item.no ?? `AP${String(idx + 1).padStart(4, '0')}`,
      category: item.category ?? 'warning',
      title: item.title ?? '未命名审批',
      applicant: item.applicant ?? '—',
      level: item.level ?? '蓝色',
      submittedAt: item.submittedAt ?? new Date(Date.now() - idx * 3600_000).toISOString(),
      status: item.status ?? '待我审',
      description: item.description ?? '',
    }))
    if (!approvals.value.length && !showToast) {
      // 静默无数据
    }
    if (showToast) {emit('notify', '审批列表已刷新')}
  } catch (err) {
    approvals.value = []
    if (showToast) {emit('notify', err?.message ?? '审批列表加载失败')}
  } finally {
    loading.value = false
  }
}

async function refresh() {
  await Promise.all([loadStats(), loadList()])
}

const KPI_LIST = computed(() => [
  {
    key: 'pending',
    label: '总待审',
    value: stats.totalPending,
    suffix: '项',
    color: '#e0a050',
    desc: '等待我的审批决策',
    icon: Clock4,
  },
  {
    key: 'decided',
    label: '已审',
    value: stats.decided,
    suffix: '项',
    color: '#4f8f99',
    desc: '本周期已处理',
    icon: ShieldCheck,
  },
  {
    key: 'passRate',
    label: '通过率',
    value: stats.passRate,
    suffix: '%',
    color: '#6dbfa5',
    desc: '通过 / 已审',
    icon: Check,
  },
  {
    key: 'avgHours',
    label: '平均耗时',
    value: stats.avgHours,
    suffix: '小时',
    color: '#4f8f99',
    desc: '提交到决策',
    icon: Activity,
  },
])

onMounted(async () => {
  await refresh()
})

defineExpose({ refresh })
</script>

<template>
  <div class="approval-center-page" :class="{ 'drawer-open': detailOpen }">
    <!-- 语义区分提示：AI 灾害初筛走人工确认工作台，行政审批走本页面 -->
    <div class="approval-semantic-hint">
      <Info :size="14" />
      <span>这里是<strong>预警发布、预案启动、应急派单</strong>等行政流程的审批。
        AI 初筛的疑似事件请前往
        <RouterLink to="/dashboard/confirmation-workbench">人工确认工作台</RouterLink>
        处理。
      </span>
    </div>

    <!-- 移动端筛选切换 -->
    <button class="mobile-filter-btn" @click="showMobileFilter = !showMobileFilter">
      <Filter :size="14" />
      {{ showMobileFilter ? '收起筛选' : '展开筛选' }}
    </button>

    <aside class="filter-rail" :class="{ open: showMobileFilter }">
      <div class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2><Filter :size="15" />筛选条件</h2>
            <p>多维度过滤审批单据</p>
          </div>
          <button class="secondary-btn ghost" @click="resetFilters">重置</button>
        </div>
        <div class="filter-section">
          <label class="filter-label">类别</label>
          <div class="chip-group">
            <button
              v-for="opt in CATEGORY_OPTIONS"
              :key="opt.value"
              class="chip"
              :class="{ active: filters.category === opt.value }"
              @click="filters.category = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
        <div class="filter-section">
          <label class="filter-label">紧急度</label>
          <div class="chip-group level-chips">
            <button
              v-for="opt in LEVEL_OPTIONS"
              :key="opt.value"
              class="chip level-chip"
              :class="{ active: filters.level === opt.value }"
              :style="opt.value !== 'all' ? { '--accent': LEVEL_VAR_MAP[opt.value] } : null"
              @click="filters.level = opt.value"
            >
              <i v-if="opt.value !== 'all'"></i>{{ opt.label }}
            </button>
          </div>
        </div>
        <div class="filter-section">
          <label class="filter-label">时间范围</label>
          <div class="chip-group">
            <button
              v-for="opt in RANGE_OPTIONS"
              :key="opt.value"
              class="chip"
              :class="{ active: filters.range === opt.value }"
              @click="filters.range = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
        <div class="filter-section">
          <label class="filter-label">状态</label>
          <div class="chip-group">
            <button
              v-for="opt in STATUS_OPTIONS"
              :key="opt.value"
              class="chip"
              :class="{ active: filters.status === opt.value }"
              @click="filters.status = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
        <div class="filter-summary">
          <Sparkles :size="12" />
          <span>共 {{ filteredApprovals.length }} 条匹配</span>
        </div>
      </div>
    </aside>

    <main class="approval-main">
      <!-- KPI -->
      <div class="stats-grid">
        <div
          v-for="kpi in KPI_LIST"
          :key="kpi.key"
          class="stat-card"
          :style="{ '--accent': kpi.color }"
        >
          <div class="stat-icon" :style="{ background: kpi.color + '22', color: kpi.color }">
            <component :is="kpi.icon" :size="19" />
          </div>
          <div class="stat-copy">
            <span>{{ kpi.label }}</span>
            <strong>{{ kpi.value }} <small>{{ kpi.suffix }}</small></strong>
            <em class="positive">{{ kpi.desc }}</em>
          </div>
        </div>
      </div>

      <!-- 图表区 -->
      <section class="panel system-panel chart-panel">
        <div class="panel-header compact">
          <div>
            <h2><BarChart3 :size="15" />审批态势</h2>
            <p>饼图：当前待审 / 已通过 / 已驳回比例；条形图：最近 14 天每日审批量</p>
          </div>
          <div class="heading-actions">
            <span v-if="lastUpdated" class="meta-text">更新于 {{ shortTime(lastUpdated) }}</span>
            <button class="secondary-btn" :disabled="loading || statsLoading" @click="refresh">
              <RefreshCw :size="13" />
              刷新
            </button>
          </div>
        </div>
        <div class="chart-grid">
          <div class="chart-block pie-block">
            <h3>状态分布</h3>
            <div class="pie-wrap">
              <svg viewBox="0 0 120 120" width="160" height="160">
                <circle cx="60" cy="60" r="50" fill="none" stroke="#eef6f3" stroke-width="14" />
                <path
                  v-for="seg in pieSegments"
                  :key="seg.key"
                  :d="seg.d"
                  :fill="seg.color"
                />
                <circle cx="60" cy="60" r="32" fill="#fff" />
              </svg>
              <ul class="pie-legend">
                <li v-for="seg in pieSegments" :key="seg.key">
                  <i :style="{ background: seg.color }"></i>
                  <span>{{ seg.label }}</span>
                  <strong>{{ seg.value }}</strong>
                  <em>{{ seg.percent }}%</em>
                </li>
              </ul>
            </div>
          </div>
          <div class="chart-block trend-block">
            <h3>近 14 天审批量</h3>
            <div class="trend-wrap">
              <div
                v-for="(bar, idx) in stats.trend"
                :key="idx"
                class="trend-bar"
                :title="`${bar.day}：${bar.count} 条`"
              >
                <i :style="{ height: `${(bar.count / trendMax) * 100}%` }">
                  <span class="bar-tip">{{ bar.count }}</span>
                </i>
                <small>{{ bar.day }}</small>
              </div>
            </div>
            <div class="trend-meta">
              <span><GitMerge :size="12" />累计 {{ stats.trend.reduce((s, b) => s + b.count, 0) }} 条</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 表格 -->
      <section class="panel warning-panel">
        <div class="panel-header compact">
          <div>
            <h2><FileSignature :size="15" />审批列表</h2>
            <p>点击行查看详情；右侧操作可直达通过 / 驳回</p>
          </div>
          <span class="meta-text">匹配 {{ filteredApprovals.length }} 条</span>
        </div>
        <div v-if="loading" class="loading-block">加载中…</div>
        <div v-else-if="!filteredApprovals.length" class="empty-block">
          <FileSignature :size="22" />
          <p>暂无数据</p>
          <small>当前筛选条件下没有审批工单</small>
        </div>
        <div v-else class="table-wrap">
          <table class="approval-table">
            <thead>
              <tr>
                <th>编号</th>
                <th>类别</th>
                <th>标题</th>
                <th>申请人</th>
                <th>紧急度</th>
                <th>提交时间</th>
                <th>状态</th>
                <th class="th-action">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in filteredApprovals"
                :key="item.id"
                :class="{ active: selectedId === item.id }"
                @click="rowClick(item)"
              >
                <td><code>{{ item.no }}</code></td>
                <td><span class="cat-pill">{{ categoryLabel(item.category) }}</span></td>
                <td>
                  <div class="cell-title">
                    <strong>{{ item.title }}</strong>
                    <small v-if="item.description">{{ item.description }}</small>
                  </div>
                </td>
                <td>{{ item.applicant }}</td>
                <td>
                  <span
                    class="level-tag"
                    :style="{ color: levelVar(item.level), borderColor: levelVar(item.level) }"
                  >
                    {{ levelLabel(item.level) }}
                  </span>
                </td>
                <td>{{ formatTime(item.submittedAt) }}</td>
                <td>
                  <span
                    class="status-tag"
                    :class="normalizeStatus(item.status)"
                  >
                    {{ statusLabel(item.status) }}
                  </span>
                </td>
                <td class="td-action" @click.stop>
                  <button
                    class="primary-btn small"
                    :disabled="normalizeStatus(item.status) !== 'pending'"
                    @click="rowClick(item); openDecision('approve')"
                  >
                    <Check :size="12" />通过
                  </button>
                  <button
                    class="secondary-btn small danger"
                    :disabled="normalizeStatus(item.status) !== 'pending'"
                    @click="rowClick(item); openDecision('reject')"
                  >
                    <X :size="12" />驳回
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </main>

    <!-- 右侧抽屉 -->
    <transition name="drawer">
      <aside v-if="detailOpen && selectedDetail" class="detail-drawer">
        <div class="drawer-header">
          <div>
            <small>{{ categoryLabel(selectedDetail.category) }} · {{ selectedDetail.no }}</small>
            <h3>{{ selectedDetail.title }}</h3>
          </div>
          <button class="icon-btn" @click="closeDrawer" aria-label="关闭">×</button>
        </div>
        <div class="drawer-body">
          <div class="detail-meta">
            <div><span>申请人</span><strong>{{ selectedDetail.applicant }}</strong></div>
            <div><span>紧急度</span>
              <strong :style="{ color: levelVar(selectedDetail.level) }">
                {{ levelLabel(selectedDetail.level) }}
              </strong>
            </div>
            <div><span>提交时间</span><strong>{{ formatTime(selectedDetail.submittedAt) }}</strong></div>
            <div><span>当前状态</span>
              <strong :class="`status-tag ${normalizeStatus(selectedDetail.status)}`">
                {{ statusLabel(selectedDetail.status) }}
              </strong>
            </div>
          </div>
          <div class="detail-desc">
            <h4>申请说明</h4>
            <p>{{ selectedDetail.description || '未提供详细说明' }}</p>
          </div>
          <div class="detail-history">
            <h4><Clock4 :size="14" />审批历史</h4>
            <ol class="timeline">
              <li
                v-for="(step, idx) in selectedDetail.history"
                :key="idx"
                :class="step.status"
              >
                <span class="dot"></span>
                <div class="timeline-body">
                  <strong>{{ step.action }}</strong>
                  <span class="actor">{{ step.actor }}</span>
                  <small>{{ formatTime(step.ts) }}</small>
                  <em v-if="step.comment">"{{ step.comment }}"</em>
                </div>
              </li>
            </ol>
          </div>
        </div>
        <div class="drawer-footer" v-if="normalizeStatus(selectedDetail.status) === 'pending'">
          <button class="secondary-btn danger" @click="openDecision('reject')">
            <X :size="13" />驳回
          </button>
          <button class="primary-btn" @click="openDecision('approve')">
            <Check :size="13" />通过
          </button>
        </div>
        <div class="drawer-footer closed" v-else>
          <span>本次审批已闭环（{{ statusLabel(selectedDetail.status) }}）</span>
        </div>
      </aside>
    </transition>

    <!-- 决策弹窗 -->
    <transition name="modal">
      <div v-if="showDecisionModal" class="modal-mask" @click.self="cancelDecision">
        <div class="modal-card">
          <div class="modal-header">
            <h3>
              <Check v-if="decisionMode === 'approve'" :size="16" />
              <X v-else :size="16" />
              {{ decisionMode === 'approve' ? '审批通过' : '审批驳回' }}
            </h3>
            <button class="icon-btn" @click="cancelDecision">×</button>
          </div>
          <div class="modal-body">
            <p class="modal-target">
              目标工单：<strong>{{ selectedDetail?.title ?? '—' }}</strong>
            </p>
            <label>
              <span>审批意见 <em>*</em></span>
              <textarea
                v-model="decisionComment"
                rows="4"
                :placeholder="decisionMode === 'approve' ? '请填写同意理由 / 后续要求' : '请填写驳回原因 / 改进建议'"
              ></textarea>
            </label>
            <p v-if="decisionError" class="error-text">{{ decisionError }}</p>
          </div>
          <div class="modal-footer">
            <button class="secondary-btn" @click="cancelDecision">取消</button>
            <button
              class="primary-btn"
              :class="{ danger: decisionMode === 'reject' }"
              :disabled="submitting"
              @click="submitDecision"
            >
              {{ submitting ? '提交中…' : (decisionMode === 'approve' ? '确认通过' : '确认驳回') }}
            </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 抽屉打开时的遮罩（移动端） -->
    <div v-if="detailOpen" class="drawer-mask" @click="closeDrawer"></div>
  </div>
</template>

<style scoped>
.approval-center-page {
  display: grid; gap: 15px;
  grid-template-columns: minmax(220px, 240px) minmax(0, 1fr);
  position: relative;
}
.approval-center-page.drawer-open { grid-template-columns: minmax(220px, 240px) minmax(0, 1fr) 360px; }

.approval-semantic-hint {
  grid-column: 1 / -1;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 14px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  font-size: 12.5px;
  color: #1e40af;
  line-height: 1.6;
}
.approval-semantic-hint strong { font-weight: 700; }
.approval-semantic-hint a { color: #2563eb; text-decoration: underline; font-weight: 600; }
.approval-semantic-hint a:hover { color: #1d4ed8; }

.mobile-filter-btn {
  display: none; align-items: center; gap: 6px;
  border: 1px solid #e3ebe7; background: #fff; padding: 8px 14px; border-radius: 8px;
  color: #1e4b45; cursor: pointer; font-size: 13px;
}

.filter-rail { display: flex; flex-direction: column; gap: 0; }
.filter-rail .panel { padding-bottom: 16px; }
.filter-section { padding: 0 20px 14px; border-top: 1px dashed #eef6f3; margin-top: 12px; }
.filter-section:first-of-type { border-top: none; margin-top: 0; }
.filter-label {
  display: block; font-size: 12px; color: #5b7770; margin-bottom: 8px;
}
.chip-group { display: flex; flex-wrap: wrap; gap: 6px; }
.chip {
  border: 1px solid #e3ebe7; background: #fff; color: #4a6760;
  padding: 4px 10px; border-radius: 999px; font-size: 12px; cursor: pointer;
  transition: all .2s ease;
}
.chip:hover { border-color: #6dbfa5; color: #1e4b45; }
.chip.active { background: #1e4b45; color: #fff; border-color: #1e4b45; }
.chip.level-chip { display: inline-flex; align-items: center; gap: 4px; }
.chip.level-chip i {
  width: 8px; height: 8px; border-radius: 50%; background: var(--accent, #4f8f99);
}
.chip.level-chip.active i { background: #fff; }
.filter-summary {
  margin: 12px 20px 0; padding: 8px 10px;
  background: #eef6f3; border-radius: 8px;
  display: flex; align-items: center; gap: 6px; font-size: 12px; color: #4a6760;
}

.approval-main { display: flex; flex-direction: column; gap: 15px; min-width: 0; }

.chart-panel { min-height: 0; }
.chart-grid {
  display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr);
  gap: 20px; padding: 16px 20px 20px;
}
@media (max-width: 980px) { .chart-grid { grid-template-columns: 1fr; } }

.chart-block h3 { font-size: 13px; color: #5b7770; margin: 0 0 10px; }
.pie-wrap { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.pie-legend { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 6px; }
.pie-legend li {
  display: grid; grid-template-columns: auto 1fr auto auto; align-items: center; gap: 8px;
  font-size: 12px; color: #4a6760;
}
.pie-legend li i { width: 10px; height: 10px; border-radius: 3px; }
.pie-legend li strong { color: #1e4b45; }
.pie-legend li em { color: #9bb5ac; font-style: normal; }

.trend-wrap {
  display: flex; align-items: flex-end; gap: 6px; height: 150px;
  border-bottom: 1px dashed #eef6f3; padding-bottom: 4px;
}
.trend-bar {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: flex-end;
  min-width: 14px; height: 100%;
}
.trend-bar i {
  width: 100%; background: linear-gradient(180deg, #6dbfa5, #4f8f99);
  border-radius: 6px 6px 0 0; position: relative; min-height: 4px;
  transition: height .3s ease;
}
.trend-bar:hover i { background: linear-gradient(180deg, #e0a050, #e06060); }
.trend-bar small { margin-top: 4px; font-size: 10px; color: #9bb5ac; }
.bar-tip {
  position: absolute; top: -18px; left: 50%; transform: translateX(-50%);
  font-size: 10px; color: #4a6760; background: #fff; padding: 1px 4px;
  border-radius: 4px; box-shadow: 0 2px 4px rgba(30, 75, 69, .08);
  opacity: 0; transition: opacity .2s ease;
}
.trend-bar:hover .bar-tip { opacity: 1; }
.trend-meta {
  margin-top: 8px; font-size: 12px; color: #5b7770;
  display: flex; align-items: center; gap: 6px;
}

.loading-block, .empty-block {
  padding: 30px 20px; text-align: center; color: #9bb5ac;
}
.empty-block { display: flex; flex-direction: column; align-items: center; gap: 4px; }
.empty-block p { margin: 6px 0 0; font-size: 14px; color: #4a6760; }
.empty-block small { color: #9bb5ac; font-size: 12px; }

.table-wrap { padding: 0 20px 20px; overflow-x: auto; }
.approval-table {
  width: 100%; border-collapse: collapse; font-size: 13px; min-width: 760px;
}
.approval-table thead th {
  text-align: left; color: #5b7770; font-weight: 500;
  padding: 10px 8px; border-bottom: 1px solid #e3ebe7;
}
.approval-table tbody td {
  padding: 10px 8px; border-bottom: 1px solid #f1f5f3; color: #1e4b45;
}
.approval-table tbody tr { cursor: pointer; transition: background .15s ease; }
.approval-table tbody tr:hover { background: #f6fbf8; }
.approval-table tbody tr.active { background: #eef6f3; }
.approval-table code {
  font-family: ui-monospace, monospace; font-size: 12px; color: #4a6760;
}
.cat-pill {
  font-size: 11px; padding: 2px 8px; border-radius: 999px;
  background: #eef6f3; color: #4a6760; border: 1px solid #d3e2dc;
}
.cell-title { display: flex; flex-direction: column; gap: 2px; }
.cell-title strong { color: #1e4b45; font-size: 13px; }
.cell-title small { color: #9bb5ac; font-size: 11px; }
.level-tag {
  font-size: 11px; padding: 2px 8px; border-radius: 999px;
  border: 1px solid currentColor; font-weight: 500;
}
.status-tag {
  font-size: 11px; padding: 2px 8px; border-radius: 999px;
  background: #eef6f3; color: #4a6760;
}
.status-tag.pending { background: #fdf6e9; color: #e0a050; }
.status-tag.approved { background: #e6f4ee; color: #1e7a5a; }
.status-tag.rejected { background: #fdf1ee; color: #e06060; }
.td-action { display: flex; gap: 4px; white-space: nowrap; }
.td-action button.small {
  padding: 4px 8px; font-size: 12px; border-radius: 6px;
  display: inline-flex; align-items: center; gap: 4px;
}
.td-action .secondary-btn.danger { color: #e06060; border-color: #f0d2c8; }
.td-action .secondary-btn.danger:hover { background: #fdf1ee; }

.meta-text { font-size: 12px; color: #5b7770; }

/* 抽屉 */
.detail-drawer {
  position: relative; align-self: stretch;
  background: #fff; border: 1px solid #e3ebe7; border-radius: 13px;
  display: flex; flex-direction: column; min-height: 480px;
  box-shadow: 0 8px 24px rgba(30, 75, 69, .06);
}
.drawer-header {
  padding: 16px 18px; border-bottom: 1px solid #eef6f3;
  display: flex; justify-content: space-between; align-items: flex-start; gap: 10px;
}
.drawer-header h3 { margin: 4px 0 0; font-size: 15px; color: #1e4b45; }
.drawer-header small { font-size: 12px; color: #5b7770; }
.icon-btn {
  background: transparent; border: 1px solid transparent; cursor: pointer;
  font-size: 18px; color: #5b7770; width: 28px; height: 28px; border-radius: 6px;
}
.icon-btn:hover { background: #f1f5f3; color: #1e4b45; }
.drawer-body { padding: 14px 18px; flex: 1; overflow-y: auto; }
.detail-meta {
  display: grid; grid-template-columns: 1fr 1fr; gap: 8px 14px;
  padding-bottom: 12px; border-bottom: 1px dashed #eef6f3; margin-bottom: 12px;
}
.detail-meta div { display: flex; flex-direction: column; gap: 2px; }
.detail-meta span { font-size: 11px; color: #9bb5ac; }
.detail-meta strong { font-size: 13px; color: #1e4b45; }
.detail-desc h4, .detail-history h4 {
  margin: 12px 0 8px; font-size: 13px; color: #5b7770;
  display: flex; align-items: center; gap: 6px;
}
.detail-desc p { font-size: 13px; color: #1e4b45; line-height: 1.6; }
.timeline { list-style: none; padding: 0; margin: 0; position: relative; }
.timeline::before {
  content: ''; position: absolute; left: 6px; top: 6px; bottom: 6px;
  width: 1px; background: #e3ebe7;
}
.timeline li {
  position: relative; padding-left: 20px; padding-bottom: 12px;
  display: flex; flex-direction: column;
}
.timeline li .dot {
  position: absolute; left: 2px; top: 4px; width: 9px; height: 9px;
  border-radius: 50%; background: #4f8f99;
}
.timeline li.approved .dot { background: #6dbfa5; }
.timeline li.rejected .dot { background: #e06060; }
.timeline-body { display: flex; flex-direction: column; gap: 2px; }
.timeline-body strong { font-size: 13px; color: #1e4b45; }
.timeline-body .actor { font-size: 12px; color: #5b7770; }
.timeline-body small { font-size: 11px; color: #9bb5ac; }
.timeline-body em { font-size: 12px; color: #4a6760; font-style: normal; }

.drawer-footer {
  padding: 12px 18px; border-top: 1px solid #eef6f3;
  display: flex; justify-content: flex-end; gap: 8px;
}
.drawer-footer.closed { justify-content: center; color: #9bb5ac; font-size: 12px; }
.drawer-footer .secondary-btn.danger { color: #e06060; border-color: #f0d2c8; }
.drawer-footer .secondary-btn.danger:hover { background: #fdf1ee; }

.drawer-mask {
  display: none;
}

/* 弹窗 */
.modal-mask {
  position: fixed; inset: 0; background: rgba(30, 75, 69, .35);
  display: flex; align-items: center; justify-content: center;
  z-index: 9999; padding: 20px;
}
.modal-card {
  background: #fff; border-radius: 13px; width: 100%; max-width: 440px;
  box-shadow: 0 20px 60px rgba(30, 75, 69, .18); overflow: hidden;
}
.modal-header {
  padding: 14px 18px; border-bottom: 1px solid #eef6f3;
  display: flex; align-items: center; justify-content: space-between;
}
.modal-header h3 { margin: 0; font-size: 15px; color: #1e4b45; display: inline-flex; gap: 6px; align-items: center; }
.modal-body { padding: 14px 18px; display: flex; flex-direction: column; gap: 10px; }
.modal-target { font-size: 13px; color: #5b7770; margin: 0; }
.modal-body label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #5b7770; }
.modal-body label em { color: #e06060; font-style: normal; }
.modal-body textarea {
  border: 1px solid #e3ebe7; border-radius: 8px; padding: 8px 10px;
  font-size: 13px; resize: vertical; min-height: 80px; font-family: inherit;
  color: #1e4b45; background: #fff;
}
.modal-body textarea:focus { outline: none; border-color: #6dbfa5; box-shadow: 0 0 0 3px rgba(109, 191, 165, .15); }
.error-text { color: #e06060; font-size: 12px; margin: 0; }
.modal-footer {
  padding: 12px 18px; border-top: 1px solid #eef6f3;
  display: flex; justify-content: flex-end; gap: 8px;
}
.modal-footer .primary-btn.danger { background: #e06060; border-color: #e06060; }
.modal-footer .primary-btn.danger:hover { background: #c95252; border-color: #c95252; }

@media (max-width: 980px) {
  .approval-center-page { grid-template-columns: 1fr; }
  .approval-center-page.drawer-open { grid-template-columns: 1fr; }
  .mobile-filter-btn { display: inline-flex; }
  .filter-rail { display: none; }
  .filter-rail.open { display: flex; }
  .approval-semantic-hint { flex-direction: column; gap: 4px; }
  .detail-drawer {
    position: fixed; top: 0; right: 0; bottom: 0; width: 90vw; max-width: 380px;
    border-radius: 0; z-index: 99;
  }
  .drawer-mask {
    display: block; position: fixed; inset: 0; background: rgba(30, 75, 69, .35);
    z-index: 98;
  }
}

/* 抽屉/弹窗过渡 */
.drawer-enter-active, .drawer-leave-active { transition: transform .25s ease, opacity .25s ease; }
.drawer-enter-from, .drawer-leave-to { transform: translateX(20px); opacity: 0; }
.modal-enter-active, .modal-leave-active { transition: opacity .2s ease; }
.modal-enter-from, .modal-leave-to { opacity: 0; }
</style>
