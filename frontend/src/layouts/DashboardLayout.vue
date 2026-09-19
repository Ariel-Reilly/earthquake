<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Activity,
  AlertTriangle,
  BarChart3,
  Bell,
  BellRing,
  Boxes,
  Bot,
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
  FileWarning,
  Flame,
  GitBranch,
  HardDrive,
  HardHat,
  HelpCircle,
  History,
  Inbox,
  Landmark,
  Layers,
  LayoutDashboard,
  ListChecks,
  LogOut,
  Map as MapIcon,
  MapPin,
  Menu,
  MessageCircle,
  MessageSquare,
  MountainSnow,
  Package,
  Radar,
  Radio,
  Route,
  ScrollText,
  Search,
  Settings,
  ShieldAlert,
  ShieldCheck,
  Siren,
  Sparkles,
  TrendingDown,
  Truck,
  TrendingUp,
  UserCheck,
  UserCircle,
  Users,
  X,
} from 'lucide-vue-next'
import { dashboardMenu, NAV_GROUPS } from '../data/dashboardData'
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
const sidebarCollapsed = ref(window.localStorage.getItem('disasterguard_sidebar_collapsed') === '1')
const mobileSidebarOpen = ref(false)
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
  Boxes,
  Bot,
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
  FileWarning,
  Flame,
  GitBranch,
  HardDrive,
  HardHat,
  HelpCircle,
  History,
  Inbox,
  Landmark,
  Layers,
  LayoutDashboard,
  ListChecks,
  LogOut,
  Map: MapIcon,
  MapPin,
  MessageCircle,
  MessageSquare,
  MountainSnow,
  Package,
  Radar,
  Radio,
  Route,
  ScrollText,
  Settings,
  ShieldAlert,
  ShieldCheck,
  Siren,
  Sparkles,
  TrendingDown,
  Truck,
  TrendingUp,
  UserCheck,
  UserCircle,
  Users,
  X,
}

function pickIcon(name) {
  return iconComponentMap[name] ?? Radar
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
  window.localStorage.setItem('disasterguard_sidebar_collapsed', sidebarCollapsed.value ? '1' : '0')
}

function toggleMobileSidebar() {
  mobileSidebarOpen.value = !mobileSidebarOpen.value
}

function closeMobileSidebar() {
  mobileSidebarOpen.value = false
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
    // 路由变化时，自动展开当前激活项所在的分组，方便用户继续浏览相邻模块
    const groupId = activeMenu.value?.group
    if (groupId) expandGroup(groupId)
  }
)

// === 左侧分组菜单：可折叠/展开状态 ===
const SIDEBAR_GROUPS_STORAGE_KEY = 'disasterguard_sidebar_groups'
const expandedGroups = ref(new Set())

function loadExpandedGroups() {
  try {
    const raw = window.localStorage.getItem(SIDEBAR_GROUPS_STORAGE_KEY)
    if (!raw) return null
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? new Set(arr) : null
  } catch {
    return null
  }
}

function persistExpandedGroups() {
  try {
    window.localStorage.setItem(SIDEBAR_GROUPS_STORAGE_KEY, JSON.stringify([...expandedGroups.value]))
  } catch {
    // 忽略写入异常（隐私模式 / 存储满）
  }
}

function isGroupExpanded(groupId) {
  return expandedGroups.value.has(groupId)
}

function toggleGroup(groupId) {
  const next = new Set(expandedGroups.value)
  if (next.has(groupId)) {
    // 如果点击的是已展开的分组，保持展开（不关闭）
    // 如果想让它也可以关闭，可以删除这个 return
    return
  }
  // 手风琴效果：关闭其他所有分组，只展开当前点击的
  next.clear()
  next.add(groupId)
  expandedGroups.value = next
  persistExpandedGroups()
}

function expandGroup(groupId) {
  if (!groupId || expandedGroups.value.has(groupId)) return
  const next = new Set(expandedGroups.value)
  next.add(groupId)
  expandedGroups.value = next
  persistExpandedGroups()
}

function expandAllGroups() {
  expandedGroups.value = new Set(groupedMenu.value.map((g) => g.id))
  persistExpandedGroups()
}

function collapseAllGroups() {
  expandedGroups.value = new Set()
  persistExpandedGroups()
}

function isGroupActive(groupId) {
  return activeMenu.value?.group === groupId
}

