<!--
  数据源管理页骨架屏。

  对齐 DataSourcesPage：4 张统计卡 → 5 列表格（数据源/类别/状态/最近同步/操作）。
  header 与筛选条由页面壳层保留，骨架只替换加载中的统计卡 + 表格区。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div class="sk-ds" role="status" aria-label="数据源加载中">
    <section class="sk-stats">
      <div v-for="i in 4" :key="i" class="sk-stat">
        <SkeletonBar width="44%" height="24px" radius="var(--r-sm)" />
        <SkeletonBar width="56%" height="10px" />
      </div>
    </section>

    <section class="sk-table">
      <div class="sk-row sk-head">
        <SkeletonBar v-for="col in 5" :key="'h' + col" width="72%" height="11px" />
      </div>
      <div v-for="row in 6" :key="'r' + row" class="sk-row">
        <div class="sk-cell-name">
          <SkeletonBar width="18px" height="18px" radius="var(--r-xs)" />
          <SkeletonBar :width="(54 + ((row * 8) % 30)) + '%'" height="11px" />
        </div>
        <SkeletonBar width="60%" height="11px" />
        <SkeletonBar width="48px" height="20px" radius="var(--r-pill)" />
        <SkeletonBar width="66%" height="10px" />
        <div class="sk-cell-actions">
          <SkeletonBar width="40px" height="22px" radius="var(--r-sm)" />
          <SkeletonBar width="40px" height="22px" radius="var(--r-sm)" />
        </div>
      </div>
    </section>

    <SkeletonSlowHint message="数据源加载较慢，请稍候；数据到位后会自动填充。" />
  </div>
</template>

<style scoped>
.sk-ds {
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

.sk-table {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 16px 16px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-row {
  display: grid;
  grid-template-columns: 1.6fr 1fr 0.8fr 1.2fr 1fr;
  gap: 14px;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--c-separator);
}

.sk-row:last-child {
  border-bottom: none;
}

.sk-head {
  padding-bottom: 12px;
}

.sk-cell-name {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sk-cell-actions {
  display: flex;
  gap: 8px;
}

@media (width <= 900px) {
  .sk-stats {
    grid-template-columns: repeat(2, 1fr);
  }

  .sk-row {
    grid-template-columns: 1.4fr 1fr 0.8fr;
  }

  .sk-row > :nth-child(4),
  .sk-cell-actions {
    display: none;
  }
}
</style>
