<script setup>
/**
 * 个人中心（三合一统一页面）。
 * 左侧分类导航 + 右侧内容区布局，整合 6 个分类：
 * 1. 基本资料 — ProfileInfo 组件
 * 2. 账号安全 — PasswordChange 组件（密码修改）
 * 3. 通知偏好 — NotificationPreference 组件
 * 4. 显示设置 — ThemeLanguage 组件（主题/语言/界面密度）
 * 5. 帮助反馈 — 版本信息、帮助中心入口、意见反馈
 * 6. 服务状态 — 各服务连接状态展示（只读）
 */
import { computed, ref } from 'vue'
import ConfirmDialog from '../../components/common/ConfirmDialog.vue'
import { useRouter } from 'vue-router'
import {
  Bell,
  Database,
  HelpCircle,
  KeyRound,
  LogOut,
  Monitor,
  Server,
  ShieldCheck,
  User,
  UserCircle,
} from 'lucide-vue-next'
import ProfileInfo from '../../components/profile/ProfileInfo.vue'
import PasswordChange from '../../components/profile/PasswordChange.vue'
import NotificationPreference from '../../components/profile/NotificationPreference.vue'
import ThemeLanguage from '../../components/profile/ThemeLanguage.vue'
import { clearAuthSession, getAuthUser, getRoleLabel } from '../../stores/auth'

const emit = defineEmits(['notify', 'open-task-modal', 'create-task'])
const confirmDialogRef = ref(null)
defineProps({ searchTerm: { type: String, default: '' }, userRole: { type: String, default: 'admin' } })

const router = useRouter()
const user = computed(() => getAuthUser() ?? { name: '李晨曦', role: 'admin', email: 'admin@disasterguard.cn' })
const roleLabel = computed(() => getRoleLabel(user.value.role))
const avatarLetter = computed(() => (user.value.name || '?').charAt(0).toUpperCase())

// 当前激活的分类
const activeTab = ref('profile')

// 左侧导航项配置
const navItems = [
  { key: 'profile',       label: '基本资料', icon: User,        desc: '个人信息与头像' },
  { key: 'security',      label: '账号安全', icon: KeyRound,    desc: '密码与安全设置' },
  { key: 'notifications', label: '通知偏好', icon: Bell,        desc: '预警通知渠道' },
  { key: 'display',       label: '显示设置', icon: Monitor,     desc: '主题语言与密度' },
  { key: 'help',          label: '帮助反馈', icon: HelpCircle,  desc: '版本与帮助中心' },
  { key: 'service',       label: '服务状态', icon: Server,      desc: '系统服务运行情况' },
]

function logout() {
  return (async () => {
    const ok = await confirmDialogRef.value?.show({
      title: '确认操作',
      message: '确认注销登录？',
      confirmText: '确认',
      cancelText: '取消',
      isDanger: true
    })
    if (!ok) return
    clearAuthSession()
    emit('notify', '已退出登录')
    router.push('/')
  })()
}

// ========== 帮助反馈 ==========
const appVersion = 'v1.2.0'
const buildDate = '2025-03-15'

function openHelpCenter() {
  emit('notify', '帮助中心即将开放，敬请期待')
}

function submitFeedback() {
  emit('notify', '反馈功能开发中，可联系系统管理员提交建议')
}

// ========== 服务状态 ==========
const serviceStatus = ref([
  { name: 'RESTful API 网关', detail: 'http://localhost:8080/api', state: '正常', color: 'teal', icon: Server },
  { name: 'MQTT 数据接入', detail: 'mqtt://mqtt.geo-sentry.local:1883', state: '正常', color: 'teal', icon: Database },
  { name: '时序数据库', detail: 'TDengine · 设备在线', state: '正常', color: 'teal', icon: Database },
  { name: '消息服务', detail: '站内通知推送通道', state: '正常', color: 'teal', icon: Bell },
])
const serviceLoading = ref(false)

