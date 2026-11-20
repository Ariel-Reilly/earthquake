<!--
  骨架屏慢加载提示。

  与骨架一起挂载，超过 delay（默认 3s）仍未卸载时显示一句可操作提示，
  让用户知道系统在继续工作、可以先行判断；骨架撤掉时组件随之卸载，计时自动清除。
  文案由使用方通过 message 传入，便于按页面给更贴切的指引。
-->
<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Info } from 'lucide-vue-next'

const props = defineProps({
  /** 超过该毫秒数后显示提示 */
  delay: { type: Number, default: 3000 },
  /** 提示文案 */
  message: { type: String, default: '加载时间较长，请稍候…' },
})

const visible = ref(false)
let timer = null

onMounted(() => {
  timer = window.setTimeout(() => {
    visible.value = true
  }, props.delay)
})

onBeforeUnmount(() => {
  if (timer) window.clearTimeout(timer)
})
</script>

<template>
  <Transition name="sk-slow-hint">
    <div v-if="visible" class="sk-slow-hint" role="status" aria-live="polite">
      <Info :size="14" aria-hidden="true" />
      <span>{{ message }}</span>
    </div>
  </Transition>
</template>

<style scoped>
.sk-slow-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 4px;
  padding: 8px 12px;
  border: 1px solid var(--c-brand-soft, var(--c-separator));
  border-radius: var(--r-md);
  background: var(--c-brand-mist, var(--c-surface-2));
  color: var(--c-text-2);
  font-size: 12px;
  line-height: 1.5;
}

.sk-slow-hint :deep(svg) {
  flex-shrink: 0;
  color: var(--c-brand, #5f7a6b);
}

.sk-slow-hint-enter-active,
.sk-slow-hint-leave-active {
  transition:
    opacity 0.25s ease,
    transform 0.25s ease;
}

.sk-slow-hint-enter-from,
.sk-slow-hint-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (prefers-reduced-motion: reduce) {
  .sk-slow-hint-enter-active,
  .sk-slow-hint-leave-active {
    transition: none;
  }
}
</style>
