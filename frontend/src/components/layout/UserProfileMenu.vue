/**
 * 用户个人中心下拉菜单组件
 * 
 * 【UI 结构】
 * - 触发器：圆形头像（莫兰迪绿背景 + 白色首字母） + ChevronDown
 * - 下拉菜单：纯白卡片，圆角 + 阴影，向下淡入过渡
 * 
 * 【菜单项】
 * - 个人资料 → /dashboard/profile
 * - 账号安全 → /dashboard/account-security
 * - 系统设置 → /dashboard/settings
 * - 分割线
 * - 退出登录（红色字）→ 清除 Token 并跳回 /
 * 
 * @component
 */
<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { User, Shield, Settings, LogOut, ChevronDown } from 'lucide-vue-next'
import { getAuthUser, clearAuthSession } from '../../stores/auth'

const router = useRouter()

const isOpen = ref(false)
const rootRef = ref(null)

// 登录态的真实键名是 disasterguard_*，必须经由 auth store 读写，
// 直接操作 localStorage('token') 清不掉会话
const user = computed(() => getAuthUser() ?? { name: '用户' })

const displayName = computed(() => user.value.name || user.value.username || '用户')
const initial = computed(() => displayName.value.charAt(0).toUpperCase())

// 悬浮展开需要关闭延迟，否则指针从触发器移向菜单的缝隙时菜单会闪退
let closeTimer = null

function openMenu() {
  clearTimeout(closeTimer)
  isOpen.value = true
}

function closeMenu() {
  clearTimeout(closeTimer)
  isOpen.value = false
}

function scheduleClose() {
  clearTimeout(closeTimer)
  closeTimer = setTimeout(closeMenu, 150)
}

function toggleMenu() {
  isOpen.value ? closeMenu() : openMenu()
}

/**
 * 跳转到个人中心相关页面并收起菜单。
 * @param {string} path - 完整路由路径，如 /dashboard/profile
 */
function navigateTo(path) {
  closeMenu()
  router.push(path)
}

function handleLogout() {
  closeMenu()
  clearAuthSession()
  router.push('/')
}

function handleClickOutside(event) {
  if (rootRef.value && !rootRef.value.contains(event.target)) closeMenu()
}

