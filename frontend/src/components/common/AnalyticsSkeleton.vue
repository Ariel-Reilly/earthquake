<!--
  数据统计页骨架屏，对齐 AnalyticsPage 两个 Tab：

  - mode='analysis'（AI 综合分析）：4 KPI → 两栏图表（TOP12 排行 + 分布条） →
    底部两栏（区域热力 + 风险区域）。
  - mode='inspection'（AI 巡检报告）：4 KPI → 左侧筛选 + 右侧 4 图表网格。

  顶部 analytics-tab-bar 由页面壳层保留，骨架只替换各 Tab 结果区。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'

defineProps({
  mode: { type: String, default: 'analysis' }, // 'analysis' | 'inspection'
})
</script>

<template>
  <div class="sk-analytics" role="status" aria-label="统计数据加载中">
    <!-- ══ AI 综合分析 ══ -->
    <template v-if="mode === 'analysis'">
      <section class="sk-stats">
        <div v-for="i in 4" :key="i" class="sk-stat">
          <SkeletonBar width="48%" height="11px" />
          <SkeletonBar width="36%" height="26px" radius="var(--r-sm)" />
          <SkeletonBar width="40%" height="9px" />
        </div>
      </section>

      <section class="sk-grid">
        <div class="sk-card">
          <div class="sk-card-head">
            <SkeletonBar width="46%" height="14px" />
          </div>
          <div class="sk-bars">
            <div v-for="i in 6" :key="i" class="sk-bar-row">
              <SkeletonBar width="52px" height="10px" />
              <SkeletonBar :height="(8 + i * 5) + 'px'" width="100%" radius="var(--r-xs)" />
              <SkeletonBar width="30px" height="10px" />
            </div>
          </div>
        </div>
        <div class="sk-card">
          <div class="sk-card-head">
            <SkeletonBar width="50%" height="14px" />
          </div>
          <div class="sk-cols">
            <div v-for="i in 5" :key="i" class="sk-col">
              <SkeletonBar :height="(40 + ((i * 17) % 70)) + 'px'" width="30px" radius="var(--r-xs)" />
              <SkeletonBar width="36px" height="9px" />
            </div>
          </div>
        </div>
      </section>

      <section class="sk-grid sk-bottom">
        <div class="sk-card">
          <div class="sk-card-head">
            <SkeletonBar width="40%" height="14px" />
          </div>
          <div class="sk-heat">
            <SkeletonBar v-for="i in 9" :key="i" width="100%" height="26px" radius="var(--r-xs)" />
          </div>
        </div>
        <div class="sk-card">
          <div class="sk-card-head">
            <SkeletonBar width="42%" height="14px" />
          </div>
          <div class="sk-map-mini">
            <SkeletonBar width="76px" height="76px" radius="50%" />
            <SkeletonBar width="56px" height="56px" radius="50%" />
          </div>
        </div>
      </section>
    </template>

    <!-- ══ AI 巡检报告 ══ -->
    <template v-else>
      <section class="sk-stats">
        <div v-for="i in 4" :key="i" class="sk-stat">
          <SkeletonBar width="46%" height="11px" />
          <SkeletonBar width="38%" height="26px" radius="var(--r-sm)" />
        </div>
      </section>

      <section class="sk-inspection">
        <div class="sk-card sk-filter-aside">
          <SkeletonBar width="56%" height="13px" />
          <SkeletonBar v-for="i in 4" :key="i" width="100%" height="32px" radius="var(--r-sm)" />
          <SkeletonBar width="100%" height="30px" radius="var(--r-sm)" />
        </div>
        <div class="sk-chart-grid">
          <div v-for="i in 4" :key="i" class="sk-card sk-chart-card">
            <div class="sk-card-head">
              <SkeletonBar width="52%" height="13px" />
            </div>
            <div class="sk-chart-body">
              <SkeletonBar v-for="j in 5" :key="j" :width="(40 + ((j * 11) % 50)) + '%'" :height="(8 + ((j % 3) * 5)) + 'px'" />
            </div>
          </div>
        </div>
      </section>
    </template>

    <SkeletonSlowHint :message="mode === 'inspection' ? '巡检报告加载较慢，请稍候；数据到位后会自动填充。' : '统计数据加载较慢，请稍候；数据到位后会自动填充。'" />
  </div>
</template>

<style scoped>
.sk-analytics {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.sk-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}

.sk-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.sk-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.sk-bars,
.sk-cols {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.sk-bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sk-bar-row > :nth-child(2) {
  flex: 1;
}

.sk-cols {
  flex-direction: row;
  align-items: flex-end;
  justify-content: space-around;
  min-height: 150px;
  padding-top: 10px;
}

.sk-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.sk-heat {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.sk-map-mini {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  min-height: 150px;
  border-radius: var(--r-md);
  background: var(--c-surface-2);
}

.sk-inspection {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 14px;
}

.sk-filter-aside {
  align-self: start;
}

.sk-chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.sk-chart-card {
  min-height: 200px;
}

.sk-chart-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: space-around;
  gap: 8px;
}

@media (width <= 1024px) {
  .sk-grid,
  .sk-chart-grid {
    grid-template-columns: 1fr;
  }

  .sk-inspection {
    grid-template-columns: 1fr;
  }
}

@media (width <= 640px) {
  .sk-stats {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