function runServiceCheck() {
  if (serviceLoading.value) return
  serviceLoading.value = true
  serviceStatus.value.forEach((item) => { item.state = '检测中'; item.color = 'amber' })
  emit('notify', '正在检测服务状态...')
  window.setTimeout(() => {
    serviceStatus.value = [
      { name: 'RESTful API 网关', detail: 'http://localhost:8080/api', state: '正常', color: 'teal', icon: Server },
      { name: 'MQTT 数据接入', detail: 'mqtt://mqtt.geo-sentry.local:1883', state: '正常', color: 'teal', icon: Database },
      { name: '时序数据库', detail: 'TDengine · 设备在线', state: '正常', color: 'teal', icon: Database },
      { name: '消息服务', detail: '站内通知推送通道', state: '正常', color: 'teal', icon: Bell },
    ]
    serviceLoading.value = false
    emit('notify', '服务状态检测完成：全部正常')
  }, 1200)
}

// 服务状态为只读展示，检测按钮模拟检查过程
</script>

<template>
  <div class="profile-page">
    <!-- 页面顶部标题栏 -->
    <header class="profile-header">
      <div class="header-left">
        <h2><UserCircle :size="22" /> 个人中心</h2>
        <p>管理您的账号信息和偏好设置</p>
      </div>
      <button class="logout-btn" @click="logout">
        <LogOut :size="14" /> 退出登录
      </button>
    </header>

    <!-- 主体布局：左侧导航 + 右侧内容 -->
    <div class="profile-layout">
      <!-- 左侧导航 -->
      <aside class="profile-sidebar">
        <!-- 用户信息卡片 -->
        <div class="profile-user-card">
          <div class="user-avatar">{{ avatarLetter }}</div>
          <div class="user-info">
            <strong class="user-name">{{ user.name }}</strong>
            <span class="user-role">{{ roleLabel }}</span>
            <span class="user-email">{{ user.email || 'admin@disasterguard.cn' }}</span>
          </div>
        </div>

        <!-- 导航菜单 -->
        <nav class="profile-nav">
          <button
            v-for="item in navItems"
            :key="item.key"
            class="nav-item"
            :class="{ active: activeTab === item.key }"
            @click="activeTab = item.key"
          >
            <component :is="item.icon" :size="16" />
            <span class="nav-label">{{ item.label }}</span>
            <span class="nav-desc">{{ item.desc }}</span>
          </button>
        </nav>

        <!-- 底部安全提示 -->
        <div class="sidebar-foot">
          <ShieldCheck :size="13" />
          <span>所有操作均记录到审计日志</span>
        </div>
      </aside>

      <!-- 右侧内容区 -->
      <main class="profile-content">
        <!-- 1. 基本资料 -->
        <ProfileInfo
          v-if="activeTab === 'profile'"
          @notify="(m) => emit('notify', m)"
        />

        <!-- 2. 账号安全（密码修改） -->
        <PasswordChange
          v-else-if="activeTab === 'security'"
          @notify="(m) => emit('notify', m)"
        />

        <!-- 3. 通知偏好 -->
        <NotificationPreference
          v-else-if="activeTab === 'notifications'"
          @notify="(m) => emit('notify', m)"
        />

        <!-- 4. 显示设置 -->
        <ThemeLanguage
          v-else-if="activeTab === 'display'"
          @notify="(m) => emit('notify', m)"
        />

        <!-- 5. 帮助反馈 -->
        <div v-else-if="activeTab === 'help'" class="profile-section">
          <header class="section-head">
            <div>
              <h3><HelpCircle :size="18" /> 帮助与反馈</h3>
              <p>版本信息、使用帮助与意见反馈渠道</p>
            </div>
          </header>

          <div class="help-grid">
            <!-- 版本信息 -->
            <div class="help-card">
              <div class="help-card-head">
                <ShieldCheck :size="18" color="#6f9e7f" />
                <strong>版本信息</strong>
              </div>
              <div class="version-info">
                <div class="version-row">
                  <span>当前版本</span>
                  <code>{{ appVersion }}</code>
                </div>
                <div class="version-row">
                  <span>构建日期</span>
                  <code>{{ buildDate }}</code>
                </div>
                <div class="version-row">
                  <span>产品名称</span>
                  <code>地脉智巡 · DisasterGuard</code>
                </div>
              </div>
            </div>

            <!-- 帮助中心 -->
            <div class="help-card">
              <div class="help-card-head">
                <HelpCircle :size="18" color="#6f9e7f" />
                <strong>帮助中心</strong>
              </div>
              <p class="help-desc">查阅产品使用手册、功能指南与常见问题解答</p>
              <ul class="help-links">
                <li>快速入门指南</li>
                <li>预警处置工作流</li>
                <li>设备接入与配置</li>
                <li>权限与角色管理</li>
              </ul>
              <button class="help-btn primary" @click="openHelpCenter">
                打开帮助中心
              </button>
            </div>

            <!-- 意见反馈 -->
            <div class="help-card">
              <div class="help-card-head">
                <Bell :size="18" color="#cbaa76" />
                <strong>意见反馈</strong>
              </div>
              <p class="help-desc">遇到问题或有改进建议？欢迎向我们反馈</p>
              <div class="feedback-channels">
                <div class="feedback-item">
                  <span class="fb-label">系统管理员</span>
                  <code>admin@disasterguard.cn</code>
                </div>
                <div class="feedback-item">
                  <span class="fb-label">技术支持</span>
                  <code>support@disasterguard.cn</code>
                </div>
              </div>
              <button class="help-btn" @click="submitFeedback">
                提交反馈
              </button>
            </div>
          </div>
        </div>

        <!-- 6. 服务状态 -->
        <div v-else-if="activeTab === 'service'" class="profile-section">
          <header class="section-head">
            <div>
              <h3><Server :size="18" /> 服务状态</h3>
              <p>实时查看平台各服务与数据连接情况</p>
            </div>
            <button class="refresh-btn" :disabled="serviceLoading" @click="runServiceCheck">
              {{ serviceLoading ? '检测中...' : '检测服务' }}
            </button>
          </header>

          <div class="service-list">
            <div
              v-for="item in serviceStatus"
              :key="item.name"
              class="service-item"
            >
              <div class="service-icon">
                <component :is="item.icon" :size="18" />
              </div>
              <div class="service-meta">
                <strong>{{ item.name }}</strong>
                <small>{{ item.detail }}</small>
              </div>
              <em
                class="service-state"
                :class="item.color === 'teal' ? 'ok' : 'checking'"
              >
                {{ item.state }}
              </em>
            </div>
          </div>

          <div class="service-foot-note">
            <ShieldCheck :size="13" />
            <span>服务状态为只读展示，如有异常请联系系统管理员</span>
          </div>
        </div>
      </main>
    </div>
  </div>
    <ConfirmDialog ref="confirmDialogRef" />
