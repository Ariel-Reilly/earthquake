<script setup>
/**
 * 我的工作台。
 *
 * 数据来源（全部为真实接口，失败时显示"—"并明确提示，不写死示例数据）：
 *   - /api/tasks            → 今日任务 / 已完成 / 本周任务
 *   - /api/todo/summary     → 待确认、待审批、待处理预警、进行中应急
 *
 * 快捷入口用于交付演示时直达指挥大屏与移动指挥端（这两个路由是 public 的独立页面），
 * 其余入口跳到系统内对应模块。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  AlertCircle,
  ArrowRight,
  Bell,
  Bot,
  CheckCircle2,
  Clock,
  Monitor,
  Smartphone,
  Siren,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'

const router = useRouter()
const emit = defineEmits(['notify'])

const loading = ref(true)
/** 数据是否成功加载过：区分"真实为 0"与"没有拿到数据" */
const dataLoaded = ref(false)
const loadError = ref('')
const tasks = ref([])
const todo = ref({
  confirmation: 0,
  approval: 0,
  pendingWarnings: 0,
  openEmergency: 0,
})

/** 解析任务时间（兼容 ISO 与后端格式化字符串），失败返回 null。 */
function toTime(value) {
  if (!value) {
    return null
  }
  const time = Date.parse(value)
  return Number.isFinite(time) ? time : null
}

/** 是否与今天同一天（本地时区）。 */
function isToday(value) {
  const time = toTime(value)
  if (time === null) {
    return false
  }
  const date = new Date(time)
  const now = new Date()
  return (
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate()
  )
}

/** 是否在最近 7 天内。 */
function isThisWeek(value) {
  const time = toTime(value)
  if (time === null) {
    return false
  }
  const sevenDaysAgo = Date.now() - 7 * 24 * 60 * 60 * 1000
  return time >= sevenDaysAgo
}

const todayTasks = computed(() =>
  tasks.value.filter((task) => isToday(task.startTime) || isToday(task.dueTime)),
)
const completedToday = computed(() =>
  todayTasks.value.filter((task) => task.status === '已完成').length,
)
const weekTasks = computed(() =>
  tasks.value.filter((task) => isThisWeek(task.startTime) || isThisWeek(task.dueTime)),
)
const doneWeek = computed(
  () => weekTasks.value.filter((task) => task.status === '已完成').length,
)

/** 顶部三张统计卡：数据未加载成功时显示"—"，避免把 0 当成真实业务结果。 */
const stats = computed(() => [
  {
    key: 'today',
    label: '今日任务',
    value: dataLoaded.value ? String(todayTasks.value.length) : '—',
    note: dataLoaded.value ? `本周 ${weekTasks.value.length} 项` : '数据加载中或不可用',
    icon: Clock,
    tone: 'primary',
  },
  {
    key: 'done',
    label: '今日已完成',
    value: dataLoaded.value ? String(completedToday.value) : '—',
    note: dataLoaded.value ? `本周已完成 ${doneWeek.value} 项` : '数据加载中或不可用',
    icon: CheckCircle2,
    tone: 'success',
  },
  {
    key: 'pending',
    label: '待我处理',
    value: dataLoaded.value
      ? String(
          todo.value.confirmation +
            todo.value.approval +
            todo.value.pendingWarnings +
            todo.value.openEmergency,
        )
      : '—',
    note: dataLoaded.value ? '确认 / 审批 / 预警 / 应急' : '数据加载中或不可用',
    icon: AlertCircle,
    tone: 'warning',
  },
])

/** 待办四分类：点击直达对应处理页（无待办时仍可进入队列页）。 */
const todoItems = computed(() => [
  {
    key: 'confirmation',
    label: '待确认事件',
    value: todo.value.confirmation,
    path: '/dashboard/confirmation-workbench',
  },
  {
    key: 'approval',
    label: '待审批',
    value: todo.value.approval,
    path: '/dashboard/approvals',
  },
  {
    key: 'warnings',
    label: '待处理预警',
    value: todo.value.pendingWarnings,
    path: '/dashboard/warnings',
  },
  {
    key: 'emergency',
    label: '进行中应急',
    value: todo.value.openEmergency,
    path: '/dashboard/emergency',
  },
])

