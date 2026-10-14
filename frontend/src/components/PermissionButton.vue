<script setup>
/**
 * PermissionButton  ── 权限受控的 iOS 18 按钮
 *
 * 兼容旧 API: variant="primary" | "secondary"
 * 真实 iOS 18 变体: primary (filled blue) | secondary (tinted blue) | destructive | ghost | tinted
 */
import { computed } from 'vue'
import { hasPermission, getAuthRole } from '../stores/auth'

const props = defineProps({
  permission: { type: String, required: true },
  variant: { type: String, default: 'secondary' }, // primary | secondary | destructive | ghost | tinted
  icon: { type: [Object, Function, String], default: null },
  size: { type: String, default: 'md' },
  disabledReason: { type: String, default: '当前角色无此权限' },
})

const emit = defineEmits(['click'])
const role = computed(getAuthRole)
const allowed = computed(() => hasPermission(role.value, props.permission))

function handleClick(event) {
  if (!allowed.value) {
    event.preventDefault()
    emit('click', { allowed: false, reason: props.disabledReason })
    return
  }
  emit('click', { allowed: true })
}
</script>

<template>
  <button
    :class="['ios-btn', variant, { 'is-denied': !allowed }]"
    :disabled="!allowed"
    :aria-disabled="!allowed"
    :title="!allowed ? disabledReason : undefined"
    :size="size"
    @click="handleClick"
  >
    <component :is="icon" v-if="icon" :size="14" />
    <slot />
  </button>
</template>

<style scoped>
.ios-btn { transition: background .15s ease, transform .1s ease, opacity .15s ease; }
.ios-btn.is-denied {
  opacity: .42;
  cursor: not-allowed;
  pointer-events: auto;
}
.ios-btn.is-denied:hover { background: inherit; }
.ios-btn.primary.is-denied { background: var(--c-brand); }
.ios-btn.secondary.is-denied { background: rgba(111, 158, 127, .14); color: var(--c-brand); }
.ios-btn.ghost.is-denied { background: transparent; color: var(--c-brand); }
.ios-btn.destructive.is-denied { background: var(--ios-red); }
.ios-btn.tinted.is-denied { background: rgba(120, 120, 127, .16); color: var(--c-text); }
</style>
