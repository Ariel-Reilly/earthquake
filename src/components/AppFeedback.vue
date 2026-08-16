<script setup>
import { computed } from 'vue'
import { AlertCircle, LoaderCircle, CheckCircle2 } from 'lucide-vue-next'

defineProps({
  message: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  success: { type: String, default: '' },
})

const stateIcon = computed(() => ({ loading: LoaderCircle, error: AlertCircle, success: CheckCircle2 }))
</script>

<template>
  <transition name="toast">
    <div v-if="message || error || success || loading" class="feedback-bar" :class="{ loading, error: !!error, success: !!success }">
      <component :is="loading ? stateIcon.loading : error ? stateIcon.error : stateIcon.success" :size="16" :class="{ spinning: loading }" />
      <span>{{ error || success || message }}</span>
    </div>
  </transition>
</template>
