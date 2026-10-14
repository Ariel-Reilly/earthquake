<script setup>
/**
 * 待办中心：四个分组汇总卡片 + 四个 Tab（待确认 / 待审批 / 待发布预警 / 待跟踪）。
 * 依赖 dashboardApi 中的 getTodoSummary / listMyConfirmations / listMyApprovals /
 *     listMyPendingWarnings / listMyOpenTasks；后端未实现时使用 requireBackend 兜底。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Inbox,
  CheckCircle2,
  Check,
  ClipboardCheck,
  ShieldCheck,
  Bell,
  Radar,
  Clock4,
  AlertTriangle,
  RefreshCw,
  Search,
  Send,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])
const router = useRouter()

const TABS = [
  { key: 'confirmations', label: '待我确认', icon: Inbox },
  { key: 'approvals', label: '待我审批', icon: ClipboardCheck },
  { key: 'warnings', label: '待发布预警', icon: Bell },
  { key: 'tasks', label: '待跟踪', icon: Radar },
]

const SUMMARY_DESCRIPTIONS = {
  confirmations: '等待你现场复核或签认的隐患事件。点击行项可直接进入确认工作台。',
  approvals: '等待你审批的预警发布 / 应急派单 / 预案启动等工单。',
  warnings: '已通过 AI 研判，等待正式发布的预警。请尽快确认发布渠道与时间。',
  tasks: '跟踪已派发的现场任务与已派发预警的执行进度。',
}

const summary = reactive({
  confirmation: 0,
  approval: 0,
  pendingWarnings: 0,
  openEmergency: 0,
})

const tabState = reactive({
  confirmations: { list: [], total: 0, loaded: false, loading: false, error: null },
  approvals: { list: [], total: 0, loaded: false, loading: false, error: null },
  warnings: { list: [], total: 0, loaded: false, loading: false, error: null },
  tasks: { list: [], total: 0, loaded: false, loading: false, error: null },
})

const activeTab = ref('confirmations')
const refreshing = ref(false)
const lastUpdated = ref(null)
let refreshTimer = null

const filters = reactive({
  confirmations: { level: 'all', range: 'all', keyword: '' },
  approvals: { level: 'all', range: 'all', keyword: '' },
  warnings: { level: 'all', range: 'all', keyword: '' },
  tasks: { level: 'all', range: 'all', keyword: '' },
})

const LEVEL_OPTIONS = [
  { value: 'all', label: '全部紧急度' },
  { value: 'red', label: '红色' },
  { value: 'orange', label: '橙色' },
  { value: 'yellow', label: '黄色' },
  { value: 'blue', label: '蓝色' },
]
const RANGE_OPTIONS = [
  { value: 'all', label: '全部时间' },
  { value: '24h', label: '近 24 小时' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
]

const LEVEL_VAR_MAP = {
  red: '#e06060',
  orange: '#cbaa76',
  yellow: '#d9c040',
  blue: '#6f9e7f',
}

function pickLevel(value) {
  const text = String(value ?? '')
  if (/红/.test(text)) {return 'red'}
  if (/橙/.test(text)) {return 'orange'}
  if (/黄/.test(text)) {return 'yellow'}
  if (/蓝/.test(text)) {return 'blue'}
  return 'blue'
}

function levelVar(level) {
  return LEVEL_VAR_MAP[pickLevel(level)] ?? '#6f9e7f'
}

function levelLabel(level) {
  const key = pickLevel(level)
  return { red: '红色', orange: '橙色', yellow: '黄色', blue: '蓝色' }[key]
}

function summaryColor(key) {
  switch (key) {
    case 'confirmation': return '#cbaa76'
    case 'approval': return '#6f9e7f'
    case 'pendingWarnings': return '#e06060'
    case 'openEmergency': return '#d9c040'
    default: return '#87b096'
  }
}

function summaryIcon(key) {
  switch (key) {
    case 'confirmation': return Inbox
    case 'approval': return ClipboardCheck
    case 'pendingWarnings': return Bell
    case 'openEmergency': return AlertTriangle
    default: return Radar
  }
}

const SUMMARY_CARDS = computed(() => [
  {
    key: 'confirmation',
    label: '待确认',
    value: summary.confirmation,
    suffix: '起',
    desc: '现场复核 / 签认',
    icon: summaryIcon('confirmation'),
    color: summaryColor('confirmation'),
    tab: 'confirmations',
  },
  {
    key: 'approval',
    label: '待审批',
    value: summary.approval,
    suffix: '项',
    desc: '等待我的审批决策',
    icon: summaryIcon('approval'),
    color: summaryColor('approval'),
    tab: 'approvals',
  },
  {
    key: 'pendingWarnings',
    label: '待发布预警',
    value: summary.pendingWarnings,
    suffix: '起',
    desc: 'AI 研判通过待发布',
    icon: summaryIcon('pendingWarnings'),
    color: summaryColor('pendingWarnings'),
    tab: 'warnings',
  },
  {
    key: 'openEmergency',
    label: '待处置应急',
    value: summary.openEmergency,
    suffix: '起',
    desc: '在处置流程中',
    icon: summaryIcon('openEmergency'),
    color: summaryColor('openEmergency'),
    tab: 'tasks',
  },
])

function withinRange(value, range) {
  if (!value || range === 'all') {return true}
  const ts = new Date(value).getTime()
  if (Number.isNaN(ts)) {return true}
  const now = Date.now()
  if (range === '24h') {return now - ts <= 24 * 60 * 60 * 1000}
  if (range === '7d') {return now - ts <= 7 * 24 * 60 * 60 * 1000}
  if (range === '30d') {return now - ts <= 30 * 24 * 60 * 60 * 1000}
  return true
}

function passFilter(item, f) {
  if (f.level !== 'all' && pickLevel(item.level) !== f.level) {return false}
  if (!withinRange(item.submittedAt ?? item.dueAt, f.range)) {return false}
  if (f.keyword) {
    const text = `${item.title ?? ''} ${item.point ?? ''} ${item.applicant ?? ''} ${item.assignee ?? ''}`.toLowerCase()
    if (!text.includes(f.keyword.toLowerCase().trim())) {return false}
  }
  return true
}

const visibleTabList = computed(() => {
  const state = tabState[activeTab.value]
  const f = filters[activeTab.value]
  return (state.list ?? []).filter((item) => passFilter(item, f))
})

const activeTabState = computed(() => tabState[activeTab.value])
const activeTabFilters = computed(() => filters[activeTab.value])

async function safeCall(key, fn) {
  tabState[key].loading = true
  tabState[key].error = null
  try {
    return await fn()
  } catch (err) {
    tabState[key].error = err?.message ?? '加载失败'
    return null
  } finally {
    tabState[key].loading = false
  }
}

async function loadSummary(showToast = false) {
  try {
    const data = await dashboardApi.getTodoSummary()
    Object.assign(summary, data ?? {})
  } catch (err) {
    // 后端未实现 / 离线模式：保持零值，由 UI 兜底文案
    summary.confirmation = summary.confirmation || 0
    summary.approval = summary.approval || 0
    summary.pendingWarnings = summary.pendingWarnings || 0
    summary.openEmergency = summary.openEmergency || 0
    if (showToast) {emit('notify', err?.message ?? '待办汇总加载失败')}
  }
  lastUpdated.value = new Date()
}

async function loadTab(key, { force = false, showToast = false } = {}) {
  if (!force && tabState[key].loaded && tabState[key].list.length) {return}
  const result = await safeCall(key, async () => {
    switch (key) {
      case 'confirmations': {return await dashboardApi.listMyConfirmations()}
      case 'approvals': {return await dashboardApi.listMyApprovals()}
      case 'warnings': {return await dashboardApi.listMyPendingWarnings()}
      case 'tasks': {return await dashboardApi.listMyOpenTasks()}
      default: {return { list: [], total: 0 }}
    }
  })
  if (result && Array.isArray(result.list)) {
    tabState[key].list = result.list
    tabState[key].total = result.total ?? result.list.length
    tabState[key].loaded = true
    if (showToast) {emit('notify', `${TABS.find((t) => t.key === key)?.label} 已刷新`)}
  } else if (!result) {
    tabState[key].list = []
    tabState[key].total = 0
    tabState[key].loaded = true
  }
}

async function refresh(silent = false) {
  refreshing.value = true
  await loadSummary(!silent)
  await loadTab('confirmations', { force: true, showToast: !silent })
  await loadTab('approvals', { force: true })
  await loadTab('warnings', { force: true })
  await loadTab('tasks', { force: true })
  refreshing.value = false
}

function switchTab(key) {
  if (activeTab.value === key) {return}
  activeTab.value = key
  loadTab(key)
}

function jumpToCard(tabKey) {
  activeTab.value = tabKey
  loadTab(tabKey)
}

function gotoConfirmation(item) {
  router.push(`/dashboard/confirmation-workbench/${item.id}`)
}

/**
 * v1.7.0：快捷确认（移动端优化）。
 * 后端实现 POST /api/confirmation/{id}/confirm 后替换此处 mock。
 * 当前仅前端乐观更新，刷新后恢复。
 */
