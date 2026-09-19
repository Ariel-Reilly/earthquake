<script setup>
/**
 * 服务健康告警横幅 (ServiceHealthBanner.vue)。
 *
 * 用途：当 AI 微服务降级（degraded）或完全不可用（down）时，
 *       在 DashboardLayout 顶部显示常驻横幅，告知用户当前为"人工巡检模式"。
 *
 * 三态显示：
 *   - ok（正常）：完全隐藏
 *   - degraded（轻微降级）：橙色提示"AI 推理延迟增加，自动重试中"
 *   - down（不可用）：红色强提示"AI 服务不可用，当前为人工巡检模式" + "重新检查"按钮
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import { AlertTriangle, AlertOctagon, CheckCircle2, RefreshCw, X } from 'lucide-vue-next'
import { useRealtimeStore } from '../stores/realtime.js'
import { checkAiHealthNow } from '../services/_architecture/core/healthService.js'

const store = useRealtimeStore()
const checking = ref(false)
const dismissed = ref(false)
let autoRestoreTimer = null

const health = computed(() => store.aiHealth ?? { state: 'ok' })
const visible = computed(() => !dismissed.value && health.value.state !== 'ok')
const severity = computed(() =>
  health.value.state === 'down' ? 'critical' : 'warning'
)
const titleText = computed(() =>
  health.value.state === 'down'
    ? 'AI 服务不可用 · 当前为人工巡检模式'
    : 'AI 服务轻微降级 · 部分 AI 推理延迟增加'
)
const detailText = computed(() => {
  const last = health.value.lastProbeAt
  const formatted = last ? new Date(last).toLocaleTimeString('zh-CN', { hour12: false }) : '—'
  const fails = health.value.consecutiveFailures || 0
  const latency = health.value.lastAiLatencyMs
  const latencyText = latency != null ? ` · 延迟 ${latency}ms` : ''
  return `连续失败 ${fails} 次 · 最近探活 ${formatted}${latencyText}`
})

async function handleRefresh() {
  checking.value = true
  try {
    await checkAiHealthNow()
    // 若恢复成功，3s 后自动收起横幅
    if (store.aiHealth?.state === 'ok') {
      dismissed.value = false
      window.clearTimeout(autoRestoreTimer)
      autoRestoreTimer = window.setTimeout(() => {
        dismissed.value = true
      }, 3000)
    }
  } finally {
    checking.value = false
  }
}

function handleDismiss() {
  dismissed.value = true
}

onBeforeUnmount(() => {
  window.clearTimeout(autoRestoreTimer)
})
</script>

<template>
  <transition name="health-slide">
    <div
      v-if="visible"
      class="health-banner"
      :class="[`sev-${severity}`]"
      role="status"
      aria-live="polite"
    >
      <div class="health-icon">
        <AlertOctagon v-if="severity === 'critical'" :size="18" />
        <AlertTriangle v-else :size="18" />
      </div>
      <div class="health-content">
        <strong>{{ titleText }}</strong>
        <small>{{ detailText }}</small>
      </div>
      <div class="health-actions">
        <button
          class="health-btn"
          :disabled="checking"
          @click="handleRefresh"
        >
          <RefreshCw :size="14" :class="{ spinning: checking }" />
          重新检查
        </button>
        <button
          class="health-btn icon-only"
          title="本会话内不再提示"
          @click="handleDismiss"
        >
          <X :size="14" />
        </button>
      </div>
    </div>
  </transition>
</template>

<style scoped>
.health-banner {
  display: flex;
  align-items: center;
  gap: var(--sp-3, 12px);
  padding: 10px 16px;
  font-size: 13px;
  color: #fff;
  width: 100%;
}
.health-banner.sev-warning {
  background: linear-gradient(135deg, #d97706, #ea580c);
  border-bottom: 1px solid #c2410c;
}
.health-banner.sev-critical {
  background: linear-gradient(135deg, #dc2626, #b91c1c);
  border-bottom: 1px solid #991b1b;
  animation: pulse-bg 2.4s ease-in-out infinite;
}
@keyframes pulse-bg {
  0%, 100% { box-shadow: inset 0 -2px 0 rgba(0,0,0,0.1); }
  50% { box-shadow: inset 0 -2px 0 rgba(255,255,255,0.18); }
}
.health-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.health-content {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.health-content strong {
  font-weight: 600;
  white-space: nowrap;
}
.health-content small {
  font-size: 12px;
  opacity: 0.85;
  white-space: nowrap;
}
.health-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.health-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid rgba(255,255,255,0.4);
  background: rgba(255,255,255,0.15);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, opacity 0.15s ease;
}
.health-btn:hover:not(:disabled) {
  background: rgba(255,255,255,0.25);
  border-color: rgba(255,255,255,0.55);
}
.health-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.health-btn.icon-only {
  padding: 4px 7px;
}
.spinning {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 过渡动画 */
.health-slide-enter-active, .health-slide-leave-active {
  transition: transform 0.3s cubic-bezier(0.3, 1.2, 0.4, 1),
              opacity 0.3s ease,
              max-height 0.3s ease;
  overflow: hidden;
}
.health-slide-enter-from, .health-slide-leave-to {
  transform: translateY(-100%);
  opacity: 0;
  max-height: 0;
}
.health-slide-enter-to, .health-slide-leave-from {
  max-height: 80px;
}
</style>