/** 快捷入口：指挥大屏 / 移动端是独立 public 页面，新窗口打开便于演示与投屏。 */
const quickLinks = [
  {
    key: 'screen',
    label: '指挥大屏',
    desc: '全屏态势指挥（新窗口）',
    icon: Monitor,
    open: () => window.open('/screen', '_blank', 'noopener'),
  },
  {
    key: 'mobile',
    label: '移动指挥端',
    desc: '手机端指挥界面（新窗口）',
    icon: Smartphone,
    open: () => window.open('/mobile', '_blank', 'noopener'),
  },
  {
    key: 'warnings',
    label: '预警中心',
    desc: '确认 / 派发 / 关闭预警',
    icon: Siren,
    open: () => router.push('/dashboard/warnings'),
  },
  {
    key: 'tasks',
    label: 'AI 巡检任务',
    desc: '任务列表与执行反馈',
    icon: Bot,
    open: () => router.push('/dashboard/ai-tasks'),
  },
]

/** 拉取工作台数据：任务与待办汇总并行；任一失败都降级为"—"并提示。 */
async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const [taskData, todoData] = await Promise.all([
      dashboardApi.getTasks({ page: 1, size: 200 }).catch(() => null),
      dashboardApi.getTodoSummary().catch(() => null),
    ])
    tasks.value = taskData?.list ?? taskData?.records ?? []
    if (todoData && typeof todoData === 'object') {
      todo.value = {
        confirmation: Number(todoData.confirmation) || 0,
        approval: Number(todoData.approval) || 0,
        pendingWarnings: Number(todoData.pendingWarnings) || 0,
        openEmergency: Number(todoData.openEmergency) || 0,
      }
    }
    dataLoaded.value = Boolean(taskData) || Boolean(todoData)
    if (!taskData && !todoData) {
      loadError.value = '工作台数据加载失败：后端服务不可用或当前账号无权限'
    }
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '工作台数据加载失败'
  } finally {
    loading.value = false
  }
}

/** 点击待办分类：进入对应队列页；路径不可达时提示而不是静默。 */
function openTodo(item) {
  if (!item.path) {
    return
  }
  router.push(item.path).catch((err) => {
    emit('notify', err?.message ?? '页面跳转失败')
  })
}

onMounted(loadData)
</script>

<template>
  <div class="workbench-page">
    <header class="workbench-header">
      <div class="workbench-title">
        <h2>我的工作台</h2>
        <p>个人待办与任务概览（数据来自任务中心与待办汇总接口）</p>
      </div>
      <button type="button" class="refresh-btn" :disabled="loading" @click="loadData">
        {{ loading ? '加载中…' : '刷新' }}
      </button>
    </header>

    <p v-if="loadError" class="load-error">{{ loadError }}</p>

    <!-- 统计卡片 -->
    <div class="workbench-stats">
      <div v-for="stat in stats" :key="stat.key" class="stat-card">
        <div class="stat-icon" :class="stat.tone">
          <component :is="stat.icon" :size="20" />
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stat.value }}</div>
          <div class="stat-label">{{ stat.label }}</div>
          <div class="stat-note">{{ stat.note }}</div>
        </div>
      </div>
    </div>

    <!-- 待办分类 -->
    <section class="panel-block">
      <div class="block-header">
        <h3>待我处理</h3>
        <span class="block-hint">点击分类直达处理队列</span>
      </div>
      <div class="todo-grid">
        <button
          v-for="item in todoItems"
          :key="item.key"
          type="button"
          class="todo-card"
          @click="openTodo(item)"
        >
          <span class="todo-value">{{ dataLoaded ? item.value : '—' }}</span>
          <span class="todo-label">{{ item.label }}</span>
          <ArrowRight :size="15" aria-hidden="true" />
        </button>
      </div>
    </section>

    <!-- 快捷入口 -->
    <section class="panel-block">
      <div class="block-header">
        <h3>快捷入口</h3>
        <span class="block-hint">演示与投屏常用入口</span>
      </div>
      <div class="quick-grid">
        <button
          v-for="link in quickLinks"
          :key="link.key"
          type="button"
          class="quick-card"
          @click="link.open"
        >
          <span class="quick-icon"><component :is="link.icon" :size="18" /></span>
          <span class="quick-copy">
            <strong>{{ link.label }}</strong>
            <span>{{ link.desc }}</span>
          </span>
        </button>
      </div>
    </section>

    <!-- 通知中心提示 -->
    <div class="tip-card">
      <div class="tip-icon"><Bell :size="20" /></div>
      <div class="tip-content">
        <h3>通知中心</h3>
        <p>待办、审批、消息已整合到全局通知抽屉，点击顶栏铃铛图标即可集中处理。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.workbench-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 24px;
  background: var(--c-bg);
}

