<script setup>
/**
 * IosButton  ── iOS 18 风格按钮
 *
 * 用法:
 *   <IosButton variant="primary" @click="...">确认</IosButton>
 *   <IosButton variant="tinted" icon="<Plus />" size="sm" />
 *   <IosButton variant="destructive" :loading="busy">删除</IosButton>
 *
 * variant: primary | secondary | ghost | destructive | tinted | plain
 * size:    sm | md | lg  (默认 md)
 */
import { computed } from 'vue'
import { Loader2 } from 'lucide-vue-next'

const props = defineProps({
  variant: { type: String, default: 'primary' },
  size: { type: String, default: 'md' },
  icon: { type: [Object, Function, String], default: null },
  iconRight: { type: [Object, Function, String], default: null },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  fullWidth: { type: Boolean, default: false },
  pill: { type: Boolean, default: true },
  type: { type: String, default: 'button' },
  ariaLabel: { type: String, default: null },
})

defineEmits(['click'])

const variantClass = computed(() => {
  // 映射到已有 .ios-btn 变体
  const map = {
    primary: '',            // 默认 = filled blue
    secondary: 'secondary', // tinted blue
    ghost: 'ghost',
    destructive: 'destructive',
    tinted: 'tinted',       // 灰底
    plain: 'ghost',
  }
  return map[props.variant] ?? ''
})

const sizeClass = computed(() => {
  if (props.size === 'sm') return 'sm'
  if (props.size === 'lg') return 'lg'
  return ''
})

const isDisabled = computed(() => props.disabled || props.loading)
</script>

<template>
  <button
    :type="type"
    :class="[
      'ios-btn',
      variantClass,
      sizeClass,
      { 'ios-btn-icon': false, 'full-width': fullWidth, 'ios-btn-pill': pill },
    ]"
    :disabled="isDisabled"
    :aria-label="ariaLabel"
    @click="(e) => $emit('click', e)"
  >
    <Loader2 v-if="loading" :size="16" class="ios-btn-spin" />
    <component :is="icon" v-else-if="icon" :size="size === 'sm' ? 13 : 15" />
    <span v-if="$slots.default" class="ios-btn-label"><slot /></span>
    <component :is="iconRight" v-if="iconRight && !loading" :size="size === 'sm' ? 13 : 15" />
  </button>
</template>

<style scoped>
.ios-btn.full-width { width: 100%; }
.ios-btn-spin { animation: ios-btn-spin 0.8s linear infinite; }
.ios-btn-label { display: inline-flex; align-items: center; }
@keyframes ios-btn-spin { to { transform: rotate(360deg); } }
</style>
