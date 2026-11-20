<!--
  骨架屏基础组件 - 条形加载占位符

  用于在内容加载时提供视觉占位，减少布局跳动（CLS）。
  统一采用「灰色底块 + 微光扫过」：底块按布局占位，高光只做装饰，
  不使用整体脉冲透明度，避免整页闪烁。
-->
<script setup>
defineProps({
  /** 宽度（支持 CSS 值：'100%', '200px', '50%' 等） */
  width: { type: String, default: '100%' },
  /** 高度（支持 CSS 值） */
  height: { type: String, default: '14px' },
  /** 圆角大小 */
  radius: { type: String, default: 'var(--r-xs)' },
})
</script>

<template>
  <div
    class="skeleton-bar"
    :style="{ width, height, borderRadius: radius }"
    aria-hidden="true"
  ></div>
</template>

<style scoped>
.skeleton-bar {
  /* 高光强度按主题切换：浅色用较亮白光，深色底块上收敛为柔和微光 */
  --sk-shine: rgb(255 255 255 / 62%);

  position: relative;
  overflow: hidden;
  background: var(--c-surface-2, #eef3f0);
}

/* 整条选择器链放进 :global，确保 scoped 编译器不会丢掉末尾的 .skeleton-bar */
:global(html[data-theme='dark'] .skeleton-bar),
:global(html.theme-dark .skeleton-bar) {
  --sk-shine: rgb(255 255 255 / 16%);
}

@media (prefers-color-scheme: dark) {
  .skeleton-bar {
    --sk-shine: rgb(255 255 255 / 16%);
  }
}

/* 微光扫过：高光只覆盖底块，不改变整个页面的透明度 */
.skeleton-bar::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    100deg,
    transparent 0%,
    rgb(255 255 255 / 0%) 35%,
    var(--sk-shine) 50%,
    rgb(255 255 255 / 0%) 65%,
    transparent 100%
  );
  transform: translateX(-120%);
  animation: skeleton-shimmer 1.35s ease-in-out infinite;
}

@keyframes skeleton-shimmer {
  to {
    transform: translateX(120%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .skeleton-bar::after {
    animation: none;
    opacity: 0;
  }
}
</style>
