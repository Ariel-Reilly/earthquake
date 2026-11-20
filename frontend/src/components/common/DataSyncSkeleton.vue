<!--
  数据同步页骨架屏。

  对齐 DataSyncPage：6 张统计卡 → 8 列表格（任务/数据源/目标/状态/记录数/延迟/时间/操作）。
  顶部 page-header 与筛选工具栏由页面壳层保留，骨架替换统计卡 + 表格区。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div class="sk-dsync" role="status" aria-label="同步状态加载中">
    <section class="sk-stats">
      <div v-for="i in 6" :key="i" class="sk-stat">
        <SkeletonBar width="22px" height="22px" radius="var(--r-sm)" />
        <div class="sk-stat-copy">
          <SkeletonBar width="54%" height="20px" radius="var(--r-sm)" />
          <SkeletonBar width="62%" height="9px" />
        </div>
      </div>
    </section>

    <section class="sk-table">
      <div class="sk-row sk-head">
        <SkeletonBar v-for="col in 8" :key="'h' + col" width="72%" height="10px" />
      </div>
      <div v-for="row in 6" :key="'r' + row" class="sk-row">
        <SkeletonBar :width="(58 + ((row * 7) % 30)) + '%'" height="11px" />
        <SkeletonBar width="64%" height="11px" />
        <SkeletonBar width="64%" height="11px" />
        <SkeletonBar width="52px" height="20px" radius="var(--r-pill)" />
        <SkeletonBar width="50%" height="11px" />
        <SkeletonBar width="46%" height="11px" />
        <SkeletonBar width="66%" height="10px" />
        <div class="sk-actions">
          <SkeletonBar width="24px" height="24px" radius="var(--r-sm)" />
          <SkeletonBar width="24px" height="24px" radius="var(--r-sm)" />
        </div>
      </div>
    </section>

    <SkeletonSlowHint message="同步状态加载较慢，请稍候；数据到位后会自动填充。" />
  </div>
</template>

<style scoped>
.sk-dsync {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.sk-stats {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
}

.sk-stat {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-stat-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 7px;
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
  grid-template-columns: 1.4fr 1fr 1fr 0.8fr 0.7fr 0.7fr 1fr 0.8fr;
  gap: 10px;
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

.sk-actions {
  display: flex;
  gap: 6px;
}

@media (width <= 1100px) {
  .sk-stats {
    grid-template-columns: repeat(3, 1fr);
  }

  .sk-row {
    grid-template-columns: 1.4fr 1fr 0.8fr 0.8fr;
  }

  .sk-row > :nth-child(3),
  .sk-row > :nth-child(5),
  .sk-row > :nth-child(6),
  .sk-row > :nth-child(7) {
    display: none;
  }
}

@media (width <= 640px) {
  .sk-stats {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
