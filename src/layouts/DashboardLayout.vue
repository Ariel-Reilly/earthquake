<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Activity,
  Bell,
  ChevronDown,
  ChevronRight,
  ChevronsLeft,
  CircleHelp,
  FileText,
  LayoutDashboard,
  ListChecks,
  LogOut,
  Mountain,
  Radio,
  Search,
  Settings,
  ShieldCheck,
} from 'lucide-vue-next'
import { dashboardMenu } from '../data/dashboardData'
import PermissionButton from '../components/PermissionButton.vue'
import FormModal from '../components/FormModal.vue'
import AppFeedback from '../components/AppFeedback.vue'
import AnimatedSidebarBackground from '../components/AnimatedSidebarBackground.vue'
import AnimatedDashboardBackground from '../components/AnimatedDashboardBackground.vue'
import { dashboardApi, logoutRequest } from '../services/dashboardApi'
import { stopRealtimeSync } from '../services/realtimeService'
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
let timerId
let feedbackTimer

const currentPath = computed(() => route.path)
const userRoleLabel = computed(() => getRoleLabel(user.value.role))
const visibleMenu = computed(() => dashboardMenu.filter((item) => getAllowedMenuPaths(user.value.role).includes(item.path)))
const activeMenu = computed(() => visibleMenu.value.find((item) => (item.path === '/' ? currentPath.value === '/' : currentPath.value.startsWith(item.path))) ?? visibleMenu.value[0] ?? dashboardMenu[0])

const timeLabel = computed(() => currentTime.value.toLocaleTimeString('zh-CN', { hour12: false }))
const currentLeafLabel = computed(() => {
  const segments = route.path.split('/').filter(Boolean)
  if (!segments.length) return ''
  if (route.path.includes('/hazards/')) return '灾害点详情'
  if (route.path.includes('/devices/')) return '设备详情'
  if (route.path.includes('/tasks/')) return '任务详情'
  if (route.path.includes('/warnings/')) return '预警详情'
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
    // 1) 通知后端作废会话（尽力而为，失败不阻断本地退出）
    await logoutRequest()
  } catch {
    // 忽略后端登出失败，保证本地退出始终可用
  } finally {
    // 2) 停止实时数据通道（WebSocket / 定时轮询）
    stopRealtimeSync({ keepListeners: false })
    // 3) 清理本地登录态
    clearAuthSession()
    // 4) 关闭弹窗并跳转登录页（replace 防止回退到受保护页）
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
    { loadingText: '巡检任务创建中...', successText: '巡检任务已创建', failureText: '创建巡检任务失败' },
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

watch(
  () => route.path,
  () => {
    showSearch.value = false
    showNotifications.value = false
  },
)

onMounted(() => {
  timerId = window.setInterval(() => {
    currentTime.value = new Date()
  }, 1000)
})

onBeforeUnmount(() => {
  window.clearInterval(timerId)
  window.clearTimeout(feedbackTimer)
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <AnimatedSidebarBackground />
      <div class="sidebar-content">
        <div class="brand">
          <div class="brand-mark"><Mountain :size="22" :stroke-width="2.4" /></div>
          <div class="brand-copy"><strong>地脉智巡</strong><span>GEO-SENTRY</span></div>
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
          <div class="nav-label">工作台</div>
          <button
            v-for="item in visibleMenu"
            :key="item.path"
            class="nav-item"
            :class="{ active: currentPath === item.path || (item.path !== '/' && currentPath.startsWith(item.path)) }"
            :title="sidebarCollapsed ? item.label : undefined"
            @click="goTo(item.path)"
          >
            <component :is="item.path === '/' ? LayoutDashboard : item.path === '/hazards' ? Mountain : item.path === '/tasks' ? ListChecks : item.path === '/devices' ? Radio : item.path === '/warnings' ? Bell : item.path === '/emergency' ? ShieldCheck : Activity" :size="18" />
            <span>{{ item.label }}</span>
            <em v-if="item.badge" :class="{ danger: item.danger }">{{ item.badge }}</em>
          </button>
        </nav>

        <div class="sidebar-footer">
          <button class="nav-item" :class="{ active: route.path === '/settings' }" title="系统设置" @click="goTo('/settings')"><Settings :size="18" /><span>系统设置</span></button>
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
          <span>工作台</span>
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
            v-if="hasPermission(user.role, 'tasks:create')"
            permission="tasks:create"
            variant="primary"
            :icon="ListChecks"
            class="topbar-action"
            @click="({ allowed, reason }) => allowed ? openCreateTaskModal() : notify(reason)"
          >创建巡检任务</PermissionButton>
          <button class="icon-btn" @click="showSearch = !showSearch"><Search :size="18" /></button>
          <button class="icon-btn notification-btn" @click="showNotifications = !showNotifications"><Bell :size="18" /><i></i></button>
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
            <strong>通知中心</strong>
            <p><span class="notice-dot orange"></span>青龙坡预警待处置 <small>刚刚</small></p>
            <p><span class="notice-dot yellow"></span>今日巡检任务还有 3 项 <small>12分钟前</small></p>
            <p><span class="notice-dot orange"></span>设备离线 1 台 <small>21分钟前</small></p>
          </div>
        </Transition>
      </header>

      <section class="page-content">
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
