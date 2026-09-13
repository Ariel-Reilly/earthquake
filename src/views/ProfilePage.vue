<script setup>
/**
 * 个人中心 / ProfilePage。
 * 4 个 Tab：个人信息 / 修改密码 / 通知偏好 / 主题语言。
 * 注销按钮统一在底部操作区。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bell,
  Globe2,
  KeyRound,
  LogOut,
  Moon,
  Save,
  ShieldCheck,
  User,
  UserCircle,
} from 'lucide-vue-next'
import ProfileInfo from '../components/profile/ProfileInfo.vue'
import PasswordChange from '../components/profile/PasswordChange.vue'
import NotificationPreference from '../components/profile/NotificationPreference.vue'
import ThemeLanguage from '../components/profile/ThemeLanguage.vue'
import { clearAuthSession, getAuthUser, getRoleLabel } from '../stores/auth'

const emit = defineEmits(['notify', 'open-task-modal', 'create-task'])
defineProps({ searchTerm: { type: String, default: '' }, userRole: { type: String, default: 'admin' } })

const router = useRouter()
const user = computed(() => getAuthUser() ?? { name: '李晨曦', role: 'admin' })
const roleLabel = computed(() => getRoleLabel(user.value.role))

const tab = ref('info')

const tabs = [
  { id: 'info',     label: '个人信息',     icon: User },
  { id: 'pwd',      label: '密码修改',     icon: KeyRound },
  { id: 'notif',    label: '通知偏好',     icon: Bell },
  { id: 'theme',    label: '主题与语言',   icon: Globe2 },
]

function logout() {
  if (!window.confirm('确认注销登录？')) return
  clearAuthSession()
  emit('notify', '已退出登录')
  router.push('/')
}
</script>

<template>
  <div class="profile-page">
    <header class="page-head">
      <div class="left">
        <h2><UserCircle :size="22" /> 个人中心</h2>
        <p>{{ user.name }} · {{ roleLabel }} · {{ user.email || 'admin@geosentry.cn' }}</p>
      </div>
      <button class="logout" @click="logout">
        <LogOut :size="14" /> 退出登录
      </button>
    </header>

    <nav class="tabs">
      <button
        v-for="t in tabs"
        :key="t.id"
        :class="{ active: tab === t.id }"
        @click="tab = t.id"
      >
        <component :is="t.icon" :size="14" /> {{ t.label }}
      </button>
    </nav>

    <section class="content">
      <ProfileInfo v-if="tab === 'info'" @notify="(m) => emit('notify', m)" />
      <PasswordChange v-else-if="tab === 'pwd'" @notify="(m) => emit('notify', m)" />
      <NotificationPreference v-else-if="tab === 'notif'" @notify="(m) => emit('notify', m)" />
      <ThemeLanguage v-else-if="tab === 'theme'" @notify="(m) => emit('notify', m)" />
    </section>

    <footer class="foot">
      <ShieldCheck :size="14" /> 所有操作均记录到审计日志，关键变更会触发通知中心消息。
    </footer>
  </div>
</template>

<style scoped>
.profile-page { padding: 22px 28px; display: flex; flex-direction: column; gap: 16px; height: 100%; overflow: auto; }
.page-head {
  display: flex; justify-content: space-between; align-items: center;
  background: linear-gradient(135deg, #5b8ef9 0%, #6366f1 100%);
  color: white; padding: 18px 22px; border-radius: 12px;
  box-shadow: 0 8px 24px rgba(91,142,249,0.15);
}
.page-head .left h2 { display: inline-flex; align-items: center; gap: 8px; margin: 0; font-size: 20px; }
.page-head .left p { margin: 6px 0 0; font-size: 13px; opacity: 0.85; }
.logout {
  background: rgba(255,255,255,0.18); color: white; border: 1px solid rgba(255,255,255,0.4);
  padding: 6px 14px; border-radius: 999px; cursor: pointer; display: inline-flex; align-items: center; gap: 4px;
  font-size: 13px;
}
.logout:hover { background: rgba(255,255,255,0.3); }
.tabs { display: flex; gap: 6px; border-bottom: 1px solid var(--dashboard-border, #eef2f7); padding-bottom: 0; }
.tabs button {
  display: inline-flex; align-items: center; gap: 4px;
  background: transparent; border: none;
  padding: 10px 18px; cursor: pointer;
  color: var(--dashboard-muted, #64748b);
  border-bottom: 2px solid transparent;
  font-size: 14px;
}
.tabs button.active { color: #2563eb; border-color: #2563eb; font-weight: 600; }
.content {
  background: var(--dashboard-card-bg, #fff);
  border: 1px solid var(--dashboard-border, #eef2f7);
  border-radius: 12px; padding: 22px 24px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.04);
}
.foot {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--dashboard-muted, #6b7280);
  border-top: 1px dashed var(--dashboard-border, #eef2f7); padding-top: 12px;
}
</style>
