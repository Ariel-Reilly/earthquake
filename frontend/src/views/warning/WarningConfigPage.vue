<script setup>
/**
 * 预警配置页（/dashboard/warning-config）。
 *
 * 采用顶部 Tab 切换方式整合三个预警相关配置：
 *   - 通知模板（NoticeTemplatePage）
 *   - 应急预案（PlanManagePage）
 *   - 通知渠道（NotificationChannelsPage）
 *
 * 原页面文件保持不变，路由继续保留。
 */
import { ref } from 'vue'
import { BellRing, ScrollText, Radio } from 'lucide-vue-next'
import NoticeTemplatePage from '../system/NoticeTemplatePage.vue'
import PlanManagePage from '../system/PlanManagePage.vue'
import NotificationChannelsPage from '../system/NotificationChannelsPage.vue'

const emit = defineEmits(['notify'])

const activeTab = ref('templates') // templates | plans | channels

const tabs = [
  { key: 'templates', label: '通知模板', icon: BellRing },
  { key: 'plans', label: '应急预案', icon: ScrollText },
  { key: 'channels', label: '通知渠道', icon: Radio },
]

function onNotify(msg) {
  emit('notify', msg)
}
</script>

<template>
  <div class="warning-config-page">
    <!-- 页面头部 -->
    <header class="wc-header">
      <div class="wc-title">
        <h2>预警配置</h2>
        <p>管理通知模板、应急预案和通知渠道</p>
      </div>
    </header>

    <!-- Tab 切换栏 -->
    <div class="wc-tab-bar">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="wc-tab-btn"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        <component :is="tab.icon" :size="16" />
        {{ tab.label }}
      </button>
    </div>

    <!-- 内容区 -->
    <div class="wc-content">
      <Transition name="fade" mode="out-in">
        <div v-if="activeTab === 'templates'" key="templates" class="wc-tab-panel">
          <NoticeTemplatePage @notify="onNotify" :user-role="'admin'" />
        </div>
        <div v-else-if="activeTab === 'plans'" key="plans" class="wc-tab-panel">
          <PlanManagePage @notify="onNotify" :user-role="'admin'" />
        </div>
        <div v-else-if="activeTab === 'channels'" key="channels" class="wc-tab-panel">
          <NotificationChannelsPage />
        </div>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.warning-config-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
}

/* 页面头部 */
.wc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.wc-title h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--c-scrim);
}
.wc-title p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--c-text-2);
}

/* Tab 切换栏 */
.wc-tab-bar {
  display: flex;
  gap: 4px;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  padding: 4px;
  width: fit-content;
}
.wc-tab-btn {
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
.wc-tab-btn:hover {
  background: var(--c-surface-2);
  color: var(--c-scrim);
}
.wc-tab-btn.active {
  background: var(--c-scrim);
  color: var(--c-on-brand);
}

/* 内容区 */
.wc-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.wc-tab-panel {
  height: 100%;
  overflow-y: auto;
}

/* 淡入淡出过渡 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@media (max-width: 720px) {
  .wc-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .wc-tab-bar {
    width: 100%;
    overflow-x: auto;
    flex-wrap: nowrap;
  }
  .wc-tab-btn {
    flex: 1;
    justify-content: center;
    padding: 9px 12px;
    font-size: 12px;
    white-space: nowrap;
  }
}
</style>
