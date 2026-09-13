<script setup>
import { ref, onMounted, computed } from 'vue'
import { getRuntimeMode } from './services/dashboardApi'
import AiAssistant from './components/AiAssistant.vue'

const runtimeMode = ref('unknown')
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
  <router-view />
  <!-- AI 智能助手：右下角悬浮 -->
  <AiAssistant />
  <!-- 运行模式指示器：固定左下角，提示当前数据来源 -->
  <div v-if="runtimeMode !== 'unknown'" class="runtime-mode-badge" :class="modeClass" :title="modeLabel">
    <span class="mode-dot"></span>
    <span class="mode-text">{{ modeLabel }}</span>
  </div>
</template>

<style>
.runtime-mode-badge {
  position: fixed;
  bottom: 12px;
  left: 12px;
  z-index: 9999;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 14px;
  font-size: 11px;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  transition: all 0.2s ease;
  cursor: default;
  user-select: none;
}
.runtime-mode-badge:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}
.mode-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  animation: pulse 2s infinite;
}
.mode-backend {
  background: rgba(34, 139, 94, 0.1);
  color: #228b5e;
  border: 1px solid rgba(34, 139, 94, 0.3);
}
.mode-backend .mode-dot { background: #228b5e; }
.mode-mock {
  background: rgba(224, 160, 80, 0.12);
  color: #c07d2a;
  border: 1px solid rgba(224, 160, 80, 0.4);
}
.mode-mock .mode-dot { background: #e0a050; }
.mode-unknown {
  background: rgba(128, 128, 128, 0.1);
  color: #808080;
  border: 1px solid rgba(128, 128, 128, 0.3);
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
</style>
