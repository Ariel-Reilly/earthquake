<script setup>
/**
 * IosBadge  ── iOS 18 pill 标签
 *
 * 用法:
 *   <IosBadge tone="red">高风险</IosBadge>
 *   <IosBadge tone="green" icon="<Check />">已处理</IosBadge>
 */
import { computed } from 'vue'

const props = defineProps({
  tone: { type: String, default: 'gray' }, // gray | blue | green | orange | red | purple | cyan | indigo
  icon: { type: [Object, Function, String], default: null },
  size: { type: String, default: 'md' },   // sm | md
})

const toneClass = computed(() => `tone-${props.tone}`)
const sizeClass = computed(() => (props.size === 'sm' ? 'ios-badge-sm' : ''))
</script>

<template>
  <span :class="['ios-badge', toneClass, sizeClass]">
    <component :is="icon" v-if="icon" :size="size === 'sm' ? 10 : 12" />
    <slot />
  </span>
</template>

<style scoped>
.ios-badge-sm { padding: 2px 7px; font-size: 11px; }
</style>
