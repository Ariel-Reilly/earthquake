<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Activity,
  AlertTriangle,
  BarChart3,
  Bell,
  BellRing,
  BrainCircuit,
  ChevronDown,
  ChevronRight,
  ChevronsLeft,
  CircleHelp,
  ClipboardCheck,
  Cloud,
  CloudRain,
  Database,
  FileBarChart,
  FileSignature,
  FileText,
  FileWarning,
  GitMerge,
  Globe2,
  HardDrive,
  HardHat,
  HelpCircle,
  History,
  Inbox,
  Layers,
  LayoutDashboard,
  LifeBuoy,
  ListChecks,
  LogOut,
  Map as MapIcon,
  MapPin,
  MessageCircle,
  MessageSquare,
  Mountain,
  Package,
  Radio,
  Route,
  ScrollText,
  Search,
  Settings,
  ShieldCheck,
  Sparkles,
  Truck,
  TrendingUp,
  UserCheck,
  UserCircle,
  Users,
} from 'lucide-vue-next'
import { dashboardMenu, NAV_GROUPS, moduleMeta } from '../data/dashboardData'
import PermissionButton from '../components/PermissionButton.vue'
import FormModal from '../components/FormModal.vue'
import AppFeedback from '../components/AppFeedback.vue'
import ServiceHealthBanner from '../components/ServiceHealthBanner.vue'
import AnimatedSidebarBackground from '../components/AnimatedSidebarBackground.vue'
import AnimatedDashboardBackground from '../components/AnimatedDashboardBackground.vue'
import { dashboardApi, logoutRequest } from '../services/dashboardApi'
import { stopRealtimeSync } from '../services/realtimeService'
import { startAiHealthCheck, stopAiHealthCheck } from '../services/_architecture/core/healthService'
import { clearAuthSession, getAllowedMenuPaths, getAuthUser, getRoleLabel, hasPermission } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const showNotifications = ref(false)
const showSearch = ref(false)
const searchTerm = ref('')
const toast = ref('')
const taskModal = ref(false)
const logoutModal = ref(false)
const loggingOut = ref(false)
const sidebarCollapsed = ref(window.localStorage.getItem('geosentry_sidebar_collapsed') === '1')
const currentTime = ref(new Date())
const user = ref(getAuthUser() ?? { name: '李晨曦', role: 'admin' })
const taskForm = reactive({
  name: '雨后重点灾害点复查',
  scope: '全部高风险灾害点',
  dueDate: '2026-08-10',
  dueTime: '18:00',
  hazardId: null,
  hazardCode: '',
  hazardLon: null,
  hazardLat: null,
})
const feedback = reactive({ loading: false, message: '', error: '', success: '' })
// 动态红点（待确认数 / 待发布预警 / 消息 / 待办 / 待审批）
const dynamicBadges = reactive({
  '/dashboard/confirmation-workbench': '0',
  '/dashboard/warnings': '0',
  '/dashboard/message-center': '0',
  '/dashboard/todo': '0',
  '/dashboard/approvals': '0',
})
let timerId
let feedbackTimer
let badgeTimer

const currentPath = computed(() => route.path)
const userRoleLabel = computed(() => getRoleLabel(user.value.role))
const visibleMenu = computed(() =>
  dashboardMenu.filter((item) => getAllowedMenuPaths(user.value.role).includes(item.path))
)

// 按 NAV_GROUPS 分组后的菜单（仅保留当前角色可见的）
const groupedMenu = computed(() => {
  const map = new Map()
  for (const g of NAV_GROUPS) map.set(g.id, { ...g, items: [] })
  for (const item of visibleMenu.value) {
    const bucket = map.get(item.group)
    if (bucket) bucket.items.push(item)
  }
  return [...map.values()]
    .filter((g) => g.items.length)
    .map((g) => ({ ...g, items: g.items.sort((a, b) => (a.order ?? 0) - (b.order ?? 0)) }))
})