</template>

<style scoped>
.profile-page {
  padding: 22px 28px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  overflow: auto;
}

/* 顶部标题栏 */
.profile-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, var(--c-brand) 0%, var(--c-brand-hover) 100%);
  color: white;
  padding: 18px 22px;
  border-radius: var(--r-lg);
  box-shadow: 0 8px 24px rgba(79, 143, 153, 0.18);
}
.profile-header .header-left h2 {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 20px;
}
.profile-header .header-left p {
  margin: 6px 0 0;
  font-size: 13px;
  opacity: 0.85;
}
.logout-btn {
  background: rgba(255, 255, 255, 0.18);
  color: white;
  border: 1px solid rgba(255, 255, 255, 0.4);
  padding: 6px 14px;
  border-radius: var(--r-pill);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  transition: background 0.2s;
}
.logout-btn:hover {
  background: rgba(255, 255, 255, 0.3);
}

/* 主体布局 */
.profile-layout {
  display: flex;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

/* 左侧边栏 */
.profile-sidebar {
  width: 240px;
  flex-shrink: 0;
  background: var(--dashboard-card-bg, var(--c-surface));
  border: 1px solid var(--dashboard-border, var(--c-brand-mist));
  border-radius: var(--r-lg);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  box-shadow: var(--shadow-md);
}

/* 用户卡片 */
.profile-user-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 18px 12px 16px;
  background: linear-gradient(180deg, rgba(79, 143, 153, 0.08) 0%, transparent 100%);
  border-radius: var(--r-md);
  border-bottom: 1px solid var(--dashboard-border, var(--c-brand-mist));
}
.user-avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--c-brand) 0%, var(--c-brand-hover) 100%);
  color: var(--c-on-brand);
  display: grid;
  place-items: center;
  font-size: 22px;
  font-weight: 700;
  box-shadow: 0 4px 14px rgba(79, 143, 153, 0.28);
}
.user-info {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  text-align: center;
}
.user-name {
  color: var(--dashboard-text, var(--c-scrim));
  font-size: 15px;
  font-weight: 600;
}
.user-role {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: var(--r-pill);
  background: rgba(79, 143, 153, 0.12);
  color: var(--c-brand);
  font-weight: 600;
  letter-spacing: 0.03em;
}
.user-email {
  font-size: 11px;
  color: var(--dashboard-muted, var(--c-text-4));
}

