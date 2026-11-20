<!--
  区域专项大屏骨架屏。

  对齐 ZoneDashboardPage 真实布局：4 张 KPI → 左侧折线趋势图 + 右侧灾害点列表 →
  Top5 风险表（7 列）→ 底部关联预警/应急两栏。
  顶部 zone-toolbar 由页面壳层保留，骨架只替换结果区。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div class="sk-zone" role="status" aria-label="区域大屏数据加载中">
    <!-- 4 KPI -->
    <section class="sk-stats">
      <div v-for="i in 4" :key="i" class="sk-stat">
        <SkeletonBar width="52%" height="11px" />
        <SkeletonBar width="36%" height="28px" radius="var(--r-sm)" />
        <SkeletonBar width="40%" height="9px" />
      </div>
    </section>

    <!-- 折线趋势 + 灾害点列表 -->
    <section class="sk-mid">
      <div class="sk-card">
        <div class="sk-card-head">
          <SkeletonBar width="42%" height="14px" />
          <SkeletonBar width="18%" height="10px" />
        </div>
        <div class="sk-chart">
          <div class="sk-chart-grid">
            <SkeletonBar v-for="i in 5" :key="'g' + i" width="100%" height="1px" />
          </div>
          <svg viewBox="0 0 560 180" preserveAspectRatio="none" class="sk-chart-line" aria-hidden="true">
            <polyline
              points="0,140 70,110 140,124 210,72 280,96 350,48 420,80 490,40 560,64"
              fill="none"
              stroke="var(--c-surface-3, var(--c-separator))"
              stroke-width="3"
            />
          </svg>
          <div class="sk-chart-axis">
            <SkeletonBar v-for="i in 6" :key="'a' + i" width="12%" height="8px" />
          </div>
        </div>
      </div>
      <div class="sk-card">
        <div class="sk-card-head">
          <SkeletonBar width="46%" height="14px" />
        </div>
        <div v-for="i in 5" :key="i" class="sk-hazard-row">
          <SkeletonBar width="28px" height="18px" radius="var(--r-pill)" />
          <div class="sk-hazard-copy">
            <SkeletonBar :width="(54 + ((i * 10) % 28)) + '%'" height="11px" />
            <SkeletonBar width="42%" height="9px" />
          </div>
          <SkeletonBar width="48px" height="20px" radius="var(--r-sm)" />
        </div>
      </div>
    </section>

    <!-- Top5 风险表 7 列 -->
    <section class="sk-card sk-table-card">
      <div class="sk-card-head">
        <SkeletonBar width="24%" height="14px" />
      </div>
      <div class="sk-table-head">
        <SkeletonBar v-for="col in 7" :key="'h' + col" width="72%" height="10px" />
      </div>
      <div v-for="row in 5" :key="'r' + row" class="sk-table-row">
        <SkeletonBar v-for="col in 7" :key="'c' + row + '-' + col" :width="(62 + ((col * 7 + row * 5) % 34)) + '%'" height="11px" />
      </div>
    </section>

    <!-- 底部两栏 -->
    <section class="sk-bottom">
      <div v-for="i in 2" :key="i" class="sk-card">
        <div class="sk-card-head">
          <SkeletonBar width="40%" height="14px" />
        </div>
        <div v-for="j in 4" :key="j" class="sk-line-row">
          <SkeletonBar width="10px" height="10px" radius="50%" />
          <SkeletonBar :width="(52 + ((j * 12) % 30)) + '%'" height="11px" />
          <SkeletonBar width="40px" height="9px" />
        </div>
      </div>
    </section>

    <SkeletonSlowHint message="专项大屏数据加载较慢，请稍候；数据到位后会自动填充。" />
  </div>
</template>

<style scoped>
.sk-zone {
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

.sk-mid {
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

.sk-chart {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 190px;
}

.sk-chart-grid {
  position: absolute;
  inset: 0 0 18px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.sk-chart-line {
  position: relative;
  flex: 1;
  width: 100%;
  height: 100%;
  margin-bottom: 18px;
}

.sk-chart-axis {
  display: flex;
  justify-content: space-between;
}

.sk-hazard-row,
.sk-line-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sk-hazard-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 7px;
}

.sk-line-row > :nth-child(2) {
  flex: 1;
}

.sk-table-head,
.sk-table-row {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 12px;
  align-items: center;
}

.sk-table-head {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--c-separator);
}

.sk-table-row {
  padding: 4px 0;
}

.sk-bottom {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

@media (width <= 1100px) {
  .sk-stats {
    grid-template-columns: repeat(2, 1fr);
  }

  .sk-table-head,
  .sk-table-row {
    grid-template-columns: repeat(4, 1fr);
  }
}

@media (width <= 720px) {
  .sk-mid,
  .sk-bottom {
    grid-template-columns: 1fr;
  }
}

@media (width <= 480px) {
  .sk-stats {
    grid-template-columns: 1fr;
  }
}
</style>