const iconComponentMap = {
  Activity,
  AlertTriangle,
  BarChart3,
  Bell,
  BellRing,
  BrainCircuit,
  ChevronDown,
  ChevronRight,
  ChevronsLeft,
  CircleHelp,
  ClipboardCheck,
  Cloud,
  CloudRain,
  Database,
  FileBarChart,
  FileText,
  FileSignature,
  FileWarning,
  GitMerge,
  Globe2,
  HardDrive,
  HardHat,
  HelpCircle,
  History,
  Inbox,
  Layers,
  LayoutDashboard,
  LifeBuoy,
  ListChecks,
  LogOut,
  Map: MapIcon,
  MapPin,
  MessageCircle,
  MessageSquare,
  Mountain,
  Package,
  Radio,
  Route,
  Settings,
  ShieldCheck,
  Sparkles,
  Truck,
  TrendingUp,
  UserCheck,
  UserCircle,
  Users,
  ScrollText,
}

function pickIcon(name) {
  return iconComponentMap[name] ?? Activity
}

const activeMenu = computed(() => {
  const all = visibleMenu.value
  return (
    all.find((item) =>
      item.path === '/dashboard' ? currentPath.value === '/dashboard' : currentPath.value.startsWith(item.path)
    ) ?? all[0] ?? dashboardMenu[0]
  )
})

const activeGroupLabel = computed(() => {
  if (!activeMenu.value) return ''
  const group = NAV_GROUPS.find((g) => g.id === activeMenu.value.group)
  return group?.label ?? ''
})

const topHeaderEyebrow = computed(() => {
  const meta = moduleMeta[route.path] ?? moduleMeta[activeMenu.value?.path]
  return meta?.title ?? ''
})
const topHeaderDesc = computed(() => {
  const meta = moduleMeta[route.path] ?? moduleMeta[activeMenu.value?.path]
  return meta?.desc ?? ''
})

const timeLabel = computed(() =>
  currentTime.value.toLocaleTimeString('zh-CN', { hour12: false })
)
const currentLeafLabel = computed(() => {
  const segments = route.path.split('/').filter(Boolean)
  if (!segments.length) return ''
  if (route.path.includes('/hazards/')) return '灾害点详情'
  if (route.path.includes('/devices/')) return '设备详情'
  if (route.path.includes('/tasks/')) return '任务详情'
  if (route.path.includes('/warnings/')) {
    if (route.path.endsWith('/create')) return '新建预警'
    return '预警详情'
  }
  return decodeURIComponent(segments[segments.length - 1])
})

function notify(message) {
  toast.value = message
  window.clearTimeout(timerId)
  timerId = window.setTimeout(() => { toast.value = '' }, 2600)
}

function setFeedback({ loading = false, message = '', error = '', success = '' }) {
  feedback.loading = loading
  feedback.message = message
  feedback.error = error
  feedback.success = success
  window.clearTimeout(feedbackTimer)
  if (error || success || message) {
    feedbackTimer = window.setTimeout(() => {
      feedback.loading = false
      feedback.message = ''
      feedback.error = ''
      feedback.success = ''
    }, loading ? 2200 : 2600)
  }
}

async function runAction(action, { loadingText, successText, failureText }) {
  setFeedback({ loading: true, message: loadingText })
  try {
    const result = await action()
    setFeedback({ success: successText })
    notify(successText)
    return result
  } catch (error) {
    const message = error instanceof Error ? error.message : failureText
    setFeedback({ error: message })
    notify(message)
    throw error
  }
}

function goTo(path) {
  router.push(path)
  showSearch.value = false
  showNotifications.value = false
}

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  window.localStorage.setItem('geosentry_sidebar_collapsed', sidebarCollapsed.value ? '1' : '0')
}

function openLogoutModal() {
  logoutModal.value = true
}

function closeLogoutModal() {
  if (loggingOut.value) return
  logoutModal.value = false
}

async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  setFeedback({ loading: true, message: '正在退出登录...' })

  try {
    await logoutRequest()
  } catch {
    // 忽略后端登出失败，保证本地退出始终可用
  } finally {
    stopRealtimeSync({ keepListeners: false })
    clearAuthSession()
    logoutModal.value = false
    loggingOut.value = false
    setFeedback({})
    router.replace('/login')
  }
}

async function submitTask() {
  await runAction(
    () => dashboardApi.createTask({
      name: taskForm.name,
      type: taskForm.scope,
      due: taskForm.dueTime,
      status: '待处理',
      progress: 0,
      hazardId: taskForm.hazardId,
      hazardCode: taskForm.hazardCode,
      hazardLon: taskForm.hazardLon,
      hazardLat: taskForm.hazardLat,
    }),
    { loadingText: '巡检任务创建中...', successText: '巡检任务已创建', failureText: '创建巡检任务失败' }
  )
  taskModal.value = false
  resetTaskForm()
}

