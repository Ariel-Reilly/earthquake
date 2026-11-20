/**
 * 主布局组件（经典双层导航：顶栏 + 一级横向 Tab + 左侧平铺二级）
 *
 * 布局结构（由上到下）：
 * - 顶部：TopToolbar（64px，内嵌一级导航）
 * - 主体：Flex 布局
 *   - 左侧：SidebarNav（260px，平铺当前大类的子菜单）
 *   - 右侧：ContentArea（flex:1，滚动）
 *
 * 联动逻辑：
 * - 路由变化时，通过"最长前缀匹配"自动推断当前页属于哪个大类，
 *   同时更新顶部一级 Tab 高亮 + 左侧二级菜单内容。
 * - 点击顶部 Tab 时，如果当前页已在该大类，则只更新高亮不跳转；
 *   否则跳转到该大类的第一个可访问页面。
 *
 * @component
 */
<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ShieldCheck } from 'lucide-vue-next'
import TopToolbar from '../components/layout/TopToolbar.vue'
import SidebarNav from '../components/layout/SidebarNav.vue'
import ContentArea from '../components/layout/ContentArea.vue'
import NotificationDrawer from '../components/notification/NotificationDrawer.vue'
import GlobalAlarmModal from '../components/alarm/GlobalAlarmModal.vue'
import { useEscapeClose } from '../composables/useEscapeClose'
import { getAllowedMenuPaths, getAuthRole } from '../stores/auth'
import { NAV_GROUPS, dashboardMenu } from '../data/dashboardData'
import { dashboardApi } from '../services/dashboardApi'

const route = useRoute()
const router = useRouter()

const userRole = computed(() => getAuthRole())
const allowedPaths = computed(() => getAllowedMenuPaths(userRole.value))

/** 完整的分组数据：NAV_GROUPS 合并 dashboardMenu 并按角色裁剪 */
const groups = computed(() => {
  const bucket = new Map(NAV_GROUPS.map((g) => [g.id, { ...g, items: [] }]))
  for (const item of dashboardMenu) {
    if (!allowedPaths.value.includes(item.path)) continue
    bucket.get(item.group)?.items.push(item)
  }
  return [...bucket.values()]
    .filter((g) => g.items.length > 0)
    .map((g) => ({
      ...g,
      items: [...g.items].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)),
      itemCount: g.items.length,
    }))
})

/**
 * 判断路径是否命中某个菜单项。
 * /dashboard 是所有页面的前缀，只能精确匹配，否则会吞掉其他菜单。
 */
function matchPath(itemPath, current) {
  if (itemPath === '/dashboard') return current === '/dashboard'
  return current === itemPath || current.startsWith(itemPath + '/')
}

/**
 * 按"最长前缀匹配"找出路径所属的大类 ID，匹配不到返回空串。
 * 取最长而不是第一个命中：/dashboard/devices/ota 同时匹配 devices 与 devices/ota，
 * 必须以更具体的那个为准。
 *
 * @param {string} path - 当前路由路径
 * @returns {string} 大类 ID
 */
function resolveGroupId(path) {
  let bestLen = 0
  let bestGroup = ''
  for (const group of groups.value) {
    for (const item of group.items) {
      if (matchPath(item.path, path) && item.path.length > bestLen) {
        bestLen = item.path.length
        bestGroup = group.id
      }
    }
  }
  return bestGroup
}

/**
 * 当前激活的大类 ID。
 * 用 ref + watch 而非 computed：403、404 等兜底页不属于任何大类，
 * 此时应保持用户上一次所在的大类，避免顶栏和侧栏突然跳回第一个大类。
 */
const activeGroupId = ref('')

watch(
  [() => route.path, groups],
  ([path]) => {
    const matched = resolveGroupId(path)
    if (matched) {
      activeGroupId.value = matched
      return
    }
    // 未匹配时保留旧值；旧值失效（权限变化导致大类消失）才回退到第一个
    if (!groups.value.some((g) => g.id === activeGroupId.value)) {
      activeGroupId.value = groups.value[0]?.id ?? ''
    }
  },
  { immediate: true }
)

/** 当前激活的大类对象 */
const activeGroup = computed(() => groups.value.find((g) => g.id === activeGroupId.value) || null)

// 动态红点数据。复用 DashboardLayout 里相同的接口拉取逻辑，避免 0 值常驻
const dynamicBadges = ref({
  '/dashboard/confirmation-workbench': '0',
  '/dashboard/warnings': '0',
  '/dashboard/message-center': '0',
  '/dashboard/todo': '0',
  '/dashboard/approvals': '0',
})

// 消息未读数（传给 TopToolbar 的铃铛）
const unreadCount = computed(() => {
  const n = parseInt(dynamicBadges.value['/dashboard/message-center'], 10)
  return Number.isFinite(n) ? n : 0
})

