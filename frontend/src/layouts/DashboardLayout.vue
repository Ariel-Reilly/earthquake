<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
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
  Camera,
  ChevronDown,
  ChevronRight,
  ChevronsLeft,
  CircleHelp,
  ClipboardCheck,
  Cloud,
  CloudRain,
  Cpu,
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
  LayoutList,
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
// 全局搜索结果（灾害点 / 设备 / 预警 / 任务）
const searchResults = ref([])
const searchLoading = ref(false)
const searchError = ref('')
let searchSeq = 0
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
  Camera,
  ChevronDown,
  ChevronRight,
  ChevronsLeft,
  CircleHelp,
  ClipboardCheck,
  Cloud,
  CloudRain,
  Cpu,
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
  LayoutList,
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
  return all.find((item) => isItemActive(item.path)) ?? all[0] ?? dashboardMenu[0]
})

const activeGroupLabel = computed(() => {
  if (!activeMenu.value) return ''
  const group = NAV_GROUPS.find((g) => g.id === activeMenu.value.group)
  return group?.label ?? ''
})

/* ══ 横向导航：一级分组作用域 ══
   横向导航用「当前激活分组」代替侧栏的「多分组同时展开」：
   - activeGroupId 默认跟随路由所在分组
   - 用户点其它分组 Tab 时, 仅切换展示, 不跳路由（等用户点具体菜单项）
   - showAllGroups 为「全部」视图, 一次性平铺 49 项, 满足"合并在一页"的诉求 */
const ALL_GROUPS = '__all__'
const activeGroupId = ref('')
const showAllGroups = computed(() => activeGroupId.value === ALL_GROUPS)

// 「全部」视图下的菜单项: 按分组顺序平铺
const allGroupItems = computed(() => groupedMenu.value.flatMap((g) => g.items))

const activeGroup = computed(() => {
  if (showAllGroups.value) {
    return { id: ALL_GROUPS, label: '全部功能', icon: 'LayoutList', items: allGroupItems.value }
  }
  return groupedMenu.value.find((g) => g.id === activeGroupId.value) ?? groupedMenu.value[0] ?? null
})

function selectGroup(groupId) {
  activeGroupId.value = groupId
}

function isItemActive(path) {
  if (path === '/dashboard') return currentPath.value === '/dashboard'
  return currentPath.value === path || currentPath.value.startsWith(path + '/')
}

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

/* ══ iOS 底部 Tab 栏数据 (仅移动端显示) ══
   取当前角色可见菜单中的 5 个高频入口,按 iOS 惯例不超过 5 项。 */
const MOBILE_TAB_PREFS = [
  { key: 'workbench', icon: 'LayoutDashboard', fallback: '/dashboard' },
  { key: 'monitor', icon: 'Radar', fallback: '/dashboard/realtime-map' },
  { key: 'warnings', icon: 'BellRing', fallback: '/dashboard/warnings' },
  { key: 'hazards', icon: 'MountainSnow', fallback: '/dashboard/hazards' },
  { key: 'message', icon: 'MessageSquare', fallback: '/dashboard/message-center' },
]

const mobileTabItems = computed(() => {
  const allowed = visibleMenu.value
  if (!allowed.length) return []
  const picked = []
  for (const pref of MOBILE_TAB_PREFS) {
    const hit =
      allowed.find((i) => i.path === pref.fallback) ??
      allowed.find((i) => i.group === pref.key) ??
      allowed.find((i) => i.path.includes(pref.key))
    if (hit && !picked.some((p) => p.path === hit.path)) {
      picked.push({ key: hit.path, path: hit.path, label: hit.label, icon: hit.icon ?? pref.icon })
    }
  }
  // 不足 3 项时用可见菜单补齐,保证 Tab 栏不空
  if (picked.length < 3) {
    for (const item of allowed) {
      if (picked.length >= 4) break
      if (!picked.some((p) => p.path === item.path)) {
        picked.push({ key: item.path, path: item.path, label: item.label, icon: item.icon })
      }
    }
  }
  return picked.slice(0, 5)
})

function isTabActive(tab) {
  const p = currentPath.value
  if (tab.path === '/dashboard') return p === '/dashboard'
  return p === tab.path || p.startsWith(tab.path + '/')
}

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

/**
 * 全局搜索：并发查询灾害点 / 设备 / 预警 / 任务，聚合为统一结果列表。
 * 使用自增序号丢弃过期响应，避免快速输入时旧结果覆盖新结果。
 */