function resetTaskForm() {
  taskForm.name = '雨后重点灾害点复查'
  taskForm.scope = '全部高风险灾害点'
  taskForm.dueDate = '2026-08-10'
  taskForm.dueTime = '18:00'
  taskForm.hazardId = null
  taskForm.hazardCode = ''
  taskForm.hazardLon = null
  taskForm.hazardLat = null
}

function openCreateTaskModal() {
  resetTaskForm()
  taskModal.value = true
}

function prefillTaskForm(payload = {}) {
  resetTaskForm()
  if (payload.hazardId) {
    taskForm.name = payload.name ?? `${payload.scope ?? ''} 巡检`
    taskForm.scope = payload.scope ?? taskForm.scope
    taskForm.hazardId = payload.hazardId
    taskForm.hazardCode = payload.hazardCode ?? ''
    taskForm.hazardLon = payload.hazardLon ?? null
    taskForm.hazardLat = payload.hazardLat ?? null
  }
  taskModal.value = true
}

/**
 * 动态红点加载：拉取后端"待确认数 / 待发布预警数 / 未读消息数"。
 * 失败时静默 keep stale 而非报错（仪表盘快速加载优先）。
 */
async function refreshDynamicBadges() {
  try {
    const [confirm, warn, msg, todoSummary, approvalStat] = await Promise.all([
      dashboardApi.getConfirmationQueueStats?.().catch(() => null),
      dashboardApi.getWarningStats?.().catch(() => null),
      dashboardApi.getMessageStats?.().catch(() => null),
      // P2-1：统一待办汇总 → 顶栏红点接齐
      dashboardApi.getTodoSummary?.().catch(() => null),
      // 审批中心独立统计（与 todoSummary 中的 approval 字段相互兜底）
      dashboardApi.getApprovalStats?.().catch(() => null),
    ])
    if (confirm && typeof confirm.pending === 'number') {
      dynamicBadges['/dashboard/confirmation-workbench'] = String(confirm.pending)
    }
    if (warn && typeof warn.pending === 'number') {
      dynamicBadges['/dashboard/warnings'] = String(warn.pending)
    }
    if (msg && typeof msg.unread === 'number') {
      dynamicBadges['/dashboard/message-center'] = String(msg.unread)
    }
    // 待办红点：取四个子计数之和，避免任何子项为零被吞
    if (todoSummary && typeof todoSummary === 'object') {
      const todoTotal =
        (Number(todoSummary.confirmation) || 0) +
        (Number(todoSummary.approval) || 0) +
        (Number(todoSummary.pendingWarnings) || 0) +
        (Number(todoSummary.openEmergency) || 0)
      dynamicBadges['/dashboard/todo'] = String(todoTotal)
      // 审批中心：优先用 approvalStat.pending，否则用 todoSummary.approval 兜底
      const approvalPending =
        (approvalStat && typeof approvalStat.pending === 'number'
          ? Number(approvalStat.pending)
          : Number(todoSummary.approval) || 0)
      dynamicBadges['/dashboard/approvals'] = String(approvalPending)
    } else if (approvalStat && typeof approvalStat.pending === 'number') {
      // todo 不可达但 approval 可达 → 退化为审批单独红点
      dynamicBadges['/dashboard/approvals'] = String(approvalStat.pending)
      dynamicBadges['/dashboard/todo'] = String(approvalStat.pending)
    }
  } catch {
    // 后端不可用时不影响主流程
  }
}

watch(
  () => route.path,
  () => {
    showSearch.value = false
    showNotifications.value = false
  }
)

onMounted(() => {
  timerId = window.setInterval(() => {
    currentTime.value = new Date()
  }, 1000)
  // 动态红点：进入页面拉一次，此后每 60s 拉一次
  refreshDynamicBadges()
  badgeTimer = window.setInterval(refreshDynamicBadges, 60_000)
  // P1：启动 AI 服务健康探活（30s 一次，连续 3 次失败进入 down 态触发横幅）
  startAiHealthCheck({ interval: 30_000, immediate: true })
})

onBeforeUnmount(() => {
  window.clearInterval(timerId)
  window.clearInterval(badgeTimer)
  window.clearTimeout(feedbackTimer)
  stopAiHealthCheck()
})
</script>

