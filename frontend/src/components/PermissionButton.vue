<script setup>
import { computed } from 'vue'
import { hasPermission, getAuthRole } from '../stores/auth'

const props = defineProps({
  permission: { type: String, required: true },
  variant: { type: String, default: 'secondary' },
  icon: { type: [Object, Function], default: null },
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
    :class="[
      variant === 'primary' ? 'primary-btn' : 'secondary-btn',
      { 'is-permission-denied': !allowed }
    ]"
    :aria-disabled="!allowed"
    :title="!allowed ? disabledReason : undefined"
    @click="handleClick"
  >
    <component :is="icon" v-if="icon" :size="16" />
    <slot />
  </button>
</template>

<style scoped>
.is-permission-denied {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
