<script setup>
import { ChevronDown } from 'lucide-vue-next'

defineProps({
  open: { type: Boolean, default: false },
  eyebrow: { type: String, default: '' },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  confirmText: { type: String, default: '确认' },
  loading: { type: Boolean, default: false },
})

const emit = defineEmits(['close', 'confirm'])
</script>

<template>
  <Transition name="modal" appear>
    <div v-if="open" class="modal-backdrop" @click.self="emit('close')">
      <div class="modal modal-wide">
        <div class="modal-head">
          <div>
            <span v-if="eyebrow" class="eyebrow">{{ eyebrow }}</span>
            <h2>{{ title }}</h2>
            <p v-if="subtitle" class="modal-subtitle">{{ subtitle }}</p>
          </div>
          <button @click="emit('close')"><ChevronDown :size="18" /></button>
        </div>
        <div class="modal-body">
          <slot />
        </div>
        <div class="modal-foot">
          <button class="secondary-btn" @click="emit('close')">取消</button>
          <button class="primary-btn" :disabled="loading" @click="emit('confirm')">{{ loading ? '处理中...' : confirmText }}</button>
        </div>
      </div>
    </div>
  </Transition>
</template>
