<script setup>
/**
 * AI 服务健康指示徽章 (AiHealthBadge.vue)。
 *
 * 在 AI 强依赖页面（ai-tasks/ai-results/ai-insight）顶部紧凑显示。
 *
 * 三态：
 *   - ok:      绿色 ✓ AI 推理正常 (延迟 Xms)
 *   - degraded:橙色 ⚠ AI 服务降级
 *   - down:    红色 ✕ AI 服务不可用 (显示在手动巡检模式)
 */
import { computed } from 'vue'
import { CheckCircle2, AlertTriangle, AlertOctagon } from 'lucide-vue-next'
import { useRealtimeStore } from '../stores/realtime.js'

const store = useRealtimeStore()
const props = defineProps({
  inline: { type: Boolean, default: false },
})

const health = computed(() => store.aiHealth ?? { state: 'ok' })

const meta = computed(() => {
  const s = health.value.state
  if (s === 'down') {
    return {
      icon: AlertOctagon,
      label: 'AI 服务不可用 · 人工巡检模式',
      cls: 'sev-down',
    }
  }
  if (s === 'degraded') {
    return {
      icon: AlertTriangle,
      label: 'AI 服务降级 · 推理可能延迟',
      cls: 'sev-degraded',
    }
  }
  const ms = health.value.lastAiLatencyMs
  return {
    icon: CheckCircle2,
    label: ms != null ? `AI 推理正常 · ${ms}ms` : 'AI 推理正常',
    cls: 'sev-ok',
  }
})
</script>

<template>
  <div class="ai-health-badge" :class="[meta.cls, { inline }]">
    <component :is="meta.icon" :size="14" />
    <span>{{ meta.label }}</span>
  </div>
</template>

<style scoped>
.ai-health-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: var(--r-pill);
  font-size: 12px;
  font-weight: 600;
  border: 1px solid;
  white-space: nowrap;
}

.ai-health-badge.inline {
  font-size: 11px;
  padding: 3px 8px;
}

.sev-ok {
  color: var(--c-success-strong);
  background: rgb(34 197 94 / 10%);
  border-color: rgb(34 197 94 / 30%);
}

.sev-degraded {
  color: #9a3412;
  background: rgb(249 115 22 / 10%);
  border-color: rgb(249 115 22 / 35%);
}

.sev-down {
  color: var(--c-danger-strong);
  background: rgb(239 68 68 / 12%);
  border-color: rgb(239 68 68 / 40%);
  animation: pulse-badge 2.4s ease-in-out infinite;
}

@keyframes pulse-badge {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgb(239 68 68 / 0%);
  }

  50% {
    box-shadow: 0 0 0 4px rgb(239 68 68 / 18%);
  }
}
</style>