async function runGlobalSearch() {
  const kw = searchTerm.value.trim()
  if (!kw) {
    searchResults.value = []
    return
  }
  const seq = ++searchSeq
  searchLoading.value = true
  searchError.value = ''
  try {
    const [hazards, devices, warnings, tasks] = await Promise.allSettled([
      dashboardApi.getHazards({ keyword: kw, page: 1, size: 6 }),
      dashboardApi.getDevices({ keyword: kw, page: 1, size: 6 }),
      dashboardApi.getWarnings({ keyword: kw, page: 1, size: 6 }),
      dashboardApi.getTasks({ keyword: kw, page: 1, size: 6 }),
    ])
    if (seq !== searchSeq) return

    const pick = (r, mapper) =>
      r.status === 'fulfilled' ? (r.value?.list ?? []).map(mapper) : []

    searchResults.value = [
      ...pick(hazards, (h) => ({
        type: '灾害点',
        icon: MountainSnow,
        id: h.id ?? h.hazardCode,
        title: h.name ?? h.hazardName ?? h.hazardCode,
        sub: h.hazardCode ?? h.regionCode ?? '',
        path: `/dashboard/hazards/${h.id ?? h.hazardCode}`,
      })),
      ...pick(devices, (d) => ({
        type: '设备',
        icon: HardDrive,
        id: d.id ?? d.deviceCode,
        title: d.name ?? d.deviceName ?? d.deviceCode,
        sub: d.deviceCode ?? d.deviceType ?? '',
        path: `/dashboard/devices/${d.id ?? d.deviceCode}`,
      })),
      ...pick(warnings, (w) => ({
        type: '预警',
        icon: Bell,
        id: w.id ?? w.warningCode,
        title: w.title ?? w.warningTitle ?? w.warningCode,
        sub: w.level ?? w.warningLevel ?? '',
        path: `/dashboard/warnings/${w.id ?? w.warningCode}`,
      })),
      ...pick(tasks, (t) => ({
        type: '任务',
        icon: ListChecks,
        id: t.id ?? t.taskCode,
        title: t.name ?? t.taskName ?? t.taskCode,
        sub: t.status ?? '',
        path: `/dashboard/ai-tasks/${t.id ?? t.taskCode}`,
      })),
    ]
    if (!searchResults.value.length) {
      searchError.value = '未找到匹配结果'
    }
  } catch (e) {
    if (seq !== searchSeq) return
    searchResults.value = []
    searchError.value = e?.message ?? String(e)
  } finally {
    if (seq === searchSeq) searchLoading.value = false
  }
}

function openSearchResult(item) {
  router.push(item.path)
  showSearch.value = false
  searchTerm.value = ''
  searchResults.value = []
}

function clearSearch() {
  searchTerm.value = ''
  searchResults.value = []
  searchError.value = ''
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
    // 路由变化时立即重算红点：用户在消息中心点完"标已读"返回，
    // 红点必须马上消失，不能等下一轮 60s 轮询（这正是"红点不消失"的体感来源）。
    refreshDynamicBadges()
    // 路由变化时，自动展开当前激活项所在的分组（移动端抽屉用）
    const groupId = activeMenu.value?.group
    if (groupId) {
      expandGroup(groupId)
      // 横向导航：一级 Tab 跟随路由切换（用户手动切到"全部"时不抢回）
      if (!showAllGroups.value) selectGroup(groupId)
    }
  }
)

// 左侧分组菜单：可折叠/展开状态
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
    // 已展开 → 折叠
    next.delete(groupId)
  } else {
    // 已折叠 → 展开（不关闭其他分组，允许同时展开多个）
    next.add(groupId)
  }
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

// 初始化：读取本地存储；首次访问默认展开第一个有可见项的分组。
// 注意：分组 id 可能随版本变化，本地存储里的旧 id 会被丢弃（intersect 当前有效 id），
// 而新增的分组（如 analytics）不在存储里，需并入展开集合，否则会静默折叠、用户看不到。
const initialExpanded = loadExpandedGroups()
if (initialExpanded && initialExpanded.size > 0) {
  const validIds = new Set(groupedMenu.value.map((g) => g.id))
  const kept = new Set([...initialExpanded].filter((id) => validIds.has(id)))
  // 存储中未出现过的分组视为新增，默认展开
  for (const id of validIds) {
    if (!initialExpanded.has(id)) kept.add(id)
  }
  expandedGroups.value = kept
} else if (groupedMenu.value[0]) {
  expandedGroups.value = new Set([groupedMenu.value[0].id])
}

// 横向导航：一级 Tab 初始值 = 当前路由所在分组（无匹配则取首个可见分组）
activeGroupId.value =
  groupedMenu.value.find((g) => g.id === activeMenu.value?.group)?.id ??
  groupedMenu.value[0]?.id ??
  ''

