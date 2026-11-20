<!--
  应急处置页骨架屏。

  对齐 EmergencyPage 真实布局：4 张 KPI → AI 应急研判卡 →
  左侧处置工单列表 + 右侧应急步骤（4 步 + 进度环 + 操作按钮）。
  超过 3s 显示可操作提示。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div class="sk-emergency" role="status" aria-label="应急数据加载中">
    <section class="sk-stats">
      <div v-for="i in 4" :key="i" class="sk-stat">
        <SkeletonBar width="44px" height="44px" radius="var(--r-md)" />
        <div class="sk-stat-copy">
          <SkeletonBar width="70%" height="11px" />
          <SkeletonBar width="44%" height="22px" radius="var(--r-sm)" />
        </div>
      </div>
    </section>

    <section class="sk-card sk-ai">
      <div class="sk-card-head">
        <SkeletonBar width="30%" height="14px" />
        <SkeletonBar width="12%" height="20px" radius="var(--r-pill)" />
      </div>
      <div v-for="i in 3" :key="i" class="sk-ai-row">
        <SkeletonBar width="36px" height="18px" radius="var(--r-pill)" />
        <SkeletonBar :width="(56 + ((i * 11) % 30)) + '%'" height="11px" />
        <SkeletonBar width="40%" height="9px" />
      </div>
    </section>

    <section class="sk-content">
      <div class="sk-card sk-orders">
        <div class="sk-card-head">
          <SkeletonBar width="42%" height="14px" />
        </div>
        <div v-for="i in 4" :key="i" class="sk-order-row">
          <SkeletonBar width="30px" height="18px" radius="var(--r-pill)" />
          <div class="sk-order-copy">
            <SkeletonBar :width="(58 + ((i * 9) % 28)) + '%'" height="11px" />
            <SkeletonBar width="46%" height="9px" />
          </div>
          <SkeletonBar width="52px" height="22px" radius="var(--r-sm)" />
        </div>
      </div>

      <div class="sk-card sk-steps">
        <div class="sk-card-head">
          <SkeletonBar width="38%" height="14px" />
        </div>
        <div v-for="i in 4" :key="i" class="sk-step-row">
          <SkeletonBar width="20px" height="20px" radius="50%" />
          <SkeletonBar :width="(60 + ((i * 8) % 26)) + '%'" height="11px" />
        </div>
        <div class="sk-summary">
          <div class="sk-ring"><SkeletonBar width="104px" height="104px" radius="50%" /></div>
          <div class="sk-summary-copy">
            <SkeletonBar width="80%" height="11px" />
            <SkeletonBar width="60%" height="10px" />
            <SkeletonBar width="72%" height="10px" />
          </div>
        </div>
        <div class="sk-actions">
          <SkeletonBar width="96px" height="30px" radius="var(--r-sm)" />
          <SkeletonBar width="96px" height="30px" radius="var(--r-sm)" />
        </div>
      </div>
    </section>

    <SkeletonSlowHint message="应急数据加载较慢，请稍候；数据到位后会自动填充。" />
  </div>
</template>

<style scoped>
.sk-emergency {
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
  align-items: center;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-stat-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
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

.sk-ai-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--c-separator);
}

.sk-content {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.sk-order-row,
.sk-step-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sk-order-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 7px;
}

.sk-summary {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 12px 0;
}

.sk-ring {
  display: flex;
  flex-shrink: 0;
}

.sk-summary-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 9px;
}

.sk-actions {
  display: flex;
  gap: 10px;
}

@media (width <= 1024px) {
  .sk-content {
    grid-template-columns: 1fr;
  }

  .sk-stats {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
