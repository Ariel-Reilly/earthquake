<script setup>
/**
 * ServiceHealthBanner  ── AI 服务健康告警横幅 (iOS 18 Liquid Glass)
 *
 * 用途: AI 微服务降级 / 不可用时, 在主内容区顶部显示横幅
 * 风格: iOS 18 染色背景 + Liquid Glass 内容
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import { AlertTriangle, AlertOctagon, RefreshCw, X } from 'lucide-vue-next'
import { useRealtimeStore } from '../stores/realtime.js'
import { checkAiHealthNow } from '../services/_architecture/core/healthService.js'

const store = useRealtimeStore()
const checking = ref(false)
const dismissed = ref(false)
let autoRestoreTimer = null

const health = computed(() => store.aiHealth ?? { state: 'ok' })
const visible = computed(() => !dismissed.value && health.value.state !== 'ok')
const severity = computed(() => (health.value.state === 'down' ? 'critical' : 'warning'))

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
    if (store.aiHealth?.state === 'ok') {
      dismissed.value = false
      window.clearTimeout(autoRestoreTimer)
      autoRestoreTimer = window.setTimeout(() => { dismissed.value = true }, 3000)
    }
  } finally {
    checking.value = false
  }
}

function handleDismiss() {
  dismissed.value = true
}

onBeforeUnmount(() => window.clearTimeout(autoRestoreTimer))
</script>

<template>
  <Transition name="health-slide" appear>
    <div
      v-if="visible"
      :class="['health-banner', `sev-${severity}`]"
      role="status"
      aria-live="polite"
    >
      <div class="banner-tint">
        <component
          :is="severity === 'critical' ? AlertOctagon : AlertTriangle"
          :size="20"
        />
      </div>
      <div class="banner-content">
        <strong>{{ titleText }}</strong>
        <small>{{ detailText }}</small>
      </div>
      <div class="banner-actions">
        <button class="banner-btn" :disabled="checking" @click="handleRefresh">
          <RefreshCw :size="14" :class="{ spinning: checking }" />
          重新检查
        </button>
        <button class="banner-btn icon-only" title="本会话内不再提示" @click="handleDismiss">
          <X :size="14" />
        </button>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.health-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  font-size: 13px;
  color: var(--c-on-brand);
  width: 100%;
  border-bottom: .5px solid rgba(0, 0, 0, .12);
  position: relative;
  overflow: hidden;
}

/* 背景渐变 + 玻璃内容 */
.health-banner.sev-warning {
  background: linear-gradient(135deg, var(--c-warning-strong), #ff7a00);
}
.health-banner.sev-critical {
  background: linear-gradient(135deg, var(--c-danger-strong), #c8261e);
  animation: health-pulse 2.4s ease-in-out infinite;
}
@keyframes health-pulse {
  0%, 100% { box-shadow: inset 0 -1px 0 rgba(0, 0, 0, .15); }
  50% { box-shadow: inset 0 -1px 0 rgba(255, 255, 255, .25); }
}

/* iOS 18 tinted 图标方块 */
.banner-tint {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: var(--r-sm);
  background: rgba(255, 255, 255, .22);
  color: var(--c-on-brand);
  flex-shrink: 0;
}

.banner-content {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.banner-content strong { font-weight: 600; }
.banner-content small { font-size: 12px; opacity: .9; }

.banner-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.banner-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 11px;
  border: 0;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, .22);
  color: var(--c-on-brand);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background .15s ease;
}
.banner-btn:hover:not(:disabled) { background: rgba(255, 255, 255, .32); }
.banner-btn:disabled { opacity: .5; cursor: not-allowed; }
.banner-btn.icon-only { padding: 5px 8px; }
.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.health-slide-enter-active, .health-slide-leave-active {
  transition: transform .36s cubic-bezier(.32, .72, 0, 1),
              opacity .26s ease,
              max-height .32s ease;
  overflow: hidden;
}
.health-slide-enter-from, .health-slide-leave-to {
  transform: translateY(-100%);
  opacity: 0;
  max-height: 0;
}
.health-slide-enter-to, .health-slide-leave-from { max-height: 80px; }
</style>
