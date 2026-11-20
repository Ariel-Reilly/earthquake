<!--
  现场巡检页骨架屏。

  对齐真实布局：5 张任务卡 + 当前任务摘要条。
  KPI 和 Tab 由页面真实壳层负责，骨架只替换任务内容区，避免加载时跳高。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div class="sk-inspection" role="status" aria-label="巡检任务加载中">
    <div class="sk-cards">
      <div v-for="i in 5" :key="i" class="sk-card">
        <div class="sk-card-head">
          <SkeletonBar width="64px" height="11px" />
          <SkeletonBar width="52px" height="20px" radius="var(--r-pill)" />
        </div>
        <SkeletonBar width="82%" height="15px" />
        <div class="sk-card-meta">
          <SkeletonBar width="42%" height="11px" />
          <SkeletonBar width="36%" height="11px" />
        </div>
        <div class="sk-card-foot">
          <SkeletonBar width="64%" height="10px" />
          <SkeletonBar width="58px" height="24px" radius="var(--r-sm)" />
        </div>
      </div>
    </div>

    <div class="sk-detail">
      <div class="sk-detail-main">
        <div class="sk-detail-title">
          <SkeletonBar width="52px" height="18px" radius="var(--r-pill)" />
          <SkeletonBar width="160px" height="14px" />
        </div>
        <SkeletonBar width="56%" height="11px" />
      </div>
      <div class="sk-detail-actions">
        <SkeletonBar width="84px" height="30px" radius="var(--r-sm)" />
        <SkeletonBar width="84px" height="30px" radius="var(--r-sm)" />
        <SkeletonBar width="84px" height="30px" radius="var(--r-sm)" />
      </div>
    </div>

    <SkeletonSlowHint message="巡检任务加载较慢，请稍候；加载完成后会自动显示任务列表。" />
  </div>
</template>

<style scoped>
.sk-inspection {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.sk-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.sk-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.sk-card-meta {
  display: flex;
  gap: 12px;
}

.sk-card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 2px;
}

.sk-detail {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 14px 16px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-detail-main {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
}

.sk-detail-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sk-detail-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

@media (width <= 720px) {
  .sk-detail {
    align-items: flex-start;
    flex-direction: column;
  }

  .sk-detail-actions {
    width: 100%;
  }
}
</style>