/* ══ 二级导航自动轮播滚动 ══
   内容溢出（scrollWidth > clientWidth）时，自动左右往返慢速滑动，
   让被挤出视口的菜单项（如末尾项）也能被看到。
   - hover / 触摸时暂停，移开后继续
   - 用户手动滚动时暂停 4s，避免与轮播"抢滚动条"
   - 系统开启"减少动态效果"时不轮播 */
const hnavItemsRef = ref(null)
let hnavRafId = 0
let hnavDir = 1
let hnavHoldUntil = 0
const HNAV_SPEED = 0.4 // px per frame（60fps 下约 24px/s，慢速不干扰阅读）

function hnavPrefersReducedMotion() {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ?? false
}

function stopHnavAutoScroll() {
  if (hnavRafId) {
    cancelAnimationFrame(hnavRafId)
    hnavRafId = 0
  }
}

function startHnavAutoScroll() {
  stopHnavAutoScroll()
  if (hnavPrefersReducedMotion()) return
  const step = () => {
    const el = hnavItemsRef.value
    if (!el) return
    const max = el.scrollWidth - el.clientWidth
    if (max > 8 && !el.matches(':hover') && performance.now() >= hnavHoldUntil) {
      // 到达两端时反向，形成往返循环
      if (hnavDir > 0 && el.scrollLeft >= max - 0.5) hnavDir = -1
      else if (hnavDir < 0 && el.scrollLeft <= 0.5) hnavDir = 1
      el.scrollLeft += hnavDir * HNAV_SPEED
    }
    hnavRafId = requestAnimationFrame(step)
  }
  hnavRafId = requestAnimationFrame(step)
}

// 用户手动滚动 / 触摸时，暂停轮播一段时间
function hnavHoldScroll() {
  hnavHoldUntil = performance.now() + 4000
}

// 分组或菜单项变化时，重置滚动位置并重启轮播
watch(
  () => [activeGroupId.value, activeGroup.value?.items.length],
  () => {
    hnavDir = 1
    nextTick(() => {
      const el = hnavItemsRef.value
      if (el) el.scrollLeft = 0
      startHnavAutoScroll()
      // 切换分组后，自动把当前激活的二级菜单项滚动到可视区中央
      const activeEl = el?.querySelector('.hnav-item.active')
      activeEl?.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' })
    })
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
  // 二级导航自动轮播
  startHnavAutoScroll()
})

onBeforeUnmount(() => {
  window.clearInterval(timerId)
  window.clearInterval(badgeTimer)
  window.clearTimeout(feedbackTimer)
  stopAiHealthCheck()
  stopHnavAutoScroll()
})
</script>