/* 导航菜单 */
.profile-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: transparent;
  border: none;
  border-radius: var(--r-sm);
  cursor: pointer;
  color: var(--dashboard-text, var(--c-ink-1));
  font-size: 13px;
  text-align: left;
  transition: all 0.2s;
  position: relative;
}
.nav-item:hover {
  background: var(--dashboard-muted-bg, var(--c-brand-mist));
  color: var(--dashboard-text, var(--c-scrim));
}
.nav-item.active {
  background: linear-gradient(135deg, rgba(79, 143, 153, 0.12), rgba(109, 191, 165, 0.12));
  color: var(--c-brand-ink);
  font-weight: 600;
}
.nav-item.active::before {
  content: '';
  position: absolute;
  left: -16px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  background: var(--c-brand);
  border-radius: 0 3px 3px 0;
}
.nav-label {
  flex: 1;
}
.nav-desc {
  display: none;
}

/* 底部提示 */
.sidebar-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--dashboard-muted, var(--c-text-4));
  padding-top: 10px;
  border-top: 1px solid var(--dashboard-border, var(--c-brand-mist));
}

/* 右侧内容区 */
.profile-content {
  flex: 1;
  min-width: 0;
  background: var(--dashboard-card-bg, var(--c-surface));
  border: 1px solid var(--dashboard-border, var(--c-brand-mist));
  border-radius: var(--r-lg);
  padding: 22px 24px;
  box-shadow: var(--shadow-md);
  overflow-y: auto;
}

