<script setup>
import { computed } from 'vue'
import { AlertCircle, LoaderCircle, CheckCircle2 } from 'lucide-vue-next'

const props = defineProps({
  message: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  success: { type: String, default: '' },
})

const stateIcon = computed(() => ({
  loading: LoaderCircle,
  error: AlertCircle,
  success: CheckCircle2,
}))
const activeIcon = computed(() => props.loading ? stateIcon.value.loading : props.error ? stateIcon.value.error : stateIcon.value.success)
const activeText = computed(() => props.error || props.success || props.message)
</script>

<template>
  <transition name="feedback">
    <div
      v-if="message || error || success || loading"
      class="feedback-bar"
      :class="{ 'is-loading': loading, 'is-error': !!error, 'is-success': !!success }"
      role="status"
      aria-live="polite"
    >
      <component :is="activeIcon" :size="17" :class="{ spinning: loading }" />
      <span>{{ activeText }}</span>
    </div>
  </transition>
</template>

<style scoped>
.feedback-bar {
  position: fixed;
  z-index: 35;
  left: 50%;
  bottom: 24px;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 260px;
  max-width: min(560px, calc(100vw - 24px));
  padding: 12px 18px;
  border-radius: 999px;
  background: var(--c-text, #17333b);
  box-shadow: 0 10px 32px rgba(23, 51, 59, 0.18);
  transform: translateX(-50%);
  font-size: 13px;
  color: var(--c-text-inv, #fff);
  pointer-events: none;
}

.feedback-bar.is-loading {
  background: var(--c-text, #17333b);
}

.feedback-bar.is-error {
  background: var(--c-danger, #c0392b);
}

.feedback-bar.is-success {
  background: var(--c-success, #2a8154);
}

.feedback-enter-active, .feedback-leave-active {
  transition: opacity 0.3s ease, transform 0.3s cubic-bezier(0.3, 1.4, 0.4, 1);
}
.feedback-enter-from, .feedback-leave-to {
  opacity: 0;
  transform: translate(-50%, 16px);
}
</style>
