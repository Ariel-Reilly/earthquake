<!--
  实时监测地图骨架屏（仅地图舞台内部）。

  RealTimeMapPage 的 page-header、图层面板、时间轴都是真实壳层，加载时保留；
  本骨架只替换地图舞台内原本的 spinner 区，避免整页跳动。
  用地图画布灰底 + 网格 + 若干要素标记点占位，视觉上贴近真实地图。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div class="sk-mapstage" role="status" aria-label="地图要素加载中">
    <div class="sk-canvas">
      <div class="sk-grid"></div>
      <div class="sk-pin" style="left: 24%; top: 34%">
        <SkeletonBar width="18px" height="18px" radius="50%" />
        <SkeletonBar width="72px" height="10px" radius="var(--r-pill)" />
      </div>
      <div class="sk-pin" style="left: 58%; top: 22%">
        <SkeletonBar width="18px" height="18px" radius="50%" />
        <SkeletonBar width="64px" height="10px" radius="var(--r-pill)" />
      </div>
      <div class="sk-pin" style="left: 44%; top: 62%">
        <SkeletonBar width="18px" height="18px" radius="50%" />
        <SkeletonBar width="80px" height="10px" radius="var(--r-pill)" />
      </div>
      <div class="sk-pin" style="left: 72%; top: 70%">
        <SkeletonBar width="18px" height="18px" radius="50%" />
        <SkeletonBar width="68px" height="10px" radius="var(--r-pill)" />
      </div>
      <div class="sk-map-tools">
        <SkeletonBar width="32px" height="32px" radius="var(--r-sm)" />
        <SkeletonBar width="32px" height="32px" radius="var(--r-sm)" />
        <SkeletonBar width="32px" height="32px" radius="var(--r-sm)" />
      </div>
    </div>
    <SkeletonSlowHint message="地图要素加载较慢，可先查看图层列表；地图就绪后会自动显示。" />
  </div>
</template>

<style scoped>
.sk-mapstage {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  background: var(--c-surface);
  z-index: 4;
}

.sk-canvas {
  position: relative;
  flex: 1;
  min-height: 300px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
  background: var(--c-surface-2);
  overflow: hidden;
}

.sk-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(to right, var(--c-separator) 1px, transparent 1px),
    linear-gradient(to bottom, var(--c-separator) 1px, transparent 1px);
  background-size: 56px 56px;
  opacity: 0.45;
}

.sk-pin {
  position: absolute;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.sk-map-tools {
  position: absolute;
  right: 12px;
  top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

@media (prefers-reduced-motion: reduce) {
  .sk-grid {
    opacity: 0.3;
  }
}
</style>
