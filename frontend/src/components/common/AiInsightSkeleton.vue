<!--
  AI 综合研判页骨架屏。

  对齐真实布局：左侧灾害点清单 240px + 右侧报告区；
  右侧按「标题 → Tab → 评分提示 → 综合风险卡 → 三维度卡」占位。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div class="sk-ai-insight" role="status" aria-label="AI 综合研判加载中">
    <aside class="sk-side">
      <div class="sk-side-head">
        <SkeletonBar width="72px" height="14px" />
        <SkeletonBar width="96px" height="11px" />
      </div>
      <div class="sk-side-list">
        <div v-for="i in 7" :key="i" class="sk-side-row">
          <div class="sk-side-lines">
            <SkeletonBar width="68%" height="13px" />
            <SkeletonBar width="48%" height="10px" />
            <SkeletonBar width="82%" height="10px" />
          </div>
          <SkeletonBar width="10px" height="10px" />
        </div>
      </div>
    </aside>

    <section class="sk-report">
      <div class="sk-report-head">
        <div class="sk-report-title">
          <SkeletonBar width="180px" height="20px" />
          <SkeletonBar width="120px" height="11px" />
        </div>
        <SkeletonBar width="168px" height="34px" radius="var(--r-md)" />
      </div>

      <div class="sk-tabs">
        <SkeletonBar width="92px" height="32px" radius="var(--r-pill)" />
        <SkeletonBar width="92px" height="32px" radius="var(--r-pill)" />
      </div>

      <div class="sk-banner">
        <SkeletonBar width="60%" height="12px" />
      </div>

      <div class="sk-risk-card">
        <div class="sk-risk-head">
          <SkeletonBar width="160px" height="15px" />
          <SkeletonBar width="80px" height="28px" radius="var(--r-pill)" />
        </div>
        <div class="sk-risk-body">
          <SkeletonBar width="90px" height="42px" />
          <div class="sk-risk-tags">
            <SkeletonBar width="86px" height="24px" />
            <SkeletonBar width="110px" height="24px" />
          </div>
          <SkeletonBar width="118px" height="34px" radius="var(--r-md)" />
        </div>
      </div>

      <div class="sk-dims">
        <div v-for="i in 3" :key="i" class="sk-dim">
          <div class="sk-dim-head">
            <SkeletonBar width="72px" height="13px" />
            <SkeletonBar width="54px" height="10px" />
          </div>
          <SkeletonBar width="64px" height="32px" />
          <div class="sk-dim-lines">
            <SkeletonBar width="100%" height="10px" />
            <SkeletonBar width="100%" height="10px" />
            <SkeletonBar width="100%" height="10px" />
            <SkeletonBar width="100%" height="10px" />
          </div>
        </div>
      </div>

      <SkeletonSlowHint message="研判清单加载较慢，可从其它模块先行查看风险；结果返回后会自动填充。" />
    </section>
  </div>
</template>

<style scoped>
.sk-ai-insight {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  flex: 1;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-side {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--c-separator);
}

.sk-side-head {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-bottom: 1px solid var(--c-separator);
}

.sk-side-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px;
}

.sk-side-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 10px;
  border-radius: var(--r-sm);
  background: var(--c-surface-2);
}

.sk-side-lines {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}

.sk-report {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  padding: 18px;
}

.sk-report-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.sk-report-title {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sk-tabs {
  display: flex;
  gap: 8px;
}

.sk-banner {
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--c-surface-2);
}

.sk-risk-card {
  padding: 14px 16px;
  border: 1px solid var(--c-separator);
  border-left: 4px solid var(--c-brand);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-risk-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.sk-risk-body {
  display: flex;
  align-items: center;
  gap: 16px;
}

.sk-risk-tags {
  display: flex;
  gap: 8px;
  flex: 1;
}

.sk-dims {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.sk-dim {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-dim-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.sk-dim-lines {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

@media (width <= 1024px) {
  .sk-ai-insight {
    grid-template-columns: 200px minmax(0, 1fr);
  }

  .sk-dims {
    grid-template-columns: 1fr;
  }
}

@media (width <= 768px) {
  .sk-ai-insight {
    grid-template-columns: 1fr;
  }

  .sk-side {
    display: none;
  }
}
</style>
