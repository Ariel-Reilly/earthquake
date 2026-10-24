<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { getRuntimeMode } from './services/dashboardApi'
import AiAssistant from './components/AiAssistant.vue'
import ErrorBoundary from './components/common/ErrorBoundary.vue'

const route = useRoute()
const runtimeMode = ref('unknown')

// 根据路由 meta 控制 AI 助手显隐
const showAiAssistant = computed(() => route.meta?.showAiAssistant === true)

const modeLabel = computed(() => {
  switch (runtimeMode.value) {
    case 'backend': return '后端连接中'
    case 'mock': return '演示模式'
    case 'unknown': return '检测中...'
    default: return runtimeMode.value
  }
})
const modeClass = computed(() => {
  if (runtimeMode.value === 'backend') return 'mode-backend'
  if (runtimeMode.value === 'mock') return 'mode-mock'
  return 'mode-unknown'
})

onMounted(async () => {
  runtimeMode.value = await getRuntimeMode()
})
</script>

<template>
  <div class="app-root">
    <ErrorBoundary>
      <router-view />
    </ErrorBoundary>
    <!-- AI 智能助手：仅在详情页显示（通过路由 meta 控制） -->
    <AiAssistant v-if="showAiAssistant" />
    <!-- 运行模式指示器：固定左下角，提示当前数据来源 -->
    <div v-if="runtimeMode !== 'unknown'" class="runtime-mode-badge" :class="modeClass" :title="modeLabel">
      <span class="mode-dot"></span>
      <span class="mode-text">{{ modeLabel }}</span>
    </div>
  </div>
</template>

<style>
.app-root {
  width: 100%;
  height: 100%;
}

.runtime-mode-badge {
  position: fixed;
  bottom: 14px;
  left: 14px;
  z-index: 9999;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: var(--r-pill);
  font-size: 11px;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "PingFang SC", sans-serif;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  transition: transform .15s ease, box-shadow .15s ease;
  cursor: default;
  user-select: none;
  -webkit-backdrop-filter: blur(14px) saturate(180%);
  backdrop-filter: blur(14px) saturate(180%);
}
.runtime-mode-badge:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.18);
}
.mode-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  animation: pulse 2s infinite;
}
.mode-backend {
  background: rgba(111, 158, 127, 0.14);
  color: #1e7a4e;
  border: 1px solid rgba(111, 158, 127, 0.28);
}
.mode-backend .mode-dot { background: var(--c-brand); }
.mode-mock {
  background: rgba(255, 149, 0, 0.16);
  color: #b35a00;
  border: 1px solid rgba(255, 149, 0, 0.36);
}
.mode-mock .mode-dot { background: var(--c-warning); }
.mode-unknown {
  background: rgba(142, 142, 147, 0.16);
  color: #6c6c70;
  border: 1px solid rgba(142, 142, 147, 0.30);
}
.mode-unknown .mode-dot { background: var(--sys-gray); }
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
</style>
