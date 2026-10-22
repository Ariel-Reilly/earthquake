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

onMounted(() => {
  refreshDynamicBadges()
  badgeTimer = window.setInterval(refreshDynamicBadges, 60_000)
})

onBeforeUnmount(() => {
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
      @navigate="handleNavigate"
      @select-group="handleGroupSelect"
    />

    <!-- 主体区域：左侧 260px 固定导航 + 右侧 flex:1 内容 -->
    <div class="main-layout-body">
      <aside class="main-layout-sidebar">
        <!-- 平铺显示当前大类的子菜单 -->
        <SidebarNav
          :group="activeGroup"
          :allowed-paths="allowedPaths"
          :badges="dynamicBadges"
          :current-path="route.path"
          @navigate="handleNavigate"
        />
      </aside>

      <main class="main-layout-content">
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

    <!-- 全局轻提示：复用 styles.css 的 .toast 样式与 .toast-enter 过渡 -->
    <Transition name="toast">
      <div v-if="toast" class="toast main-layout-toast">
        <ShieldCheck :size="17" aria-hidden="true" />
        <span>{{ toast }}</span>
      </div>
    </Transition>
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
  color: var(--text-main, #2C3B34);
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
  background: var(--bg-card, #FFFFFF);
  border-right: 1px solid var(--border-color, #D1DCD5);
  overflow: hidden;
}

/* ── 右侧内容区：flex:1，独立滚动 ── */
.main-layout-content {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  overflow-x: hidden;
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

@media (max-width: 1024px) {
  .main-layout-sidebar {
    width: 220px;
  }
}

@media (max-width: 768px) {
  .main-layout-sidebar {
    display: none;
  }

  .main-layout-content {
    width: 100%;
  }
}
</style>
