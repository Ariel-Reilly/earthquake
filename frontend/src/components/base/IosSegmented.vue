<script setup>
/**
 * IosSegmented  ── iOS 18 分段控件
 *
 * 用法:
 *   <IosSegmented v-model="active" :options="[
 *     { label: '今日', value: 'today' },
 *     { label: '本周', value: 'week' },
 *     { label: '本月', value: 'month' },
 *   ]" />
 */
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: [String, Number, null], default: null },
  options: { type: Array, required: true }, // [{ label, value, icon? }]
  size: { type: String, default: 'md' },    // sm | md
})

const emit = defineEmits(['update:modelValue', 'change'])

const current = computed(() => props.modelValue)

function pick(v) {
  emit('update:modelValue', v)
  emit('change', v)
}
</script>

<template>
  <div :class="['ios-segmented', size === 'sm' ? 'ios-segmented-sm' : '']" role="tablist">
    <button
      v-for="opt in options"
      :key="opt.value"
      type="button"
      role="tab"
      :aria-selected="current === opt.value"
      :class="{ active: current === opt.value, 'with-icon': opt.icon }"
      @click="pick(opt.value)"
    >
      <component :is="opt.icon" v-if="opt.icon" :size="13" />
      <span>{{ opt.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.ios-segmented { background: var(--c-border); }
.ios-segmented-sm { font-size: 12px; }
.ios-segmented-sm button { padding: 5px 11px; }
.ios-segmented button.with-icon { gap: 5px; }
</style>
