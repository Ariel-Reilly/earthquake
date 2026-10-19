<script setup>
/**
 * IosToggle  ── iOS 18 开关
 *
 * 用法:
 *   <IosToggle v-model="enablePush" label="推送通知" />
 *   <IosToggle v-model="dark" :on-color="'#54806a'" />  // 自定义开启色
 */
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  label: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  onColor: { type: String, default: 'var(--ios-green)' },
  size: { type: String, default: 'md' }, // sm | md
})

const emit = defineEmits(['update:modelValue', 'change'])

const on = computed(() => props.modelValue)
const styleVar = computed(() => on.value ? { '--switch-on-bg': props.onColor } : {})

function toggle() {
  if (props.disabled) return
  emit('update:modelValue', !props.modelValue)
  emit('change', !props.modelValue)
}
</script>

<template>
  <label :class="['ios-toggle-wrap', { 'is-disabled': disabled, 'is-sm': size === 'sm' }]">
    <span v-if="label || $slots.default" class="ios-toggle-label">
      <slot>{{ label }}</slot>
    </span>
    <span class="ios-switch" :class="{ on }" :style="styleVar" @click.prevent="toggle">
      <i></i>
    </span>
    <input
      type="checkbox"
      :checked="on"
      :disabled="disabled"
      class="ios-toggle-input"
      @change="toggle"
    />
  </label>
</template>

<style scoped>
.ios-toggle-wrap {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  user-select: none;
}
.ios-toggle-wrap.is-disabled { opacity: 0.4; cursor: not-allowed; }
.ios-toggle-label { color: var(--ios-label); font-size: 14px; }
.ios-toggle-input {
  position: absolute;
  width: 1px; height: 1px;
  opacity: 0; pointer-events: none;
}
.ios-switch.on i { background: var(--switch-on-bg, var(--ios-green)); }
.ios-toggle-wrap.is-sm .ios-switch { width: 42px; height: 25px; }
.ios-toggle-wrap.is-sm .ios-switch i::after {
  top: 2px; left: 2px; width: 21px; height: 21px;
}
.ios-toggle-wrap.is-sm .ios-switch.on i::after { transform: translateX(17px); }
</style>