let badgeTimer = null

/**
 * 动态红点加载（从 DashboardLayout 迁移）。
 * 失败时静默保留旧值，快速加载优先。
 */
async function refreshDynamicBadges() {
  try {
    const [confirm, warn, msg, todoSummary, approvalStat] = await Promise.all([
      dashboardApi.getConfirmationQueueStats?.().catch(() => null),
      dashboardApi.getWarningStats?.().catch(() => null),
      dashboardApi.getMessageStats?.().catch(() => null),
      dashboardApi.getTodoSummary?.().catch(() => null),
      dashboardApi.getApprovalStats?.().catch(() => null),
    ])
    if (confirm && typeof confirm.pending === 'number') {
      dynamicBadges.value['/dashboard/confirmation-workbench'] = String(confirm.pending)
    }
    if (warn && typeof warn.pending === 'number') {
      dynamicBadges.value['/dashboard/warnings'] = String(warn.pending)
    }
    if (msg && typeof msg.unread === 'number') {
      dynamicBadges.value['/dashboard/message-center'] = String(msg.unread)
    }
    if (todoSummary && typeof todoSummary === 'object') {
      const todoTotal =
        (Number(todoSummary.confirmation) || 0) +
        (Number(todoSummary.approval) || 0) +
        (Number(todoSummary.pendingWarnings) || 0) +
        (Number(todoSummary.openEmergency) || 0)
      dynamicBadges.value['/dashboard/todo'] = String(todoTotal)
      const approvalPending =
        (approvalStat && typeof approvalStat.pending === 'number'
          ? Number(approvalStat.pending)
          : Number(todoSummary.approval) || 0)
      dynamicBadges.value['/dashboard/approvals'] = String(approvalPending)
    } else if (approvalStat && typeof approvalStat.pending === 'number') {
      dynamicBadges.value['/dashboard/approvals'] = String(approvalStat.pending)
    }
  } catch (err) {
    console.warn('[MainLayout] 动态红点刷新失败:', err)
  }
}

/**
 * 统一的路由跳转入口（顶栏搜索、一级 Tab、二级菜单共用）。
 * 相同路径不重复 push，避免 vue-router 抛 NavigationDuplicated 警告。
 *
 * @param {string} path - 目标路径
 */
function handleNavigate(path) {
  if (!path || path === route.path) return
  router.push(path).catch((err) => {
    console.warn('[MainLayout] 路由跳转失败:', path, err)
  })
}

/** 全局通知抽屉 */
const notificationDrawerVisible = ref(false)

/* ── 移动端侧栏抽屉 ─────────────────────────────────────────
   ≤768px 时侧栏被样式表收起，此前全站没有任何入口，
   二级页面在手机上完全不可达（只能靠顶栏一级 Tab 跳大类首页）。
   现在：顶栏汉堡按钮 → 侧栏以抽屉形式滑出；点击遮罩 / 菜单项 /
   ESC 关闭；路由变化（含浏览器前进后退）自动收起。
   桌面端（≥769px）行为完全不变，is-mobile-open 不生效。 */
const mobileSidebarOpen = ref(false)

function closeMobileSidebar() {
  mobileSidebarOpen.value = false
}

function toggleMobileSidebar() {
  mobileSidebarOpen.value = !mobileSidebarOpen.value
}

/**
 * 抽屉内的菜单跳转：先收起抽屉再跳，避免遮罩挡住刚打开的目标页。
 * @param {string} path - 目标路径
 */
function handleSidebarNavigate(path) {
  closeMobileSidebar()
  handleNavigate(path)
}

// 路由变化时收起抽屉（浏览器前进/后退同样适用）
watch(
  () => route.path,
  () => closeMobileSidebar()
)

// ESC 关闭抽屉；与 GlobalAlarmModal 的 ESC 监听互不影响（各自处理自己的状态）
useEscapeClose(closeMobileSidebar, mobileSidebarOpen)

/**
 * 打开通知抽屉
 */
function openNotificationDrawer() {
  notificationDrawerVisible.value = true
}

/**
 * 关闭通知抽屉
 */
function closeNotificationDrawer() {
  notificationDrawerVisible.value = false
}

/**
 * 处理通知抽屉内的红点刷新事件
 */
function handleBadgeRefresh() {
  refreshDynamicBadges()
}

/**
 * 点击一级导航大类时的处理。
 * 当前页面已属于该大类时不跳转，避免把用户从子页面拽回大类首页；
 * 否则跳转到该大类排序后的第一个可访问页面（groups 已按角色裁剪并排序）。
 * 先同步更新 activeGroupId，让 Tab 与侧栏立即响应，路由确认后 watch 会再校正一次。
 *
 * @param {string} groupId - 目标大类 ID
 */