<template>
  <div class="app-shell is-horizontal">
    <!-- ═══════════════════════════════════════════════════════════
         移动端抽屉侧栏（仅 ≤768px 可见；桌面端已改为顶部横向导航）
         ═══════════════════════════════════════════════════════════ -->
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed, 'mobile-open': mobileSidebarOpen }">
      <AnimatedSidebarBackground />
      <div class="sidebar-content">
        <div class="brand">
          <div class="brand-mark"><MountainSnow :size="22" :stroke-width="2.4" /></div>
          <div class="brand-copy"><strong>御灾智巡</strong><span>YuZaiGuard v1.8</span></div>
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
              <component :is="pickIcon(group.icon)" :size="15" class="nav-group-icon" />
              <ChevronRight :size="13" class="nav-group-chevron" />
              <span class="nav-group-label">{{ group.label }}</span>
              <em class="nav-group-count">{{ group.items.length }}</em>
            </button>
            <div v-else class="nav-group-collapsed-indicator">
              <component :is="pickIcon(group.icon)" :size="18" />
            </div>
            <div class="nav-group-body">
              <div class="nav-group-items">
                <button
                  v-for="item in group.items"
                  :key="item.path"
                  class="nav-item"
                  :class="{ active: isItemActive(item.path) }"
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
          <div class="sidebar-footer-nav">
            <button class="nav-item" :class="{ active: route.path === '/dashboard/settings' }" title="系统设置" @click="goTo('/dashboard/settings')">
              <Settings :size="18" /><span>系统设置</span>
            </button>
            <button class="nav-item" title="帮助中心" @click="goTo('/dashboard/help')">
              <CircleHelp :size="18" /><span>帮助中心</span>
            </button>
            <button class="nav-item" title="退出登录" @click="openLogoutModal">
              <LogOut :size="18" /><span>退出登录</span>
            </button>
          </div>
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
      <!-- AI 健康横幅：固定在主内容区顶部 -->
      <div class="health-banner-wrap">
        <ServiceHealthBanner />
      </div>

      <!-- Mobile overlay (closes drawer on click) -->
      <div v-show="mobileSidebarOpen" class="sidebar-overlay" :class="{ 'is-visible': mobileSidebarOpen }" @click="closeMobileSidebar" />

      <!-- ═══════════════════════════════════════════════════════════
           顶部横向导航栏（桌面端主导航）
           第一行：品牌 + 6 个分组 Tab + 全局操作
           第二行：当前分组下的菜单项（横向可滚动）
           ═══════════════════════════════════════════════════════════ -->
      <header class="hnav">
        <div class="hnav-top">
          <div class="hnav-brand" title="御灾智巡" @click="goTo('/dashboard')">
            <div class="brand-mark"><MountainSnow :size="19" :stroke-width="2.4" /></div>
            <div class="brand-copy"><strong>御灾智巡</strong><span>YuZaiGuard v1.8</span></div>
          </div>

          <!-- 一级导航：分组 Tab -->
          <nav class="hnav-groups" role="tablist" aria-label="主导航分组">
            <button
              v-for="group in groupedMenu"
              :key="group.id"
              type="button"
              role="tab"
              class="hnav-group"
              :class="{ active: !showAllGroups && isGroupActive(group.id) }"
              :aria-selected="!showAllGroups && isGroupActive(group.id)"
              @click="selectGroup(group.id)"
            >
              <component :is="pickIcon(group.icon)" :size="16" />
              <span>{{ group.label }}</span>
              <em class="hnav-group-count">{{ group.items.length }}</em>
            </button>
            <span class="hnav-groups-sep" aria-hidden="true"></span>
            <button
              type="button"
              role="tab"
              class="hnav-group hnav-group-all"
              :class="{ active: showAllGroups }"
              :aria-selected="showAllGroups"
              title="在一页中平铺全部功能"
              @click="selectGroup(ALL_GROUPS)"
            >
              <component :is="pickIcon('LayoutList')" :size="16" />
              <span>全部功能</span>
              <em class="hnav-group-count">{{ visibleMenu.length }}</em>
            </button>
          </nav>

          <div class="top-actions">
            <PermissionButton
              v-if="hasPermission(user.role, 'overview:export')"
              permission="overview:export"
              :icon="FileText"
              class="topbar-action icon-only"
              aria-label="导出报告"
              title="导出报告"
              @click="async ({ allowed, reason }) => {
                if (!allowed) return notify(reason)
                await runAction(() => dashboardApi.exportOverviewReport(), {
                  loadingText: '报表生成中...',
                  successText: '报告正在生成，请稍候',
                  failureText: '导出失败',
                })
              }"
            ><span class="sr-only">导出报告</span></PermissionButton>
            <PermissionButton
              v-if="hasPermission(user.role, 'ai:task:create')"
              permission="ai:task:create"
              variant="primary"
              :icon="ListChecks"
              class="topbar-action icon-only"
              aria-label="创建巡检任务"
              title="创建巡检任务"
              @click="({ allowed, reason }) => allowed ? openCreateTaskModal() : notify(reason)"
            ><span class="sr-only">创建巡检任务</span></PermissionButton>
            <button class="icon-btn" title="搜索" aria-label="搜索" @click="showSearch = !showSearch"><Search :size="18" /></button>
            <button
              class="icon-btn notification-btn"
              title="通知"
              aria-label="通知"
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
        </div>

        <!-- 二级导航：当前分组下的菜单项（keyed 容器 → 切换分组时重放上滑动画） -->
        <div v-if="activeGroup" :key="activeGroup.id" class="hnav-sub hnav-sub-anim">
          <div class="hnav-sub-title">
            <component :is="pickIcon(activeGroup.icon)" :size="14" />
            <span>{{ activeGroup.label }}</span>
          </div>
          <div
            ref="hnavItemsRef"
            class="hnav-items"
            role="tablist"
            :aria-label="`${activeGroup.label} 菜单`"
            @wheel.passive="hnavHoldScroll"
            @touchstart.passive="hnavHoldScroll"
          >
            <button
              v-for="item in activeGroup.items"
              :key="item.path"
              type="button"
              role="tab"
              class="hnav-item"
              :class="{ active: isItemActive(item.path), danger: item.danger }"
              :aria-selected="isItemActive(item.path)"
              :title="item.label"
              @click="goTo(item.path)"
            >
              <component :is="pickIcon(item.icon)" :size="16" />
              <span>{{ item.label }}</span>
              <em
                v-if="dynamicBadges[item.path] && +dynamicBadges[item.path] > 0"
                class="hnav-badge"
              >{{ dynamicBadges[item.path] }}</em>
              <em v-else-if="item.badge" class="hnav-badge new">{{ item.badge }}</em>
            </button>
          </div>
        </div>
      </header>

      <!-- ═══════════════════════════════════════════════════════════
           页面级工具条：面包屑（路由层级提示）
           移动端(≤768px)额外承担顶栏角色: 汉堡按钮在此显示
           ═══════════════════════════════════════════════════════════ -->
      <header class="topbar topbar-sub">
        <button class="mobile-menu-btn" title="打开菜单" @click="toggleMobileSidebar">
          <Menu :size="20" />
        </button>
        <div class="breadcrumb">
          <span>{{ activeGroupLabel }}</span>
          <ChevronRight :size="14" />
          <strong>{{ activeMenu.label }}</strong>
          <template v-if="route.path !== activeMenu.path">
            <ChevronRight :size="14" />
            <strong class="breadcrumb-leaf">{{ currentLeafLabel }}</strong>
          </template>
        </div>

        <Transition name="drop">
          <div v-if="showSearch" class="search-popover">
            <div class="search-input-row">
              <Search :size="17" class="search-icon" />
              <input v-model="searchTerm" autofocus placeholder="搜索灾害点、任务、设备或预警" @keyup.enter="runGlobalSearch" />
              <button v-if="searchTerm" class="search-clear-btn" title="清除" @click="clearSearch"><X :size="13" /></button>
              <button class="search-submit-btn" title="搜索" @click="runGlobalSearch"><Search :size="14" /></button>
            </div>
            <div v-if="searchLoading" class="search-result-hint">正在搜索…</div>
            <div v-else-if="searchResults.length" class="search-results">
              <div
                v-for="item in searchResults"
                :key="`${item.type}-${item.id}`"
                class="search-result-item"
                @click="openSearchResult(item)"
              >
                <component :is="item.icon" :size="15" class="search-result-icon" />
                <div class="search-result-copy">
                  <strong>{{ item.title }}</strong>
                  <small>{{ item.sub }}</small>
                </div>
                <span class="search-result-type">{{ item.type }}</span>
              </div>
            </div>
            <div v-else-if="searchError" class="search-result-hint">{{ searchError }}</div>
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
              @badge-refresh="refreshDynamicBadges"
              @open-task-modal="openCreateTaskModal"
              @create-task="prefillTaskForm"
            />
          </Transition>
        </router-view>
      </section>
    </main>

    <!-- ══ iOS 底部 Tab 栏 (移动端主导航) ══ -->
    <nav v-if="mobileTabItems.length" class="ios-tabbar" role="tablist">
      <button
        v-for="tab in mobileTabItems"
        :key="tab.key"
        type="button"
        role="tab"
        class="ios-tabbar-item"
        :class="{ active: isTabActive(tab) }"
        :aria-selected="isTabActive(tab)"
        :title="tab.label"
        @click="goTo(tab.path)"
      >
        <span class="ios-tabbar-icon">
          <component :is="pickIcon(tab.icon)" :size="23" />
          <span
            v-if="dynamicBadges[tab.path] && +dynamicBadges[tab.path] > 0"
            class="ios-tabbar-badge"
          >{{ dynamicBadges[tab.path] }}</span>
        </span>
        <span>{{ tab.label }}</span>
      </button>
    </nav>

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

