<!--
  AI 推理结果页骨架屏。

  占位结构刻意对齐真实页面：4 个统计卡 → 置信度图表 → 筛选条 → 3 条结果行。
  用 SkeletonBar 的「灰块 + 微光扫过」统一动效，避免加载时整页闪烁。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div class="sk-ai-results" role="status" aria-label="AI 推理结果加载中">
    <section class="sk-stats">
      <div v-for="i in 4" :key="i" class="sk-stat">
        <SkeletonBar width="34%" height="30px" radius="var(--r-sm)" />
        <SkeletonBar width="58%" height="12px" />
      </div>
    </section>

    <section class="sk-chart">
      <SkeletonBar width="120px" height="14px" />
      <div class="sk-bars">
        <div v-for="i in 5" :key="i" class="sk-bar-col">
          <SkeletonBar :height="[74, 44, 92, 64, 30][i - 1] + 'px'" width="36px" radius="var(--r-xs)" />
          <SkeletonBar width="46px" height="10px" />
        </div>
      </div>
    </section>

    <section class="sk-filter">
      <SkeletonBar width="72px" height="12px" />
      <SkeletonBar width="180px" height="8px" radius="var(--r-pill)" />
      <SkeletonBar width="110px" height="30px" radius="var(--r-md)" />
    </section>

    <div class="sk-rows">
      <div v-for="i in 3" :key="i" class="sk-row">
        <SkeletonBar width="56px" height="56px" radius="var(--r-md)" />
        <div class="sk-row-main">
          <SkeletonBar width="44%" height="15px" />
          <SkeletonBar width="72%" height="11px" />
          <SkeletonBar width="56%" height="11px" />
        </div>
        <div class="sk-row-side">
          <SkeletonBar width="36px" height="36px" radius="50%" />
          <SkeletonBar width="96px" height="30px" radius="var(--r-md)" />
        </div>
      </div>
    </div>

    <SkeletonSlowHint message="推理结果返回较慢，可先查看其它模块；结果就绪后会自动填充。" />
  </div>
</template>

<style scoped>
.sk-ai-results {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.sk-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}

.sk-stat {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  min-height: 92px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-chart {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 18px;
  min-height: 190px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-bars {
  display: flex;
  align-items: flex-end;
  justify-content: space-around;
  gap: 18px;
  flex: 1;
  padding: 0 20px 4px;
}

.sk-bar-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.sk-filter {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 12px 16px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
  background: var(--c-surface);
}

.sk-rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.sk-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-row-main {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
}

.sk-row-side {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

@media (width <= 900px) {
  .sk-stats {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (width <= 640px) {
  .sk-stats {
    grid-template-columns: 1fr;
  }

  .sk-filter {
    flex-wrap: wrap;
    gap: 10px;
  }

  .sk-row-side {
    display: none;
  }
}
</style>
