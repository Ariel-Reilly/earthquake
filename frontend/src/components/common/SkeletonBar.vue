<!--
  骨架屏基础组件 - 条形加载占位符
  
  用于在内容加载时提供视觉占位，减少布局跳动（CLS）
  参考 WarningDeliveryPage 的 skeleton-bar 实现
-->
<script setup>
defineProps({
  /** 宽度（支持 CSS 值：'100%', '200px', '50%' 等） */
  width: { type: String, default: '100%' },
  /** 高度（支持 CSS 值） */
  height: { type: String, default: '14px' },
  /** 圆角大小 */
  radius: { type: String, default: 'var(--r-xs)' }
})
</script>

<template>
  <div 
    class="skeleton-bar" 
    :style="{ width, height, borderRadius: radius }"
  ></div>
</template>

<style scoped>
/**
 * 骨架屏动画效果
 * 
 * 使用渐变 + 脉冲动画模拟内容加载，视觉上比纯静态占位更友好
 */
.skeleton-bar {
  background: linear-gradient(
    90deg, 
    var(--c-brand-mist) 0%, 
    var(--c-bg) 50%, 
    var(--c-brand-mist) 100%
  );
  animation: skeleton-pulse 1.2s ease-in-out infinite;
}

@keyframes skeleton-pulse {
  0%, 100% { opacity: 0.65; }
  50% { opacity: 1; }
}
</style>