<style scoped>
/* ═══════════════════════════════════════════════════════════
   DashboardLayout iOS 18 视觉升级
   作用: 在不破坏既有全局 styles.css 的前提下, 强化
   侧栏 / 顶栏 / 折叠按钮 / 头像 / 用户徽章 的 iOS 18 质感
   ═══════════════════════════════════════════════════════════ */

/* 侧栏: 实心莫兰迪深绿（禁止毛玻璃 —— 避免透出身后浅色背景导致"看不清"）*/
.app-shell :deep(.sidebar) {
  background: var(--nav-bg, linear-gradient(180deg, var(--c-brand-deeper) 0%, var(--c-brand-deeper) 100%));
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
  border-right: .5px solid var(--nav-border, rgba(255, 255, 255, .08));
  box-shadow: 8px 0 32px rgba(44, 61, 51, .10);
}

/* 品牌 logo: squircle + 莫兰迪绿渐变 */
.app-shell :deep(.brand-mark) {
  border-radius: var(--r-lg);
  background: linear-gradient(150deg, var(--c-brand-hover) 0%, var(--c-brand) 55%, var(--c-brand-deep) 100%);
  box-shadow:
    0 6px 18px rgba(74, 122, 94, .30),
    inset 0 1px 0 rgba(255, 255, 255, .28);
}

/* 工作区切换: iOS 18 圆角胶囊 */
.app-shell :deep(.workspace-switcher) {
  border-radius: var(--r-lg);
  transition: background .2s var(--ease-ios, ease), transform .2s var(--ease-ios, ease);
}
.app-shell :deep(.workspace-switcher:active) { transform: scale(.98); }

/* 导航项激活: 视觉统一由全局 styles.css 管, 这里只定圆角 */
.app-shell :deep(.nav-item.active) {
  border-radius: var(--r-md);
}

