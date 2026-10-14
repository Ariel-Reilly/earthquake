<script setup>
/**
 * AppFeedback  ── iOS 18 风格反馈胶囊
 *
 * 显示位置: 顶部中央 (仿 iOS Banner 通知)
 * 风格: Liquid Glass 毛玻璃 + 胶囊外形
 * 状态: loading | success | error | info
 */
import { computed } from 'vue'
import { AlertCircle, LoaderCircle, CheckCircle2, Info } from 'lucide-vue-next'

const props = defineProps({
  message: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  success: { type: String, default: '' },
  position: { type: String, default: 'top' }, // top | bottom
})

const stateIcon = computed(() => ({
  loading: LoaderCircle,
  error: AlertCircle,
  success: CheckCircle2,
  info: Info,
}))
const activeIcon = computed(() => {
  if (props.loading) return stateIcon.value.loading
  if (props.error) return stateIcon.value.error
  if (props.success) return stateIcon.value.success
  return stateIcon.value.info
})
const activeText = computed(() => props.error || props.success || props.message)
const tone = computed(() => {
  if (props.error) return 'error'
  if (props.success) return 'success'
  if (props.loading) return 'loading'
  return 'info'
})
const visible = computed(() => Boolean(props.message || props.error || props.success || props.loading))
</script>

<template>
  <Transition :name="position === 'top' ? 'feedback-top' : 'feedback-bottom'" appear>
    <div
      v-if="visible"
      :class="['feedback-bar', `tone-${tone}`, position === 'top' ? 'is-top' : 'is-bottom']"
      role="status"
      aria-live="polite"
    >
      <component :is="activeIcon" :size="16" :class="{ spinning: loading }" />
      <span>{{ activeText }}</span>
    </div>
  </Transition>
</template>

<style scoped>
.feedback-bar {
  position: fixed;
  z-index: 40;
  left: 50%;
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 220px;
  max-width: min(540px, calc(100vw - 32px));
  padding: 11px 18px;
  border-radius: var(--r-pill);
  font-size: 13px;
  font-weight: 500;
  pointer-events: none;
  background: var(--glass-thick);
  -webkit-backdrop-filter: blur(40px) saturate(200%);
  backdrop-filter: blur(40px) saturate(200%);
  border: .5px solid var(--c-separator);
  box-shadow: 0 1px 0 rgba(255, 255, 255, .6) inset,
              0 8px 24px rgba(0, 0, 0, .12),
              0 0 0 .5px rgba(0, 0, 0, .04);
  color: var(--c-text);
  transform: translateX(-50%);
}
.feedback-bar.is-top { top: 14px; }
.feedback-bar.is-bottom { bottom: 24px; }

.tone-error   { color: var(--c-on-brand); background: linear-gradient(135deg, var(--c-danger-strong), #ff6b30); border-color: transparent; } /* #ff6b30：错误态暖橙高光（有意保留的字面量） */
.tone-success { color: var(--c-on-brand); background: linear-gradient(135deg, var(--c-brand), var(--c-brand)); border-color: transparent; }
.tone-loading { color: var(--c-on-brand); background: linear-gradient(135deg, var(--c-text), var(--c-ink-1)); border-color: transparent; }
.tone-info    { color: var(--c-text); }

.spinning { animation: feedback-spin .8s linear infinite; }
@keyframes feedback-spin { to { transform: rotate(360deg); } }

.feedback-top-enter-active,
.feedback-top-leave-active {
  transition: opacity .26s ease, transform .34s cubic-bezier(.32, .72, 0, 1);
}
.feedback-top-enter-from,
.feedback-top-leave-to { opacity: 0; transform: translate(-50%, -16px); }

.feedback-bottom-enter-active,
.feedback-bottom-leave-active {
  transition: opacity .26s ease, transform .34s cubic-bezier(.32, .72, 0, 1);
}
.feedback-bottom-enter-from,
.feedback-bottom-leave-to { opacity: 0; transform: translate(-50%, 16px); }

</style>