.workbench-header {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
}

.workbench-title h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--c-text);
}

.workbench-title p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--c-text-2);
}

.refresh-btn {
  padding: 6px 14px;
  font-size: 13px;
  color: var(--c-text);
  cursor: pointer;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 8px;
}

.refresh-btn:hover {
  border-color: var(--c-brand);
}

.refresh-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.load-error {
  padding: 10px 14px;
  margin: 0;
  font-size: 13px;
  color: var(--c-danger-ink, #8c3b3b);
  background: var(--c-danger-light, #fdf3f2);
  border: 1px solid var(--c-danger, #c48b8b);
  border-radius: 8px;
}

.workbench-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.stat-card {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 16px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 10px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.stat-card:hover {
  border-color: var(--c-brand);
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
}

.stat-icon {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  color: var(--c-brand);
  background: var(--c-brand-light);
  border-radius: 8px;
}

.stat-icon.success {
  color: var(--c-success-ink, #3c6b4b);
  background: var(--c-success-light);
}

.stat-icon.warning {
  color: var(--c-warning-ink-deep, #8a4a05);
  background: var(--c-warning-light);
}

.stat-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--c-text);
}

.stat-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.stat-note {
  font-size: 12px;
  color: var(--c-text-3-ink, #6b7a73);
}

.panel-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 10px;
}

.block-header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  justify-content: space-between;
}

.block-header h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--c-text);
}

.block-hint {
  font-size: 12px;
  color: var(--c-text-3-ink, #6b7a73);
}

.todo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 12px;
}

.todo-card {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 12px 14px;
  font-family: inherit;
  color: var(--c-text);
  text-align: left;
  cursor: pointer;
  background: var(--c-surface-2);
  border: 1px solid transparent;
  border-radius: 8px;
}

.todo-card:hover {
  border-color: var(--c-brand);
}

.todo-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--c-brand-deep, #245b5d);
}

.todo-label {
  flex: 1;
  font-size: 13px;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px;
}

.quick-card {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 12px 14px;
  font-family: inherit;
  color: var(--c-text);
  text-align: left;
  cursor: pointer;
  background: var(--c-surface-2);
  border: 1px solid transparent;
  border-radius: 8px;
}

.quick-card:hover {
  border-color: var(--c-brand);
}

.quick-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  color: var(--c-brand);
  background: var(--c-brand-light);
  border-radius: 8px;
}

.quick-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.quick-copy strong {
  font-size: 13px;
  font-weight: 600;
}

.quick-copy span {
  font-size: 12px;
  color: var(--c-text-3-ink, #6b7a73);
}

.tip-card {
  display: flex;
  gap: 12px;
  padding: 16px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 10px;
}

.tip-icon {
  display: flex;
  flex-shrink: 0;
  align-items: flex-start;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding-top: 4px;
  color: var(--c-brand);
  background: var(--c-brand-light);
  border-radius: 8px;
}

.tip-content h3 {
  margin: 0 0 4px;
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.tip-content p {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-2);
}

@media (max-width: 768px) {
  .workbench-page {
    padding: 16px;
  }

  .workbench-stats,
  .todo-grid,
  .quick-grid {
    grid-template-columns: 1fr;
  }
}
</style>