<template>
  <div class="app-shell">
    <ServiceHealthBanner />
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <AnimatedSidebarBackground />
      <div class="sidebar-content">
        <div class="brand">
          <div class="brand-mark"><Mountain :size="22" :stroke-width="2.4" /></div>
          <div class="brand-copy"><strong>地脉智巡</strong><span>GEO-SENTRY v1.7</span></div>
          <button class="sidebar-collapse-btn" :title="sidebarCollapsed ? '展开侧边栏' : '折叠侧边栏'" @click="toggleSidebar">
            <ChevronsLeft :size="17" />
          </button>
        </div>

        <div class="workspace-switcher">
          <div class="workspace-avatar">应</div>
          <div class="workspace-copy"><strong>应急管理中心</strong><span>{{ userRoleLabel }}</span></div>
          <ChevronDown :size="16" />
        </div>

        <nav class="main-nav">
          <template v-for="group in groupedMenu" :key="group.id">
            <div class="nav-label">{{ group.label }}</div>
            <button
              v-for="item in group.items"
              :key="item.path"
              class="nav-item"
              :class="{ active: currentPath === item.path || (item.path !== '/dashboard' && currentPath.startsWith(item.path)) }"
              :title="sidebarCollapsed ? item.label : undefined"
              @click="goTo(item.path)"
            >
              <component :is="pickIcon(item.icon)" :size="18" />
              <span>{{ item.label }}</span>
              <em
                v-if="dynamicBadges[item.path] && +dynamicBadges[item.path] > 0"
                :class="{ danger: item.danger }"
              >{{ dynamicBadges[item.path] }}</em>
              <em v-else-if="item.badge" :class="{ danger: item.danger }">{{ item.badge }}</em>
            </button>
          </template>
        </nav>

        <div class="sidebar-footer">
          <button class="nav-item" :class="{ active: route.path === '/dashboard/settings' }" title="系统设置" @click="goTo('/dashboard/settings')"><Settings :size="18" /><span>系统设置</span></button>
          <button class="nav-item" title="帮助中心"><CircleHelp :size="18" /><span>帮助中心</span></button>
          <button class="nav-item" title="退出登录" @click="openLogoutModal"><LogOut :size="18" /><span>退出登录</span></button>
          <div class="online-card">
            <span class="pulse-dot"></span>
            <span>数据服务正常</span>
            <small>延迟 42ms</small>
          </div>
        </div>
      </div>
    </aside>

    <main class="main-content">
      <AnimatedDashboardBackground />
      <header class="topbar">
        <div class="breadcrumb">
          <span>{{ activeGroupLabel }}</span>
          <ChevronRight :size="14" />
          <strong>{{ activeMenu.label }}</strong>
          <template v-if="route.path !== activeMenu.path">
            <ChevronRight :size="14" />
            <strong class="breadcrumb-leaf">{{ currentLeafLabel }}</strong>
          </template>
        </div>

        <div class="top-actions">
          <PermissionButton
            v-if="hasPermission(user.role, 'overview:export')"
            permission="overview:export"
            :icon="FileText"
            class="topbar-action"
            @click="async ({ allowed, reason }) => {
              if (!allowed) return notify(reason)
              await runAction(() => dashboardApi.exportOverviewReport(), {
                loadingText: '报表生成中...',
                successText: '报告正在生成，请稍候',
                failureText: '导出失败',
              })
            }"
          >导出报告</PermissionButton>
          <PermissionButton
            v-if="hasPermission(user.role, 'ai:task:create')"
            permission="ai:task:create"
            variant="primary"
            :icon="ListChecks"
            class="topbar-action"
            @click="({ allowed, reason }) => allowed ? openCreateTaskModal() : notify(reason)"
          >创建巡检任务</PermissionButton>
          <button class="icon-btn" @click="showSearch = !showSearch"><Search :size="18" /></button>
          <button
            class="icon-btn notification-btn"
            @click="showNotifications = !showNotifications"
          >
            <Bell :size="18" />
            <i v-if="+dynamicBadges['/dashboard/message-center'] > 0"></i>
          </button>
          <div class="user-profile">
            <div class="user-avatar">李</div>
            <div><strong>{{ user.name }}</strong><span>{{ userRoleLabel }}</span></div>
            <button class="icon-btn" @click="openLogoutModal" title="退出登录"><ChevronDown :size="15" /></button>
          </div>
        </div>

        <Transition name="drop">
          <div v-if="showSearch" class="search-popover">
            <Search :size="17" />
            <input v-model="searchTerm" autofocus placeholder="搜索灾害点、任务、设备或预警" @keyup.enter="notify('已更新搜索条件')" />
          </div>
        </Transition>

        <Transition name="drop">
          <div v-if="showNotifications" class="notification-popover">
            <div class="notice-head">
              <strong>消息中心</strong>
              <a @click="goTo('/dashboard/message-center')">查看全部</a>
            </div>
            <p>
              <span class="notice-dot orange"></span>
              <span>人工确认工作台</span>
              <small v-if="dynamicBadges['/dashboard/confirmation-workbench']">
                {{ dynamicBadges['/dashboard/confirmation-workbench'] }} 条待确认
              </small>
            </p>
            <p>
              <span class="notice-dot yellow"></span>
              <span>待发布预警</span>
              <small v-if="dynamicBadges['/dashboard/warnings']">
                {{ dynamicBadges['/dashboard/warnings'] }} 起
              </small>
            </p>
            <p>
              <span class="notice-dot orange"></span>
              <span>设备离线 1 台 · RAIN-052</span>
              <small>21分钟前</small>
            </p>
            <p>
              <span class="notice-dot yellow"></span>
              <span>今日巡检任务还有 3 项</span>
              <small>12分钟前</small>
            </p>
          </div>
        </Transition>
      </header>

      <section class="page-content">
        <div class="page-eyebrow">
          <h1>{{ topHeaderEyebrow }}</h1>
          <p>{{ topHeaderDesc }}</p>
        </div>
        <div v-if="visibleMenu.length < dashboardMenu.length" class="role-banner">
          当前角色仅开放 {{ userRoleLabel }} 可访问模块，未开放模块已自动隐藏。
        </div>

        <router-view v-slot="{ Component, route: viewRoute }">
          <Transition name="page" mode="out-in" :duration="280">
            <component
              :is="Component"
              :key="viewRoute.path"
              :current-time="timeLabel"
              :search-term="searchTerm"
              :user-role="user.role"
              @notify="notify"
              @open-task-modal="openCreateTaskModal"
              @create-task="prefillTaskForm"
            />
          </Transition>
        </router-view>
      </section>
    </main>

    <FormModal
      :open="taskModal"
      eyebrow="NEW INSPECTION"
      title="创建巡检任务"
      subtitle="统一表单已接入后端创建接口"
      confirm-text="确认创建"
      :loading="feedback.loading"
      @close="taskModal = false"
      @confirm="submitTask"
    >
      <label>任务名称<input v-model="taskForm.name" /></label>
      <label>巡检范围
        <select v-model="taskForm.scope">
          <option>全部高风险灾害点</option>
          <option>龙安镇重点区域</option>
          <option>自定义点位</option>
        </select>
      </label>
      <label v-if="taskForm.hazardId">关联灾害点
        <input :value="`${taskForm.hazardCode || ''} · ${taskForm.scope}`" readonly />
      </label>
      <div class="modal-row">
        <label>执行日期<input v-model="taskForm.dueDate" type="date" /></label>
        <label>截止时间<input v-model="taskForm.dueTime" type="time" /></label>
      </div>
    </FormModal>

    <FormModal
      :open="logoutModal"
      eyebrow="SIGN OUT"
      title="确认退出登录？"
      subtitle="退出后将清除本地登录状态，需要重新登录才能访问系统"
      confirm-text="退出登录"
      :loading="loggingOut"
      @close="closeLogoutModal"
      @confirm="logout"
    >
      <div class="logout-summary">
        <div class="logout-user">
          <div class="user-avatar">{{ user.name?.charAt(0) ?? '用' }}</div>
          <div>
            <strong>{{ user.name }}</strong>
            <span>{{ userRoleLabel }}</span>
          </div>
        </div>
        <p>退出后此页面将返回登录页，您可以随时重新登录。<br />未保存的界面操作可能会丢失。</p>
      </div>
    </FormModal>

    <AppFeedback :message="feedback.message" :loading="feedback.loading" :error="feedback.error" :success="feedback.success" />

    <transition name="toast">
      <div v-if="toast" class="toast"><ShieldCheck :size="17" />{{ toast }}</div>
    </transition>
  </div>
</template>