function handleGroupSelect(groupId) {
  const group = groups.value.find((g) => g.id === groupId)
  if (!group) return

  activeGroupId.value = groupId
  if (group.items.some((item) => matchPath(item.path, route.path))) return

  const first = group.items[0]
  if (first) handleNavigate(first.path)
}

/* ── 全局轻提示（Toast） ─────────────────────────────────────
   子页面统一通过 emit('notify', msg) 上抛操作反馈。若布局层不接收，
   这些 emit 会静默丢失，用户点了按钮却看不到任何结果——这正是
   DashboardLayout 里 notify() 的职责，MainLayout 必须补齐，
   否则换成新布局后全站操作反馈集体失效。
   样式复用 styles.css 的全局 .toast / .toast-enter-*，不重复造轮子。 */
const toast = ref('')
let toastTimer = null

/**
 * 接收子页面的 notify 事件并展示轻提示。
 * 同一时刻只保留最后一条：连续操作时旧提示直接被覆盖，
 * 避免多条 Toast 叠在一起把页面内容挡住。
 *
 * @param {string} message - 提示文案
 */
function notify(message) {
  const text = typeof message === 'string' ? message.trim() : ''
  if (!text) return
  toast.value = text
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = ''
    toastTimer = null
  }, 2600)
}

const routeLoading = ref(false)
let routeLoadingTimer = null
let removeBeforeRoute = null
let removeAfterRoute = null

function startRouteLoading() {
  routeLoading.value = true
  window.clearTimeout(routeLoadingTimer)
  // 守卫异常/中止时 afterEach 可能不触发，避免进度条永久停留。
  routeLoadingTimer = window.setTimeout(() => {
    routeLoading.value = false
    routeLoadingTimer = null
  }, 10_000)
}

function endRouteLoading() {
  routeLoading.value = false
  window.clearTimeout(routeLoadingTimer)
  routeLoadingTimer = null
}

removeBeforeRoute = router.beforeEach((to, from, next) => {
  if (to.fullPath !== from.fullPath) startRouteLoading()
  next()
})
removeAfterRoute = router.afterEach(() => endRouteLoading())

onMounted(() => {
  refreshDynamicBadges()
  badgeTimer = window.setInterval(refreshDynamicBadges, 60_000)
})

onBeforeUnmount(() => {
  removeBeforeRoute?.()
  removeAfterRoute?.()
  if (routeLoadingTimer !== null) {
    window.clearTimeout(routeLoadingTimer)
    routeLoadingTimer = null
  }
  if (badgeTimer !== null) {
    window.clearInterval(badgeTimer)
    badgeTimer = null
  }
  if (toastTimer !== null) {
    window.clearTimeout(toastTimer)
    toastTimer = null
  }
})
</script>

<template>
  <div class="main-layout">
    <!-- 顶部 Header：64px，内含一级导航 -->
    <TopToolbar
      :groups="groups"
      :active-group-id="activeGroupId"
      :badges="dynamicBadges"
      :unread-count="unreadCount"
      :sidebar-open="mobileSidebarOpen"
      @navigate="handleNavigate"
      @select-group="handleGroupSelect"
      @open-notification-drawer="openNotificationDrawer"
      @toggle-sidebar="toggleMobileSidebar"
    />

    <!-- 主体区域：左侧 260px 固定导航 + 右侧 flex:1 内容 -->
    <div class="main-layout-body">
      <aside
        id="app-sidebar"
        class="main-layout-sidebar"
        :class="{ 'is-mobile-open': mobileSidebarOpen }"
      >
        <!-- 平铺显示当前大类的子菜单 -->
        <SidebarNav
          :group="activeGroup"
          :allowed-paths="allowedPaths"
          :badges="dynamicBadges"
          :current-path="route.path"
          @navigate="handleSidebarNavigate"
        />
      </aside>

      <main class="main-layout-content">
        <div v-if="routeLoading" class="route-loading-bar" role="progressbar" aria-label="页面加载中">
          <span></span>
        </div>
        <ContentArea
          :show-search="false"
          :show-filter="false"
          :show-create="false"
        >
          <!-- @notify 是全站操作反馈的唯一出口：所有子页面的 emit('notify', msg)
               都必须在这里被接住，否则子页面里的操作提示会静默丢失 -->
          <router-view @notify="notify" />
        </ContentArea>
      </main>
    </div>

    <!-- 移动端侧栏遮罩：复用 styles.css 已有的 .sidebar-overlay
         （桌面端 display:none；≤768px 由 .is-visible 控制淡入淡出与层级 44）。
         放在 body 之后，保证覆盖层级在侧栏(45)之下、顶栏(40)之上。 -->
    <div
      class="sidebar-overlay"
      :class="{ 'is-visible': mobileSidebarOpen }"
      aria-hidden="true"
      @click="closeMobileSidebar"
    ></div>

    <!-- 全局轻提示：复用 styles.css 的 .toast 样式与 .toast-enter 过渡 -->
    <Transition name="toast">
      <div v-if="toast" class="toast main-layout-toast">
        <ShieldCheck :size="17" aria-hidden="true" />
        <span>{{ toast }}</span>
      </div>
    </Transition>

    <!-- 全局通知抽屉 -->
    <NotificationDrawer
      :visible="notificationDrawerVisible"
      :badges="dynamicBadges"
      @close="closeNotificationDrawer"
      @badge-refresh="handleBadgeRefresh"
    />

    <!-- 全局紧急警报：挂在布局顶层，任意路由下收到新待确认红/橙/黄/蓝预警都会按档位弹出 -->
    <GlobalAlarmModal />
  </div>