function handleKeydown(event) {
  if (event.key === 'Escape') closeMenu()
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
  document.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  clearTimeout(closeTimer)
  document.removeEventListener('click', handleClickOutside)
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div
    ref="rootRef"
    class="user-profile-menu"
    @mouseenter="openMenu"
    @mouseleave="scheduleClose"
  >
    <!-- 触发器：头像 + 用户名 + 箭头 -->
    <button
      type="button"
      class="user-profile-trigger"
      :class="{ 'is-active': isOpen }"
      aria-haspopup="menu"
      :aria-expanded="isOpen"
      @click="toggleMenu"
    >
      <!-- 圆形头像 -->
      <div class="user-avatar">
        <span class="user-initial">{{ initial }}</span>
      </div>

      <!-- 用户名（可选显示） -->
      <span class="user-name">{{ displayName }}</span>

      <!-- 箭头图标 -->
      <ChevronDown 
        :size="14" 
        class="chevron-icon" 
        :class="{ 'is-rotated': isOpen }"
      />
    </button>

    <!-- 下拉菜单 -->
    <Transition name="dropdown-fade">
      <div v-if="isOpen" class="user-profile-dropdown" role="menu">
        <!-- 个人资料 -->
        <button 
          type="button" 
          class="dropdown-item"
          @click="navigateTo('/dashboard/profile')"
        >
          <User :size="16" />
          <span>个人资料</span>
        </button>

        <!-- 账号安全 -->
        <button 
          type="button" 
          class="dropdown-item"
          @click="navigateTo('/dashboard/account-security')"
        >
          <Shield :size="16" />
          <span>账号安全</span>
        </button>

        <!-- 系统设置 -->
        <button 
          type="button" 
          class="dropdown-item"
          @click="navigateTo('/dashboard/settings')"
        >
          <Settings :size="16" />
          <span>系统设置</span>
        </button>

        <!-- 分割线 -->
        <div class="dropdown-divider"></div>

        <!-- 退出登录 -->
        <button 
          type="button" 
          class="dropdown-item dropdown-item-danger"
          @click="handleLogout"
        >
          <LogOut :size="16" />
          <span>退出登录</span>
        </button>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
/* ── 用户菜单容器 ── */
.user-profile-menu {
  position: relative;
  display: inline-block;
}

/* ── 触发器按钮 ── */
.user-profile-trigger {
  display: flex;
  align-items: center;
  gap: var(--sp-8, 8px);
  padding: var(--sp-8, 8px) var(--sp-12, 12px);
  border: 0;
  border-radius: var(--r-lg, 12px);
  background: transparent;
  color: var(--text-main, #2C3B34);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition:
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    transform 0.12s cubic-bezier(0.32, 0.72, 0, 1);
}

.user-profile-trigger:hover {
  background: var(--bg-hover, rgba(95, 122, 107, 0.08));
}

.user-profile-trigger:active {
  transform: scale(0.97);
}

.user-profile-trigger.is-active {
  background: var(--bg-hover, rgba(95, 122, 107, 0.08));
}

/* ── 圆形头像 ── */
.user-avatar {
  width: 32px;
  height: 32px;
  border-radius: var(--r-pill, 9999px);
  background: var(--primary, #5F7A6B);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: var(--shadow-card, 0 2px 8px rgba(44, 59, 52, 0.04));
}

.user-initial {
  color: var(--primary-on, #FFFFFF);
  font-size: 14px;
  font-weight: 650;
  line-height: 1;
  user-select: none;
}

/* ── 用户名 ── */
.user-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── 箭头图标 ── */
.chevron-icon {
  flex-shrink: 0;
  transition: transform 0.2s cubic-bezier(0.32, 0.72, 0, 1);
  color: var(--text-muted, #6B7A73);
}

.chevron-icon.is-rotated {
  transform: rotate(180deg);
}

/* ── 下拉菜单 ── */
.user-profile-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: var(--z-dropdown, 10);
  min-width: 200px;
  padding: var(--sp-8, 8px);
  border-radius: var(--r-lg, 12px);
  background: var(--bg-card, #FFFFFF);
  border: 1px solid var(--border-color, #D1DCD5);
  box-shadow: var(--shadow-hover, 0 4px 16px rgba(44, 59, 52, 0.08));
  overflow: hidden;
}

/* ── 下拉菜单项 ── */
.dropdown-item {
  display: flex;
  align-items: center;
  gap: var(--sp-12, 12px);
  width: 100%;
  padding: var(--sp-12, 12px);
  border: 0;
  border-radius: var(--r-md, 8px);
  background: transparent;
  color: var(--text-main, #2C3B34);
  font-size: 13px;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  transition:
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    color 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.dropdown-item:hover {
  background: var(--bg-hover, rgba(95, 122, 107, 0.08));
  color: var(--primary, #5F7A6B);
}

.dropdown-item svg {
  flex-shrink: 0;
  opacity: 0.7;
  transition: opacity 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.dropdown-item:hover svg {
  opacity: 1;
}

/* ── 危险项（退出登录） ── */
.dropdown-item-danger {
  color: var(--alert-high, #C48B8B);
}

.dropdown-item-danger:hover {
  background: var(--alert-high-bg, rgba(196, 139, 139, 0.12));
  color: var(--alert-high, #C48B8B);
}

/* ── 分割线 ── */
.dropdown-divider {
  height: 1px;
  margin: var(--sp-8, 8px) 0;
  background: var(--border-color, #D1DCD5);
}

/* ── 下拉淡入动画 ── */
.dropdown-fade-enter-active,
.dropdown-fade-leave-active {
  transition:
    opacity 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    transform 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.dropdown-fade-enter-from {
  opacity: 0;
  transform: translateY(-10px);
}

.dropdown-fade-leave-to {
  opacity: 0;
  transform: translateY(-5px);
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .user-name {
    display: none;
  }

  .user-profile-dropdown {
    right: -8px;
  }
}
</style>