function quickConfirm(item) {
  const idx = tabState.confirmations.list.findIndex((i) => String(i.id) === String(item.id))
  if (idx === -1) return
  // 乐观更新
  tabState.confirmations.list[idx] = { ...tabState.confirmations.list[idx], status: '已确认' }
  summary.confirmation = Math.max(0, summary.confirmation - 1)
  emit('notify', `已确认：${item.title}`)
}

function gotoApproval(item) {
  // 审批详情目前复用 warning detail（按工单类型分发到对应详情页）
  if (item.category === '应急派单') {
    router.push(`/dashboard/tasks/${item.id}`)
  } else {
    router.push(`/dashboard/warnings/${item.id}`)
  }
}

function gotoWarning(item) {
  router.push(`/dashboard/warnings/${item.id}`)
}

function gotoTask(item) {
  if (item.type === '预警') {
    router.push(`/dashboard/warnings/${item.id}`)
  } else {
    router.push(`/dashboard/tasks/${item.id}`)
  }
}

function gotoCreateWarning() {
  router.push('/dashboard/warnings/create')
}

function clearFilters() {
  const f = filters[activeTab.value]
  f.level = 'all'
  f.range = 'all'
  f.keyword = ''
}

function resetFilters() {
  Object.keys(filters).forEach((k) => clearFiltersFor(k))
}

