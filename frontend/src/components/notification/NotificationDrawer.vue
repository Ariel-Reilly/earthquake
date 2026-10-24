<script setup>
/**
 * 全局通知抽屉容器
 * 
 * 功能：
 * - 三个 Tab：消息中心、待办中心、审批中心
 * - 右侧滑出（大屏）/ 全屏覆盖（移动端）
 * - 红点聚合显示
 * - Tab 间的未读数合计
 */
import { computed, ref, watch } from 'vue'
import { X, Mail, CheckSquare, FileText } from 'lucide-vue-next'
import MessageCenterTab from './tabs/MessageCenterTab.vue'
import TodoCenterTab from './tabs/TodoCenterTab.vue'
import ApprovalCenterTab from './tabs/ApprovalCenterTab.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  badges: { type: Object, default: () => ({}) },
})

const emit = defineEmits(['close', 'badge-refresh'])

const activeTab = ref('messages')

const TABS = [
  { id: 'messages', label: '消息', icon: Mail, path: '/dashboard/message-center' },
  { id: 'todos', label: '待办', icon: CheckSquare, path: '/dashboard/todo' },
  { id: 'approvals', label: '审批', icon: FileText, path: '/dashboard/approvals' },
]

// 从红点数据中提取每个 Tab 的计数
const tabBadges = computed(() => ({
  messages: parseInt(props.badges['/dashboard/message-center'] || 0),
  todos: (parseInt(props.badges['/dashboard/todo'] || 0) + 
           parseInt(props.badges['/dashboard/approvals'] || 0)),
  approvals: parseInt(props.badges['/dashboard/approvals'] || 0),
}))

const totalBadge = computed(() => 
  Object.values(tabBadges.value).reduce((sum, v) => sum + v, 0)
)

function handleBadgeRefresh() {
  emit('badge-refresh')
}
</script>

<template>
  <Transition name="drawer-overlay">
    <div v-if="visible" class="drawer-overlay" @click.self="$emit('close')" />
  </Transition>

  <Transition name="drawer-slide">
    <div v-if="visible" class="notification-drawer">
      <!-- Header -->
      <div class="drawer-header">
        <div class="header-title">
          <h2>通知中心</h2>
          <span v-if="totalBadge" class="header-badge">{{ totalBadge > 99 ? '99+' : totalBadge }}</span>
        </div>
        <button class="close-btn" @click="$emit('close')" aria-label="关闭">
          <X size="20" />
        </button>
      </div>

      <!-- Tabs -->
      <div class="drawer-tabs">
        <button
          v-for="tab in TABS"
          :key="tab.id"
          class="tab-btn"
          :class="{ active: activeTab === tab.id }"
          @click="activeTab = tab.id"
        >
          <component :is="tab.icon" size="16" />
          <span>{{ tab.label }}</span>
          <span v-if="tabBadges[tab.id]" class="tab-badge">{{ tabBadges[tab.id] }}</span>
        </button>
      </div>

      <!-- Content -->
      <div class="drawer-content">
        <MessageCenterTab
          v-show="activeTab === 'messages'"
          :compact="true"
          @badge-refresh="handleBadgeRefresh"
        />
        <TodoCenterTab
          v-show="activeTab === 'todos'"
          :compact="true"
          @badge-refresh="handleBadgeRefresh"
        />
        <ApprovalCenterTab
          v-show="activeTab === 'approvals'"
          :compact="true"
          @badge-refresh="handleBadgeRefresh"
        />
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 48;
  animation: fade-in 0.2s ease-out;
}

.notification-drawer {
  position: fixed;
  top: 64px;
  right: 0;
  bottom: 0;
  width: 420px;
  background: var(--c-surface, #fff);
  border-left: 1px solid var(--c-separator, #e0e0e0);
  z-index: 50;
  display: flex;
  flex-direction: column;
  box-shadow: -4px 0 12px rgba(0, 0, 0, 0.08);
  animation: slide-in-right 0.3s cubic-bezier(0.32, 0.72, 0, 1);
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  border-bottom: 1px solid var(--c-separator);
  flex-shrink: 0;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-title h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
}

.header-badge {
  padding: 2px 6px;
  border-radius: 10px;
  background: var(--c-danger-bg);
  color: var(--c-danger-strong);
  font-size: 10px;
  font-weight: 700;
}

.close-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 0;
  background: transparent;
  cursor: pointer;
  color: var(--c-text-3);
  border-radius: 6px;
  transition: all 0.2s;
}

.close-btn:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.drawer-tabs {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--c-separator);
  background: var(--c-surface-2);
  flex-shrink: 0;
  overflow-x: auto;
}

.tab-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--c-text-3);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
  flex-shrink: 0;
}

.tab-btn:hover {
  background: var(--c-surface);
  color: var(--c-text);
}

.tab-btn.active {
  border-color: var(--c-brand);
  background: var(--c-brand-light);
  color: var(--c-brand);
}

.tab-badge {
  padding: 2px 4px;
  border-radius: 3px;
  background: var(--c-danger-bg);
  color: var(--c-danger-strong);
  font-size: 10px;
  font-weight: 700;
  min-width: 16px;
  text-align: center;
}

.drawer-content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

/* 动画 */
.drawer-overlay {
  animation: fade-in 0.2s ease-out;
}

.notification-drawer {
  animation: slide-in-right 0.3s cubic-bezier(0.32, 0.72, 0, 1);
}

.drawer-overlay-enter-from {
  opacity: 0;
}

.drawer-overlay-leave-to {
  opacity: 0;
}

.drawer-overlay-enter-active,
.drawer-overlay-leave-active {
  transition: opacity 0.2s ease-out;
}

.drawer-slide-enter-from {
  transform: translateX(100%);
  opacity: 0;
}

.drawer-slide-leave-to {
  transform: translateX(100%);
  opacity: 0;
}

.drawer-slide-enter-active,
.drawer-slide-leave-active {
  transition: transform 0.3s cubic-bezier(0.32, 0.72, 0, 1), opacity 0.3s;
}

@keyframes fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes slide-in-right {
  from {
    transform: translateX(100%);
    opacity: 0;
  }
  to {
    transform: translateX(0);
    opacity: 1;
  }
}

/* 响应式：移动端全屏 */
@media (max-width: 768px) {
  .notification-drawer {
    width: 100%;
    left: 0;
    right: auto;
    border-left: 0;
    border-top: 1px solid var(--c-separator);
    top: auto;
    bottom: 0;
    max-height: 80vh;
    border-radius: 16px 16px 0 0;
  }

  .drawer-slide-enter-from {
    transform: translateY(100%);
  }

  .drawer-slide-leave-to {
    transform: translateY(100%);
  }
}
</style>