/* 折叠按钮: 圆形 tinted */
.app-shell :deep(.sidebar-collapse-btn) {
  width: 30px;
  height: 30px;
  background: rgba(120, 120, 127, .12);
  color: var(--c-text-2);
  border-radius: 50%;
}
.app-shell :deep(.sidebar-collapse-btn:hover) {
  background: rgba(120, 120, 127, .22);
  color: var(--c-text);
}

/* 顶栏: 轻玻璃（模糊降为 20px / 饱和度 160%，避免过度透色）*/
.app-shell :deep(.topbar) {
  background: var(--glass-regular, rgba(252, 253, 252, .80));
  -webkit-backdrop-filter: blur(20px) saturate(160%);
  backdrop-filter: blur(20px) saturate(160%);
  border-bottom: .5px solid var(--c-separator, rgba(60, 60, 67, .12));
  box-shadow: 0 1px 0 rgba(255, 255, 255, .5) inset;
}

/* 顶栏按钮: 圆形 tinted */
.app-shell :deep(.icon-btn) {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: rgba(120, 120, 127, .10);
  color: var(--c-text-2);
  transition: background .18s var(--ease-ios, ease), color .18s var(--ease-ios, ease), transform .12s ease;
}
.app-shell :deep(.icon-btn:hover) {
  background: rgba(111, 158, 127, .14);
  color: var(--c-brand);
}
.app-shell :deep(.icon-btn:active) { transform: scale(.94); }

/* 用户头像: 莫兰迪绿渐变 */
.app-shell :deep(.user-avatar) {
  border-radius: 50%;
  background: linear-gradient(140deg, var(--c-brand-hover), var(--c-brand-deep));
  color: var(--c-on-brand);
  font-weight: 600;
  box-shadow: 0 2px 8px rgba(84, 128, 106, .28);
}

/* 健康状态点: iOS 18 脉冲 */
.app-shell :deep(.pulse-dot) {
  background: var(--c-success);
  box-shadow: 0 0 0 4px rgba(111, 158, 127, .22), 0 0 12px rgba(111, 158, 127, .35);
}

/* 通知 popover / 搜索 popover: iOS 18 玻璃 */
.app-shell :deep(.search-popover),
.app-shell :deep(.notification-popover) {
  background: var(--glass-thick, rgba(255, 255, 255, .92));
  -webkit-backdrop-filter: blur(24px) saturate(160%);
  backdrop-filter: blur(24px) saturate(160%);
  border: .5px solid var(--c-separator, rgba(60, 60, 67, .12));
  border-radius: var(--r-lg);
  box-shadow: 0 1px 0 rgba(255, 255, 255, .6) inset,
              0 16px 48px rgba(0, 0, 0, .14);
}

/* 离线/在线卡片: iOS 18 玻璃 */
.app-shell :deep(.online-card) {
  border-radius: var(--r-lg);
  background: rgba(120, 120, 127, .10);
  border: .5px solid var(--c-separator, rgba(60, 60, 67, .12));
  box-shadow: none;
}

/* ═══════════════════════════════════════════════════════════
   横向导航栏 (HNAV)  —— 桌面端主导航  2026-10-08
   结构:
     .hnav
       ├─ .hnav-top    品牌 + 一级分组 Tab + 全局操作
       └─ .hnav-sub    当前分组下的菜单项（横向滚动）
   ═══════════════════════════════════════════════════════════ */
.hnav {
  position: sticky;
  top: 0;
  z-index: var(--z-top, 40);
  flex-shrink: 0;
  background: linear-gradient(180deg, var(--c-brand-deep) 0%, var(--nav-bg-flat) 58%, var(--c-brand-deeper) 100%);
  box-shadow: 0 6px 22px rgba(44, 61, 51, .16), inset 0 -1px 0 rgba(255, 255, 255, .08);
  color: var(--nav-text, var(--c-bg));
}

/* ── 第一行 ── */
.hnav-top {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 22px;
  height: 62px;
}

.hnav-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  cursor: pointer;
  border-radius: var(--r-lg);
  padding: 5px 8px 5px 5px;
  transition: background .2s var(--ease-ios, ease);
}
.hnav-brand:hover { background: rgba(255, 255, 255, .10); }
.hnav-brand .brand-mark {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: var(--r-md);
  background: linear-gradient(150deg, var(--c-brand-hover) 0%, var(--c-brand) 55%, var(--c-brand-deep) 100%);
  box-shadow: 0 4px 14px rgba(20, 45, 30, .26), inset 0 1px 0 rgba(255, 255, 255, .3);
  color: var(--c-on-brand);
}
.hnav-brand .brand-copy { display: flex; flex-direction: column; line-height: 1.15; }
.hnav-brand .brand-copy strong { font-size: 14.5px; font-weight: 700; letter-spacing: .01em; color: var(--c-on-brand); }
.hnav-brand .brand-copy span { font-size: 10px; color: rgba(244, 247, 244, .62); }

