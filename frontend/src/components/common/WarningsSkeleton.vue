<!--
  预警管理页骨架屏。

  替换原 SkeletonList（形状与真实页不符）。对齐真实布局：
  4 张 KPI → 左侧预警列表（等级徽标 + AI 分 + 描述 + 点位/时间）+ 右侧处置面板。
  超过 3s 显示可操作提示。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div class="sk-warnings" role="status" aria-label="预警数据加载中">
    <section class="sk-stats">
      <div v-for="i in 4" :key="i" class="sk-stat">
        <SkeletonBar width="50%" height="11px" />
        <SkeletonBar width="34%" height="26px" radius="var(--r-sm)" />
        <SkeletonBar width="42%" height="9px" />
      </div>
    </section>

    <section class="sk-content">
      <div class="sk-card sk-list">
        <div class="sk-card-head">
          <SkeletonBar width="34%" height="13px" />
          <SkeletonBar width="26%" height="24px" radius="var(--r-sm)" />
        </div>
        <div v-for="i in 5" :key="i" class="sk-warn-item">
          <div class="sk-badges">
            <SkeletonBar width="40px" height="20px" radius="var(--r-pill)" />
            <SkeletonBar width="34px" height="20px" radius="var(--r-pill)" />
          </div>
          <SkeletonBar :width="(62 + ((i * 8) % 30)) + '%'" height="12px" />
          <SkeletonBar width="50%" height="10px" />
          <div class="sk-meta">
            <SkeletonBar width="30%" height="9px" />
            <SkeletonBar width="24%" height="9px" />
          </div>
        </div>
        <div class="sk-pager">
          <SkeletonBar width="88px" height="10px" />
          <SkeletonBar width="72px" height="24px" radius="var(--r-sm)" />
        </div>
      </div>

      <div class="sk-card sk-disposal">
        <div class="sk-card-head">
          <SkeletonBar width="46%" height="14px" />
        </div>
        <div class="sk-progress">
          <SkeletonBar width="100%" height="10px" radius="var(--r-pill)" />
          <SkeletonBar width="100%" height="8px" radius="var(--r-pill)" />
        </div>
        <div class="sk-info-grid">
          <SkeletonBar v-for="i in 6" :key="i" width="100%" height="34px" radius="var(--r-sm)" />
        </div>
        <div class="sk-actions">
          <SkeletonBar width="84px" height="30px" radius="var(--r-sm)" />
          <SkeletonBar width="84px" height="30px" radius="var(--r-sm)" />
        </div>
      </div>
    </section>

    <SkeletonSlowHint message="预警数据加载较慢，请稍候；数据到位后会自动填充。" />
  </div>
</template>

<style scoped>
.sk-warnings {
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

.sk-content {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
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

.sk-warn-item {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 12px 0;
  border-bottom: 1px solid var(--c-separator);
}

.sk-badges,
.sk-meta,
.sk-actions {
  display: flex;
  gap: 8px;
}

.sk-meta {
  justify-content: space-between;
}

.sk-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 2px;
}

.sk-progress {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sk-info-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}

@media (width <= 1180px) {
  .sk-content {
    grid-template-columns: 1fr;
  }
}

@media (width <= 760px) {
  .sk-stats {
    grid-template-columns: repeat(2, 1fr);
  }

  .sk-info-grid {
    grid-template-columns: 1fr;
  }
}
</style>