function clearFiltersFor(key) {
  const f = filters[key]
  f.level = 'all'
  f.range = 'all'
  f.keyword = ''
}

function formatTime(value) {
  if (!value) {return '—'}
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {return String(value)}
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function progressColor(progress) {
  if (progress >= 80) {return '#87b096'}
  if (progress >= 50) {return '#6f9e7f'}
  if (progress >= 25) {return '#cbaa76'}
  return '#e06060'
}

onMounted(async () => {
  await refresh(true)
  refreshTimer = window.setInterval(() => {
    loadSummary(false)
  }, 60_000)
})

onBeforeUnmount(() => {
  if (refreshTimer) {window.clearInterval(refreshTimer)}
})
</script>

<template>
  <div class="todo-center-page">
    <!-- 顶部汇总卡 -->
    <div class="stats-grid">
      <div
        v-for="card in SUMMARY_CARDS"
        :key="card.key"
        class="stat-card summary-card"
        :class="{ active: activeTab === card.tab }"
        :style="{ '--accent': card.color }"
        @click="jumpToCard(card.tab)"
      >
        <div class="stat-icon" :style="{ background: card.color + '22', color: card.color }">
          <component :is="card.icon" :size="20" />
        </div>
        <div class="stat-copy">
          <span>{{ card.label }}</span>
          <strong>{{ card.value }} <small>{{ card.suffix }}</small></strong>
          <em class="positive">{{ card.desc }}</em>
        </div>
        <div class="summary-arrow" aria-hidden="true">›</div>
      </div>
    </div>

    <section class="panel system-panel">
      <div class="panel-header compact">
        <div class="heading-actions">
          <button class="secondary-btn" @click="resetFilters">重置筛选</button>
          <button class="primary-btn" :disabled="refreshing" @click="refresh(false)">
            <RefreshCw :size="14" />
            {{ refreshing ? '刷新中…' : '刷新' }}
          </button>
        </div>
      </div>

      <div class="todo-tabs">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          class="todo-tab"
          :class="{ active: activeTab === tab.key }"
          @click="switchTab(tab.key)"
        >
          <component :is="tab.icon" :size="15" />
          <span>{{ tab.label }}</span>
          <i v-if="tabState[tab.key].total">{{ tabState[tab.key].total }}</i>
        </button>
      </div>

      <!-- Tab 顶部说明 + 筛选条 -->
      <div class="todo-toolbar">
        <p class="todo-desc">
          <Clock4 :size="14" />
          <span>{{ SUMMARY_DESCRIPTIONS[activeTab] }}</span>
        </p>
        <div class="todo-filters">
          <label>
            <span>紧急度</span>
            <select v-model="activeTabFilters.level">
              <option v-for="opt in LEVEL_OPTIONS" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </option>
            </select>
          </label>
          <label>
            <span>时间</span>
            <select v-model="activeTabFilters.range">
              <option v-for="opt in RANGE_OPTIONS" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </option>
            </select>
          </label>
          <label class="keyword-input">
            <Search :size="14" />
            <input
              v-model="activeTabFilters.keyword"
              type="text"
              placeholder="搜索标题 / 地点 / 申请人"
            />
          </label>
          <button class="secondary-btn ghost" @click="clearFilters">清空</button>
        </div>
      </div>

      <!-- Tab 1：待我确认 -->
      <div v-if="activeTab === 'confirmations'" class="todo-list">
        <div v-if="activeTabState.loading" class="todo-empty">加载中…</div>
        <div v-else-if="!visibleTabList.length" class="todo-empty">
          <Inbox :size="22" />
          <p>暂无待办</p>
          <small>所有待确认事件已处理完毕</small>
        </div>
        <article
          v-for="item in visibleTabList"
          :key="item.id"
          class="todo-card confirmation"
          @click="gotoConfirmation(item)"
        >
          <div
            class="todo-colorbar"
            :style="{ background: levelVar(item.level) }"
            aria-hidden="true"
          ></div>
          <div class="todo-main">
            <div class="todo-title">
              <strong>{{ item.title }}</strong>
              <span class="level-pill" :style="{ color: levelVar(item.level), borderColor: levelVar(item.level) }">
                {{ levelLabel(item.level) }}
              </span>
              <span v-if="item.unread" class="unread-dot" title="未读">●</span>
            </div>
            <div class="todo-meta">
              <span>{{ item.point }}</span>
              <span class="dot">·</span>
              <span>上报人：{{ item.reporter ?? '—' }}</span>
              <span class="dot">·</span>
              <time>{{ formatTime(item.submittedAt) }}</time>
            </div>
            <div class="todo-status">
              <CheckCircle2 :size="13" />
              <span>{{ item.status ?? '待确认' }}</span>
            </div>
            <div class="todo-quick-actions" @click.stop>
              <button class="quick-btn success" @click.stop="quickConfirm(item)">
                <Check :size="11" />确认
              </button>
              <button class="quick-btn" @click.stop="gotoConfirmation(item)">详情</button>
            </div>
          </div>
        </article>
      </div>

      <!-- Tab 2：待我审批 -->
      <div v-else-if="activeTab === 'approvals'" class="todo-list">
        <div v-if="activeTabState.loading" class="todo-empty">加载中…</div>
        <div v-else-if="!visibleTabList.length" class="todo-empty">
          <ClipboardCheck :size="22" />
          <p>暂无待办</p>
          <small>当前没有等待审批的工单</small>
        </div>
        <article
          v-for="item in visibleTabList"
          :key="item.id"
          class="todo-card approval"
          @click="gotoApproval(item)"
        >
          <div class="approval-pie" :style="{ '--c': levelVar(item.level) }">
            <svg viewBox="0 0 36 36" width="40" height="40">
              <circle cx="18" cy="18" r="15" fill="none" stroke="rgba(60,60,67,.10)" stroke-width="3" />
              <circle
                cx="18"
                cy="18"
                r="15"
                fill="none"
                :stroke="levelVar(item.level)"
                stroke-width="3"
                stroke-dasharray="28 72"
                stroke-linecap="round"
                transform="rotate(-90 18 18)"
              />
            </svg>
            <span :style="{ color: levelVar(item.level) }">审</span>
          </div>
          <div class="todo-main">
            <div class="todo-title">
              <strong>{{ item.title }}</strong>
              <span class="category-pill">{{ item.category ?? '预警发布' }}</span>
              <span class="level-pill" :style="{ color: levelVar(item.level), borderColor: levelVar(item.level) }">
                {{ levelLabel(item.level) }}
              </span>
            </div>
            <div class="todo-meta">
              <span>申请人：{{ item.applicant ?? '—' }}</span>
              <span class="dot">·</span>
              <span>状态：{{ item.status }}</span>
              <span class="dot">·</span>
              <time>{{ formatTime(item.submittedAt) }}</time>
            </div>
          </div>
          <div class="todo-action">
            <button class="secondary-btn">查看详情</button>
          </div>
        </article>
      </div>

      <!-- Tab 3：待发布预警 -->
      <div v-else-if="activeTab === 'warnings'" class="todo-list">
        <div v-if="activeTabState.loading" class="todo-empty">加载中…</div>
        <div v-else-if="!visibleTabList.length" class="todo-empty">
          <Bell :size="22" />
          <p>暂无待办</p>
          <small>暂无待发布预警</small>
        </div>
        <article
          v-for="item in visibleTabList"
          :key="item.id"
          class="todo-card warning-row"
          :style="{ borderColor: levelVar(item.level) }"
          @click="gotoWarning(item)"
        >
          <div class="warning-icon" :style="{ background: levelVar(item.level) }">
            <Bell :size="16" />
          </div>
          <div class="todo-main">
            <div class="todo-title">
              <strong>{{ item.title }}</strong>
              <span class="level-pill" :style="{ color: levelVar(item.level), borderColor: levelVar(item.level) }">
                {{ levelLabel(item.level) }}预警
              </span>
            </div>
            <div class="todo-meta">
              <span>{{ item.point }}</span>
              <span class="dot">·</span>
              <span>状态：{{ item.status }}</span>
              <span class="dot">·</span>
              <span class="due">截止：{{ formatTime(item.dueAt) }}</span>
            </div>
          </div>
          <div class="todo-action">
            <button class="primary-btn" @click.stop="gotoCreateWarning">
              <Send :size="13" />
              发布
            </button>
          </div>
        </article>
      </div>

      <!-- Tab 4：待跟踪 -->
      <div v-else-if="activeTab === 'tasks'" class="todo-list">
        <div v-if="activeTabState.loading" class="todo-empty">加载中…</div>
        <div v-else-if="!visibleTabList.length" class="todo-empty">
          <Radar :size="22" />
          <p>暂无待办</p>
          <small>没有正在跟踪的任务或预警</small>
        </div>
        <article
          v-for="item in visibleTabList"
          :key="`${item.type}-${item.id}`"
          class="todo-card task"
          @click="gotoTask(item)"
        >
          <div class="task-side">
            <span class="type-pill" :class="item.type === '预警' ? 'warn' : 'task'">
              {{ item.type }}
            </span>
            <strong>{{ item.title }}</strong>
            <span class="todo-meta">{{ item.point }} · 负责人：{{ item.assignee ?? '—' }}</span>
          </div>
          <div class="task-progress">
            <div class="progress-meta">
              <span>进度</span>
              <strong :style="{ color: progressColor(item.progress ?? 0) }">{{ item.progress ?? 0 }}%</strong>
              <span class="status-text">{{ item.status }}</span>
            </div>
            <div class="progress-bar">
              <i :style="{ width: `${item.progress ?? 0}%`, background: progressColor(item.progress ?? 0) }"></i>
            </div>
            <div class="progress-meta sub">
              <span>截止：{{ formatTime(item.dueAt) }}</span>
            </div>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.todo-center-page { display: flex; flex-direction: column; gap: 15px; }

.summary-card { cursor: pointer; position: relative; }
.summary-card.active { border-color: var(--accent, var(--c-brand)); box-shadow: 0 6px 20px rgba(0, 0, 0, .08); }
.summary-arrow {
  position: absolute; right: 14px; top: 50%; transform: translateY(-50%);
  font-size: 22px; color: var(--c-text-3); transition: transform .2s ease;
}
.summary-card:hover .summary-arrow { transform: translateY(-50%) translateX(3px); color: var(--accent, var(--c-brand)); }

.todo-tabs {
  display: flex; gap: 8px; padding: 0 20px 14px;
  border-bottom: 1px dashed var(--c-separator); flex-wrap: wrap;
}
.todo-tab {
  display: inline-flex; align-items: center; gap: 6px;
  border: 1px solid var(--c-separator); background: var(--c-brand-mist); color: var(--c-ink-1);
  padding: 7px 14px; border-radius: var(--r-pill); cursor: pointer; font-size: 13px;
  transition: all .2s ease;
}
.todo-tab:hover { border-color: var(--c-brand-hover); color: var(--c-brand-deeper); }
.todo-tab.active { background: var(--c-brand-deeper); color: var(--c-on-brand); border-color: var(--c-brand-deeper); }
.todo-tab i {
  font-style: normal; font-size: 11px; padding: 1px 6px; border-radius: var(--r-pill);
  background: var(--glass-thin);
}
.todo-tab:not(.active) i { background: var(--c-separator); color: var(--c-ink-1); }

.todo-toolbar {
  display: flex; flex-wrap: wrap; gap: 12px; justify-content: space-between;
  padding: 14px 20px 6px;
}
.todo-desc {
  display: inline-flex; align-items: center; gap: 6px; margin: 0;
  color: var(--c-ink-2); font-size: 13px;
}
.todo-filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.todo-filters label {
  display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--c-ink-2);
}
.todo-filters select {
  border: 1px solid var(--c-separator); border-radius: var(--r-sm); padding: 5px 8px;
  background: var(--c-surface); color: var(--c-brand-deeper); font-size: 12px;
}
.todo-filters .keyword-input {
  position: relative; border: 1px solid var(--c-separator); border-radius: var(--r-sm); padding: 4px 8px 4px 24px;
  background: var(--c-surface);
}
.todo-filters .keyword-input svg { position: absolute; left: 8px; top: 50%; transform: translateY(-50%); color: var(--c-text-3); }
.todo-filters .keyword-input input {
  border: none; outline: none; font-size: 12px; min-width: 160px; background: transparent; color: var(--c-brand-deeper);
}
.secondary-btn.ghost { background: transparent; border-color: var(--c-separator); }