/* ── 一级分组 Tab ── */
.hnav-groups {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.hnav-groups::-webkit-scrollbar { display: none; }

.hnav-group {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  flex-shrink: 0;
  height: 36px;
  padding: 0 13px;
  border: 0;
  border-radius: var(--r-md);
  background: transparent;
  color: rgba(244, 247, 244, .78);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: background .2s var(--ease-ios, ease), color .2s var(--ease-ios, ease),
              transform .14s var(--ease-ios, ease);
}
.hnav-group:hover { background: rgba(255, 255, 255, .12); color: var(--c-on-brand); }
.hnav-group:active { transform: scale(.97); }
.hnav-group.active {
  background: rgba(255, 255, 255, .20);
  color: var(--c-on-brand);
  font-weight: 650;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .22);
}
.hnav-group-count {
  font-style: normal;
  font-size: 10.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  min-width: 17px;
  height: 17px;
  padding: 0 5px;
  display: inline-grid;
  place-items: center;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, .16);
  color: rgba(255, 255, 255, .88);
}
.hnav-group.active .hnav-group-count { background: rgba(255, 255, 255, .30); color: var(--c-on-brand); }

.hnav-groups-sep {
  flex-shrink: 0;
  width: 1px;
  height: 20px;
  margin: 0 4px;
  background: rgba(255, 255, 255, .18);
}
.hnav-group-all.active { background: rgba(255, 255, 255, .24); }

/* 第一行右侧操作区: 深色底上反白
   ⚠ PermissionButton 实际渲染类名是 ios-btn secondary/primary (非 *-btn) */
.hnav .top-actions { gap: 10px; flex-shrink: 0; }
.hnav .icon-btn {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: rgba(255, 255, 255, .10);
  color: rgba(255, 255, 255, .86);
  border: 0;
}
.hnav .icon-btn:hover { background: rgba(255, 255, 255, .20); color: var(--c-on-brand); }
.hnav .notification-btn i {
  background: var(--c-danger, var(--c-danger));
  box-shadow: 0 0 0 2px rgba(65, 105, 79, .9);
}
.hnav .user-profile {
  display: flex;
  align-items: center;
  gap: 9px;
  padding-left: 10px;
  margin-left: 2px;
  border-left: 1px solid rgba(255, 255, 255, .16);
}
.hnav .user-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-on-brand);
  background: linear-gradient(140deg, var(--c-brand-hover), var(--c-brand-deep));
  box-shadow: 0 2px 8px rgba(20, 45, 30, .3);
}
.hnav .user-info { display: flex; flex-direction: column; line-height: 1.2; }
.hnav .user-info strong { font-size: 12.5px; font-weight: 600; color: var(--c-on-brand); }
.hnav .user-info span { font-size: 10.5px; color: rgba(244, 247, 244, .62); }
.hnav .user-logout-btn { width: 30px; height: 30px; background: transparent; }
.hnav .user-logout-btn:hover { background: rgba(255, 255, 255, .18); color: var(--c-on-brand); }
/* 次按钮(导出报告): 半透明白玻璃 —— 纯图标胶囊 */
.hnav .topbar-action.ios-btn.secondary,
.hnav .topbar-action.secondary {
  background: rgba(255, 255, 255, .12);
  color: var(--c-on-brand);
  border: .5px solid rgba(255, 255, 255, .22);
  box-shadow: none;
}
.hnav .topbar-action.ios-btn.secondary:hover,
.hnav .topbar-action.secondary:hover {
  background: rgba(255, 255, 255, .22);
  color: var(--c-on-brand);
}
/* 主按钮(创建巡检任务) —— 纯图标胶囊 */
.hnav .topbar-action.ios-btn.primary,
.hnav .topbar-action.primary {
  background: var(--c-bg);
  color: var(--c-brand-deeper);
  border-color: transparent;
  font-weight: 650;
  box-shadow: 0 2px 10px rgba(15, 35, 22, .22);
}
.hnav .topbar-action.ios-btn.primary:hover,
.hnav .topbar-action.primary:hover {
  background: var(--c-surface);
  color: #2c4d3b;
}

