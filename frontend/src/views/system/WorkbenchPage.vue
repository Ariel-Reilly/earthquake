<script setup>
/**
 * 我的工作台：整合待办事项、我的审批、系统消息三个功能
 * 采用顶部 Tab 切换方式，每个 Tab 内嵌对应页面的完整组件
 * 原页面文件保持不变，路由继续保留
 */
import { ref } from 'vue'
import { ListChecks, ShieldCheck, Inbox } from 'lucide-vue-next'
import TodoCenterPage from './TodoCenterPage.vue'
import ApprovalCenterPage from './ApprovalCenterPage.vue'
import MessageCenterPage from './MessageCenterPage.vue'

const emit = defineEmits(['notify'])

const activeTab = ref('todo') // todo | approval | message

const tabs = [
  { key: 'todo', label: '待办事项', icon: ListChecks },
  { key: 'approval', label: '我的审批', icon: ShieldCheck },
  { key: 'message', label: '系统消息', icon: Inbox },
]

function onNotify(msg) {
  emit('notify', msg)
}
</script>

<template>
  <div class="workbench-page">
    <!-- 页面头部 -->
    <header class="workbench-header">
      <div class="workbench-title">
        <h2>我的工作台</h2>
        <p>待办事项、审批和消息一站式处理</p>
      </div>
    </header>

    <!-- Tab 切换 -->
    <div class="workbench-tab-bar">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="workbench-tab-btn"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        <component :is="tab.icon" :size="16" />
        {{ tab.label }}
      </button>
    </div>

    <!-- 内容区 -->
    <div class="workbench-content">
      <!-- 待办事项 Tab -->
      <div v-if="activeTab === 'todo'" class="tab-panel">
        <TodoCenterPage @notify="onNotify" />
      </div>

      <!-- 我的审批 Tab -->
      <div v-else-if="activeTab === 'approval'" class="tab-panel">
        <ApprovalCenterPage @notify="onNotify" />
      </div>

      <!-- 系统消息 Tab -->
      <div v-else-if="activeTab === 'message'" class="tab-panel">
        <MessageCenterPage @notify="onNotify" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.workbench-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 页面头部 */
.workbench-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.workbench-title h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--c-scrim);
}
.workbench-title p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--c-text-2);
}

/* Tab 切换栏 */
.workbench-tab-bar {
  display: flex;
  gap: 4px;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  padding: 4px;
  width: fit-content;
}
.workbench-tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 20px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-brand-deep);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.workbench-tab-btn:hover {
  background: var(--c-surface-2);
  color: var(--c-scrim);
}
.workbench-tab-btn.active {
  background: var(--c-scrim);
  color: var(--c-on-brand);
}

/* 内容区 */
.workbench-content {
  min-height: 0;
}
.tab-panel {
  animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 720px) {
  .workbench-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .workbench-tab-bar {
    width: 100%;
    overflow-x: auto;
    flex-wrap: nowrap;
  }
  .workbench-tab-btn {
    flex: 1;
    justify-content: center;
    padding: 9px 12px;
    font-size: 12px;
    white-space: nowrap;
  }
}
</style>
