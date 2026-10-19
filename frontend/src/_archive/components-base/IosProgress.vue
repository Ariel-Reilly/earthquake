<script setup>
/**
 * IosProgress  ── iOS 18 进度条
 *
 * 用法:
 *   <IosProgress :value="62" />
 *   <IosProgress :value="90" tone="orange" />
 *   <IosProgress :value="42" indeterminate />  // 不确定进度
 */
import { computed } from 'vue'

const props = defineProps({
  value: { type: Number, default: 0 },
  max: { type: Number, default: 100 },
  tone: { type: String, default: 'blue' }, // blue | green | orange | red | purple
  indeterminate: { type: Boolean, default: false },
  size: { type: String, default: 'md' },   // sm | md | lg
  showLabel: { type: Boolean, default: false },
})

const pct = computed(() => {
  if (props.indeterminate) return 100
  const v = Math.max(0, Math.min(props.value, props.max))
  return (v / props.max) * 100
})
</script>

<template>
  <div :class="['ios-progress-wrap', size === 'sm' ? 'is-sm' : size === 'lg' ? 'is-lg' : '']">
    <div :class="['ios-progress', `tone-${tone}`, { indeterminate }]">
      <i :style="indeterminate ? {} : { width: `${pct}%` }" />
    </div>
    <span v-if="showLabel && !indeterminate" class="ios-progress-label">{{ Math.round(pct) }}%</span>
  </div>
</template>

<style scoped>
.ios-progress-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.ios-progress { flex: 1; }
.ios-progress.is-sm { height: 3px; }
.ios-progress.is-lg { height: 6px; }
.ios-progress.indeterminate i {
  width: 35% !important;
  animation: ios-progress-indet 1.4s cubic-bezier(.4, 0, .2, 1) infinite;
}
@keyframes ios-progress-indet {
  0%   { transform: translateX(-100%); }
  100% { transform: translateX(285%); }
}
.ios-progress-label {
  color: var(--ios-label-3);
  font-size: 12px;
  font-family: var(--font-mono, monospace);
  min-width: 36px;
  text-align: right;
}
</style>