// 初始化：读取本地存储；首次访问默认展开第一个有可见项的分组
const initialExpanded = loadExpandedGroups()
if (initialExpanded && initialExpanded.size > 0) {
  expandedGroups.value = initialExpanded
} else if (groupedMenu.value[0]) {
  expandedGroups.value = new Set([groupedMenu.value[0].id])
}

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
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed, 'mobile-open': mobileSidebarOpen }">
      <AnimatedSidebarBackground />
      <div class="sidebar-content">
        <div class="brand">
          <div class="brand-mark"><MountainSnow :size="22" :stroke-width="2.4" /></div>
          <div class="brand-copy"><strong>御灾智巡</strong><span>YuZaiGuard v1.7</span></div>
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
          <div
            v-for="group in groupedMenu"
            :key="group.id"
            class="nav-group"
            :class="{ collapsed: !isGroupExpanded(group.id), 'has-active': isGroupActive(group.id) }"
          >
            <button
              v-if="!sidebarCollapsed"
              type="button"
              class="nav-group-head"
              :aria-expanded="isGroupExpanded(group.id)"
              :title="`${group.label}（点击折叠）`"
              @click="toggleGroup(group.id)"
            >
              <component :is="pickIcon(group.icon)" :size="14" class="nav-group-icon" />
              <ChevronRight :size="12" class="nav-group-chevron" />
              <span class="nav-group-label">{{ group.label }}</span>
              <em class="nav-group-count">{{ group.items.length }}</em>
            </button>
            <div class="nav-group-body">
              <div class="nav-group-items">
                <button
                  v-for="item in group.items"
                  :key="item.path"
                  class="nav-item"
                  :class="{ active: currentPath === item.path || (item.path !== '/dashboard' && currentPath.startsWith(item.path)) }"
                  :title="sidebarCollapsed ? `${group.label} / ${item.label}` : item.label"
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
              </div>
            </div>
          </div>
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
      <!-- AI 健康横幅：固定在主内容区顶部，不受侧边栏影响 -->
      <div class="health-banner-wrap">
        <ServiceHealthBanner />
      </div>
      <header class="topbar">
        <!-- Mobile: hamburger menu button -->
        <button class="mobile-menu-btn" title="打开菜单" @click="toggleMobileSidebar">
          <Menu :size="20" />
        </button>
        <!-- Mobile overlay (closes sidebar on click) -->
        <div v-if="mobileSidebarOpen" class="sidebar-overlay" @click="closeMobileSidebar" />
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
            <div class="user-avatar">{{ user.name?.charAt(0) ?? '李' }}</div>
            <div class="user-info">
              <strong>{{ user.name }}</strong>
              <span>{{ userRoleLabel }}</span>
            </div>
            <button class="icon-btn user-logout-btn" title="退出登录" @click="openLogoutModal"><LogOut :size="15" /></button>
          </div>
        </div>

        <Transition name="drop">
          <div v-if="showSearch" class="search-popover">
            <Search :size="17" class="search-icon" />
            <input v-model="searchTerm" autofocus placeholder="搜索灾害点、任务、设备或预警" @keyup.enter="() => { notify('已更新搜索条件'); showSearch = false }" />
            <button v-if="searchTerm" class="search-clear-btn" title="清除" @click="searchTerm = ''"><X :size="13" /></button>
            <button class="search-submit-btn" title="搜索" @click="() => { notify('已更新搜索条件'); showSearch = false }"><Search :size="14" /></button>
          </div>
        </Transition>

        <Transition name="drop">
          <div v-if="showNotifications" class="notification-popover">
            <div class="notice-head">
              <strong>消息中心</strong>
              <a @click="goTo('/dashboard/message-center')">查看全部</a>
            </div>
            <div class="notice-list">
              <div
                class="notice-item"
                :class="{ 'has-count': dynamicBadges['/dashboard/confirmation-workbench'] && +dynamicBadges['/dashboard/confirmation-workbench'] > 0 }"
                @click="goTo('/dashboard/confirmation-workbench')"
              >
                <div class="notice-item-left">
                  <span class="notice-dot danger"></span>
                  <span>人工确认工作台</span>
                </div>
                <small v-if="dynamicBadges['/dashboard/confirmation-workbench'] && +dynamicBadges['/dashboard/confirmation-workbench'] > 0" class="notice-count">
                  {{ dynamicBadges['/dashboard/confirmation-workbench'] }} 条待确认
                </small>
              </div>
              <div
                class="notice-item"
                :class="{ 'has-count': dynamicBadges['/dashboard/warnings'] && +dynamicBadges['/dashboard/warnings'] > 0 }"
                @click="goTo('/dashboard/warnings')"
              >
                <div class="notice-item-left">
                  <span class="notice-dot warning"></span>
                  <span>待发布预警</span>
                </div>
                <small v-if="dynamicBadges['/dashboard/warnings'] && +dynamicBadges['/dashboard/warnings'] > 0" class="notice-count">
                  {{ dynamicBadges['/dashboard/warnings'] }} 起
                </small>
              </div>
              <div class="notice-item">
                <div class="notice-item-left">
                  <span class="notice-dot warning"></span>
                  <span>设备离线 1 台 · RAIN-052</span>
                </div>
                <small class="notice-time">21分钟前</small>
              </div>
              <div class="notice-item">
                <div class="notice-item-left">
                  <span class="notice-dot warning"></span>
                  <span>今日巡检任务还有 3 项</span>
                </div>
                <small class="notice-time">12分钟前</small>
              </div>
            </div>
          </div>
        </Transition>
      </header>

      <section class="page-content">
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