/* 通用 section 样式（帮助反馈、服务状态） */
.profile-section {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  border-bottom: 1px solid var(--dashboard-border, var(--c-brand-mist));
  padding-bottom: 14px;
}
.section-head h3 {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 18px;
  color: var(--dashboard-text, var(--c-scrim));
}
.section-head p {
  margin: 4px 0 0;
  color: var(--dashboard-muted, var(--c-text-2));
  font-size: 13px;
}
.refresh-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  border: 1px solid var(--dashboard-border, var(--c-brand-tint));
  background: var(--c-surface);
  border-radius: var(--r-sm);
  font-size: 12px;
  cursor: pointer;
  color: var(--c-ink-1);
  transition: background 0.2s;
}
.refresh-btn:hover {
  background: var(--c-brand-mist);
}
.refresh-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* ========== 帮助反馈 ========== */
.help-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.help-card {
  background: var(--dashboard-muted-bg, var(--c-surface-2));
  border: 1px solid var(--dashboard-border, var(--c-separator));
  border-radius: var(--r-md);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.help-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--dashboard-text, var(--c-scrim));
  font-size: 14px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--dashboard-border, var(--c-separator));
}
.help-desc {
  margin: 0;
  font-size: 12px;
  color: var(--dashboard-muted, var(--c-text-2));
  line-height: 1.55;
}
.version-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.version-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--dashboard-muted, var(--c-text-2));
}
.version-row code {
  background: var(--c-surface);
  padding: 2px 8px;
  border-radius: var(--r-xs);
  font-size: 11px;
  color: var(--dashboard-text, var(--c-scrim));
  border: 1px solid var(--dashboard-border, var(--c-separator));
}
.help-links {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.help-links li {
  font-size: 12px;
  color: var(--dashboard-text, var(--c-ink-1));
  padding: 4px 8px;
  background: var(--c-surface);
  border-radius: var(--r-sm);
  border: 1px solid var(--dashboard-border, var(--c-separator));
  cursor: pointer;
  transition: all 0.2s;
}
.help-links li:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
}
.help-btn {
  padding: 7px 14px;
  border: 1px solid var(--dashboard-border, var(--c-brand-tint));
  background: var(--c-surface);
  border-radius: var(--r-sm);
  font-size: 13px;
  cursor: pointer;
  color: var(--c-ink-1);
  transition: all 0.2s;
}
.help-btn:hover {
  background: var(--c-brand-mist);
}
.help-btn.primary {
  background: var(--c-brand);
  color: var(--c-on-brand);
  border-color: var(--c-brand);
}
.help-btn.primary:hover {
  background: var(--sys-blue-deep);
}
.feedback-channels {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.feedback-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 8px;
  background: var(--c-surface);
  border-radius: var(--r-sm);
  border: 1px solid var(--dashboard-border, var(--c-separator));
}
.fb-label {
  font-size: 11px;
  color: var(--dashboard-muted, var(--c-text-4));
}
.feedback-item code {
  font-size: 12px;
  color: var(--dashboard-text, var(--c-scrim));
}

/* ========== 服务状态 ========== */
.service-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.service-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  background: var(--dashboard-muted-bg, var(--c-surface-2));
  border: 1px solid var(--dashboard-border, var(--c-separator));
  border-radius: var(--r-md);
  transition: all 0.2s;
}
.service-item:hover {
  border-color: var(--c-brand-tint);
}
.service-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--r-md);
  background: var(--c-surface);
  border: 1px solid var(--dashboard-border, var(--c-separator));
  display: grid;
  place-items: center;
  color: var(--c-brand);
  flex-shrink: 0;
}
.service-meta {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.service-meta strong {
  font-size: 14px;
  color: var(--dashboard-text, var(--c-scrim));
}
.service-meta small {
  font-size: 12px;
  color: var(--dashboard-muted, var(--c-text-2));
}
.service-state {
  font-style: normal;
  font-size: 12px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: var(--r-pill);
  letter-spacing: 0.03em;
  flex-shrink: 0;
}
.service-state.ok {
  background: rgba(42, 129, 84, 0.12);
  color: var(--c-brand-deep);
}
.service-state.checking {
  background: rgba(224, 160, 80, 0.15);
  color: var(--c-warning);
}
.service-foot-note {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--dashboard-muted, var(--c-text-4));
  padding-top: 12px;
  border-top: 1px dashed var(--dashboard-border, var(--c-brand-mist));
}

/* 响应式 */
@media (max-width: 960px) {
  .help-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 768px) {
  .profile-layout {
    flex-direction: column;
  }
  .profile-sidebar {
    width: 100%;
  }
  .profile-nav {
    flex-direction: row;
    flex-wrap: wrap;
  }
  .nav-item {
    flex: 1;
    min-width: calc(50% - 4px);
    justify-content: center;
  }
  .nav-item.active::before {
    display: none;
  }
}
</style>