.todo-list {
  display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; padding: 16px 20px 20px;
}
@media (max-width: 980px) { .todo-list { grid-template-columns: 1fr; } }

.todo-empty {
  grid-column: 1 / -1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 4px; padding: 40px 20px; color: var(--c-text-3); text-align: center;
}
.todo-empty p { margin: 4px 0 0; font-size: 14px; color: var(--c-ink-1); }
.todo-empty small { color: var(--c-text-3); font-size: 12px; }

.todo-card {
  display: flex; gap: 12px; align-items: stretch;
  border: 1px solid var(--c-separator); border-radius: var(--r-md); padding: 12px 14px;
  background: var(--c-surface); cursor: pointer; transition: all .2s ease;
}
.todo-card:hover { border-color: var(--c-brand-hover); box-shadow: 0 6px 18px rgba(0, 0, 0, .06); transform: translateY(-1px); }

.todo-colorbar {
  width: 4px; border-radius: var(--r-xs); flex-shrink: 0;
}
.todo-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.todo-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.todo-title strong { font-size: 14px; color: var(--c-brand-deeper); }
.level-pill {
  font-size: 11px; padding: 2px 8px; border-radius: var(--r-pill);
  border: 1px solid currentColor; font-weight: 500;
}
.category-pill {
  font-size: 11px; padding: 2px 8px; border-radius: var(--r-pill);
  background: var(--c-brand-soft); color: var(--c-ink-1); border: 1px solid var(--c-separator);
}
.unread-dot { color: var(--c-danger); font-size: 12px; }

