<script setup>
/**
 * 数据管理：整合数据源接入、同步监控、数据资产目录三个功能
 * 采用顶部 Tab 切换方式，每个 Tab 内嵌对应页面的完整组件
 * 原页面文件保持不变，路由继续保留
 */
import { ref } from 'vue'
import { Database, Radar, FolderOpen } from 'lucide-vue-next'
import DataSourcesPage from './DataSourcesPage.vue'
import DataSyncPage from './DataSyncPage.vue'
import DataCatalogPage from './DataCatalogPage.vue'

const emit = defineEmits(['notify'])

const activeTab = ref('sources') // sources | sync | catalog

const tabs = [
  { key: 'sources', label: '数据源接入', icon: Database },
  { key: 'sync', label: '同步监控', icon: Radar },
  { key: 'catalog', label: '资产目录', icon: FolderOpen },
]

function onNotify(msg) {
  emit('notify', msg)
}
</script>

<template>
  <div class="data-manage-page">
    <!-- 页面头部 -->
    <header class="dm-header">
      <div class="dm-title">
        <h2>数据管理</h2>
        <p>管理数据源接入、同步监控和数据资产目录</p>
      </div>
    </header>

    <!-- Tab 切换栏 -->
    <div class="dm-tab-bar">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="dm-tab-btn"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        <component :is="tab.icon" :size="16" />
        {{ tab.label }}
      </button>
    </div>

    <!-- 内容区 -->
    <div class="dm-content">
      <Transition name="fade-slide" mode="out-in">
        <div v-if="activeTab === 'sources'" key="sources" class="dm-tab-panel">
          <DataSourcesPage @notify="onNotify" :user-role="'admin'" />
        </div>
        <div v-else-if="activeTab === 'sync'" key="sync" class="dm-tab-panel">
          <DataSyncPage @notify="onNotify" :user-role="'admin'" />
        </div>
        <div v-else-if="activeTab === 'catalog'" key="catalog" class="dm-tab-panel">
          <DataCatalogPage @notify="onNotify" :user-role="'admin'" />
        </div>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.data-manage-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
}

/* 页面头部 */
.dm-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px;
}
.dm-title h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--c-scrim);
}
.dm-title p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--c-text-2);
}

/* Tab 切换栏 */
.dm-tab-bar {
  display: flex;
  gap: 4px;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  padding: 4px;
  width: fit-content;
}
.dm-tab-btn {
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
.dm-tab-btn:hover {
  background: var(--c-surface-2);
  color: var(--c-scrim);
}
.dm-tab-btn.active {
  background: var(--c-scrim);
  color: var(--c-on-brand);
}

/* 内容区 */
.dm-content {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.dm-tab-panel {
  height: 100%;
}

/* 过渡动画 */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(4px);
}
.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 720px) {
  .dm-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .dm-tab-bar {
    width: 100%;
    overflow-x: auto;
    flex-wrap: nowrap;
  }
  .dm-tab-btn {
    flex: 1;
    justify-content: center;
    padding: 9px 12px;
    font-size: 12px;
    white-space: nowrap;
  }
}
</style>