/* ── 纯图标按钮: 与 .icon-btn 同尺寸同圆度，文字仅保留给读屏 ── */
.hnav .topbar-action.icon-only {
  width: 34px;
  height: 34px;
  min-width: 34px;
  padding: 0;
  border-radius: 50%;
  display: inline-grid;
  place-items: center;
  gap: 0;
}
.hnav .topbar-action.icon-only svg { width: 17px; height: 17px; }
.hnav .topbar-action.icon-only:active { transform: scale(.94); }
/* 读屏专用文本 */
.hnav .topbar-action.icon-only .sr-only,
.hnav .sr-only {
  position: absolute;
  width: 1px; height: 1px;
  padding: 0; margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

/* ── 第二行：当前分组菜单项 ── */
.hnav-sub {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 50px;
  padding: 0 22px;
  background: rgba(255, 255, 255, .06);
  border-top: .5px solid rgba(255, 255, 255, .10);
  overflow: hidden; /* 上滑动画从条内浮出，避免溢出到第一行 */
}

/* ── 切换一级分组时，二级导航整体上滑浮现（iOS 风格） ── */
@keyframes hnav-slide-up {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
.hnav-sub-anim {
  animation: hnav-slide-up .34s cubic-bezier(.32, .72, 0, 1) both;
}
@media (prefers-reduced-motion: reduce) {
  .hnav-sub-anim { animation: none; }
}
.hnav-sub-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  padding-right: 12px;
  border-right: 1px solid rgba(255, 255, 255, .16);
  color: rgba(244, 247, 244, .72);
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: .02em;
  white-space: nowrap;
}

.hnav-items {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
  height: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, .24) transparent;
  /* 轮播滚动时两端淡出（内容不溢出时遮罩无视觉影响） */
  -webkit-mask-image: linear-gradient(90deg, transparent 0, #000 14px, #000 calc(100% - 14px), transparent 100%);
  mask-image: linear-gradient(90deg, transparent 0, #000 14px, #000 calc(100% - 14px), transparent 100%);
}
.hnav-items::-webkit-scrollbar { height: 3px; }
.hnav-items::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, .22);
  border-radius: var(--r-pill);
}
.hnav-items::-webkit-scrollbar-thumb:hover { background: rgba(255, 255, 255, .38); }

.hnav-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  flex-shrink: 0;
  height: 34px;
  padding: 0 12px;
  border: 0;
  border-radius: var(--r-md);
  background: transparent;
  color: rgba(244, 247, 244, .80);
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: background .18s var(--ease-ios, ease), color .18s var(--ease-ios, ease),
              transform .12s var(--ease-ios, ease);
}
.hnav-item:hover { background: rgba(255, 255, 255, .14); color: var(--c-on-brand); }
.hnav-item:active { transform: scale(.96); }
.hnav-item.active {
  background: var(--c-bg);
  color: var(--c-brand-deeper);
  font-weight: 650;
  box-shadow: 0 2px 10px rgba(15, 35, 22, .20);
}
.hnav-item.active svg { color: var(--c-brand-deeper); }
.hnav-item.active:hover { background: var(--c-surface); }
.hnav-item.danger:not(.active) { color: #ffd9d6; }

.hnav-badge {
  font-style: normal;
  font-size: 9.5px;
  font-weight: 700;
  line-height: 1;
  padding: 3px 5px;
  border-radius: var(--r-pill);
  background: var(--c-danger, var(--c-danger));
  color: var(--c-on-brand);
  font-variant-numeric: tabular-nums;
}
.hnav-badge.new { background: rgba(255, 255, 255, .26); color: var(--c-on-brand); letter-spacing: .04em; }
.hnav-item.active .hnav-badge { background: var(--c-brand); color: var(--c-on-brand); }
.hnav-item.active .hnav-badge.new { background: var(--c-brand); color: var(--c-on-brand); }

/* 第二行存在时, 页面级面包屑条退为轻量细条 */
.topbar-sub {
  height: 40px;
  background: transparent;
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
  border-bottom: 0;
  box-shadow: none;
  padding-top: 2px;
}
.topbar-sub .breadcrumb { font-size: 11.5px; }

/* ── 断点：≤1024px 隐藏一级分组文字, 只留图标 ── */
@media (max-width: 1024px) {
  .hnav-group span:not(.hnav-group-count) { display: none; }
  .hnav-group { padding: 0 10px; }
  .hnav-brand .brand-copy { display: none; }
  .hnav .user-info { display: none; }
}

/* ── 断点：≤880px 二级导航收窄 ── */
@media (max-width: 880px) {
  .hnav-sub { height: 46px; padding: 0 16px; }
  .hnav-sub-title { display: none; }
  .hnav-top { padding: 0 16px; gap: 10px; }
}

/* ── 断点：≤768px 移动端 —— 横向导航整体隐藏, 改走抽屉 + 底部 Tab ── */
@media (max-width: 768px) {
  .hnav { display: none; }
  /* 面包屑条保留, 兼任移动端顶栏(汉堡按钮在其中) */
  .topbar-sub { padding: 0 14px; }
}
</style>