.todo-meta { font-size: 12px; color: var(--c-ink-2); display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
.todo-meta .dot { color: var(--c-text-4); padding: 0 2px; }
.todo-meta .due { color: var(--c-warning); }

.todo-status {
  display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--c-brand-hover);
}

/* v1.7.0 快捷确认按钮（移动端优化） */
.todo-quick-actions {
  display: none;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed var(--c-separator);
  flex-shrink: 0;
}
.todo-card:hover .todo-quick-actions,
.todo-quick-actions { display: flex; } /* 始终显示，不依赖 hover */
.quick-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 10px;
  border-radius: var(--r-sm);
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  color: var(--c-ink-1);
  font-size: 11.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}
.quick-btn:hover { background: var(--c-surface-2); }
.quick-btn.success { background: var(--c-success-strong); color: var(--c-on-brand); border-color: var(--c-success-strong); }
.quick-btn.success:hover { background: var(--c-success-strong); }

@media (max-width: 520px) {
  /* 小屏：卡片竖向排列，让快捷按钮更醒目 */
  .todo-card { flex-direction: column; }
  .todo-card.confirmation { padding-left: 14px; }
  .todo-quick-actions { display: flex; }
}

/* 待我确认（左侧 colorbar） */
.todo-card.confirmation { padding-left: 14px; align-items: center; }