</template>

<style scoped>
/* ── 整体布局：100vh 高度，Flex 列方向 ── */
.main-layout {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100vh;
  background: transparent;
  color: var(--text-main, #2c3b34);
  overflow: hidden;
}

/* ── 主体区域：Flex 横向布局 ── */
.main-layout-body {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

/* ── 左侧固定导航：260px ── */
.main-layout-sidebar {
  width: 260px;
  flex-shrink: 0;
  background: var(--bg-card, #fff);
  border-right: 1px solid var(--border-color, #d1dcd5);
  overflow: hidden;
}

/* ── 右侧内容区：flex:1，独立滚动 ── */
.main-layout-content {
  position: relative;
  flex: 1;
  min-width: 0;
  overflow: hidden auto;
  scrollbar-gutter: stable;
  scroll-behavior: smooth;
}

.route-loading-bar {
  position: absolute;
  inset: 0 0 auto;
  z-index: 60;
  height: 2px;
  overflow: hidden;
  pointer-events: none;
  background: rgb(111 158 127 / 16%);
}

.route-loading-bar > span {
  display: block;
  width: 36%;
  height: 100%;
  background: var(--c-brand);
  animation: route-loading-slide 1.1s ease-in-out infinite;
}

@keyframes route-loading-slide {
  from {
    transform: translateX(-120%);
  }

  to {
    transform: translateX(320%);
  }
}

/* ── 全局轻提示 ──
   基础外观复用 styles.css 的全局 .toast，这里只做两件布局层的事：
   1) 抬高 z-index，确保盖住顶栏与侧栏（全局 .toast 是 30，低于布局层）；
   2) 沿用 MainLayout 的 --c-* 色板，视觉上与新版布局一致。
   .main-layout 虽有 overflow: hidden，但祖先链上没有 transform/filter，
   因此 position: fixed 仍以视口为基准，不会被布局容器裁掉。 */
.main-layout-toast {
  z-index: var(--z-toast, 60);
  max-width: min(420px, calc(100vw - 48px));
  background: var(--c-brand-deeper, #245b5d);
}

@media (width <= 1024px) {
  .main-layout-sidebar {
    width: 220px;
  }
}

@media (width >= 769px) {
  .main-layout-sidebar {
    transition: width 0.24s var(--ease-ios, cubic-bezier(0.32, 0.72, 0, 1));
  }
}

@media (width <= 768px) {
  /* ── 侧栏抽屉（修复：<=768px 侧栏 display:none 且全站无入口）──
     不再 display:none（那样二级页面在手机上彻底不可达），改为
     「默认移出屏幕、汉堡按钮唤出」。用 transform + visibility 过渡，
     display 不可过渡，直接切换会「啪」地闪没。 */
  .main-layout-sidebar {
    position: fixed;
    top: 108px; /* 顶栏 64px + 一级导航第二行 44px；抽屉从导航下方滑出 */
    bottom: 0;
    left: 0;
    width: min(280px, 85vw);
    z-index: var(--z-sidebar, 45); /* 高于顶栏(40)，低于弹窗(50) */
    visibility: hidden;
    transform: translateX(-100%);
    transition:
      transform 0.46s var(--ease-ios, cubic-bezier(0.32, 0.72, 0, 1)),
      visibility 0.46s,
      box-shadow 0.46s;
    box-shadow: none;
    will-change: transform;
    overflow-y: auto;
    -webkit-overflow-scrolling: touch;
  }

  .main-layout-sidebar.is-mobile-open {
    visibility: visible;
    transform: translateX(0);
    box-shadow: 8px 0 40px rgb(10 50 25 / 28%);
  }

  .main-layout-content {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .main-layout-sidebar {
    transition: none;
  }
}
</style>