/* 待我审批（饼形 row） */
.todo-card.approval { align-items: center; }
.approval-pie {
  position: relative; width: 40px; height: 40px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
}
.approval-pie span {
  position: absolute; font-size: 12px; font-weight: 600;
}
.todo-action {
  display: flex; align-items: center; gap: 6px; flex-shrink: 0;
}

/* 待发布预警 */
.todo-card.warning-row {
  border-left: 4px solid; padding-left: 12px; align-items: center;
}
.warning-icon {
  width: 32px; height: 32px; border-radius: var(--r-sm);
  display: inline-flex; align-items: center; justify-content: center; color: var(--c-on-brand);
  flex-shrink: 0;
}

/* 待跟踪 */
.todo-card.task {
  flex-direction: column; gap: 10px; align-items: stretch;
}
.task-side { display: flex; flex-direction: column; gap: 4px; }
.type-pill {
  align-self: flex-start; font-size: 11px; padding: 2px 8px; border-radius: var(--r-sm);
  background: var(--c-brand-soft); color: var(--c-ink-1); border: 1px solid var(--c-separator);
}
.type-pill.warn { background: var(--c-danger-bg); color: var(--c-danger); border-color: var(--c-danger-bg); }

.task-progress { display: flex; flex-direction: column; gap: 4px; }
.progress-meta { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--c-ink-2); }
.progress-meta strong { font-size: 13px; }
.progress-meta .status-text { color: var(--c-brand-hover); }
.progress-meta.sub { font-size: 11px; color: var(--c-text-3); }
.progress-bar {
  height: 6px; border-radius: var(--r-pill); background: var(--c-brand-soft); overflow: hidden;
}
.progress-bar i {
  display: block; height: 100%; border-radius: var(--r-pill); transition: width .3s ease;
}

@media (max-width: 720px) {
  .todo-tabs { overflow-x: auto; flex-wrap: nowrap; }
  .todo-toolbar { flex-direction: column; align-items: stretch; }
  .todo-filters { justify-content: flex-start; }
}
</style>
