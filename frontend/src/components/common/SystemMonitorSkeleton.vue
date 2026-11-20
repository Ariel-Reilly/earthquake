<!--
  系统监控页骨架屏。

  对齐 SystemMonitorPage 真实布局：
  6 张服务状态卡 → 4 张 KPI 卡 → 3 张趋势卡（CPU/GPU/内存）→ 实时告警列表。
  顶部 page-header（标题+刷新按钮）由页面壳层保留，骨架只替换加载中的结果区。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div class="sk-sysmon" role="status" aria-label="系统监控加载中">
    <!-- 6 服务卡 -->
    <section class="sk-services">
      <div v-for="i in 6" :key="i" class="sk-svc">
        <div class="sk-svc-head">
          <SkeletonBar width="18px" height="18px" radius="var(--r-xs)" />
          <SkeletonBar width="60%" height="12px" />
          <SkeletonBar width="10px" height="10px" radius="50%" />
        </div>
        <div class="sk-svc-meta">
          <SkeletonBar width="70%" height="9px" />
          <SkeletonBar width="50%" height="9px" />
        </div>
      </div>
    </section>

    <!-- 4 KPI -->
    <section class="sk-kpis">
      <div v-for="i in 4" :key="i" class="sk-kpi">
        <SkeletonBar width="18px" height="18px" radius="var(--r-xs)" />
        <SkeletonBar width="52%" height="22px" radius="var(--r-sm)" />
        <SkeletonBar width="46%" height="10px" />
        <SkeletonBar width="100%" height="6px" radius="var(--r-pill)" />
      </div>
    </section>

    <!-- 3 趋势卡 -->
    <section class="sk-trends">
      <div v-for="i in 3" :key="i" class="sk-trend">
        <div class="sk-card-head">
          <SkeletonBar width="42%" height="13px" />
          <SkeletonBar width="22%" height="11px" />
        </div>
        <svg viewBox="0 0 100 40" preserveAspectRatio="none" class="sk-trend-svg" aria-hidden="true">
          <polyline
            points="0,30 15,22 30,26 45,12 60,20 75,10 90,18 100,14"
            fill="none"
            stroke="var(--c-surface-3, var(--c-separator))"
            stroke-width="2"
          />
        </svg>
        <SkeletonBar width="44%" height="9px" />
      </div>
    </section>

    <!-- 实时告警 -->
    <section class="sk-alerts">
      <div class="sk-card-head">
        <SkeletonBar width="20%" height="14px" />
        <div class="sk-alert-tabs">
          <SkeletonBar width="56px" height="22px" radius="var(--r-sm)" />
          <SkeletonBar width="56px" height="22px" radius="var(--r-sm)" />
          <SkeletonBar width="56px" height="22px" radius="var(--r-sm)" />
        </div>
      </div>
      <div v-for="i in 5" :key="i" class="sk-alert-row">
        <SkeletonBar width="14px" height="14px" radius="var(--r-xs)" />
        <div class="sk-alert-copy">
          <SkeletonBar :width="(46 + ((i * 9) % 28)) + '%'" height="11px" />
          <SkeletonBar width="34%" height="9px" />
        </div>
        <SkeletonBar v-if="i % 2 === 0" width="52px" height="18px" radius="var(--r-pill)" />
      </div>
    </section>

    <SkeletonSlowHint message="监控数据加载较慢，请稍候；数据到位后会自动填充。" />
  </div>
</template>

<style scoped>
.sk-sysmon {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.sk-services {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 10px;
}

.sk-svc {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-svc-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sk-svc-head > :nth-child(2) {
  flex: 1;
}

.sk-svc-meta {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.sk-kpis {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}

.sk-kpi {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 9px;
  padding: 16px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-trends {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

.sk-trend {
  display: flex;
  flex-direction: column;
  gap: 12px;
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

.sk-trend-svg {
  width: 100%;
  height: 60px;
}

.sk-alerts {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-alert-tabs {
  display: flex;
  gap: 8px;
}

.sk-alert-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px solid var(--c-separator);
}

.sk-alert-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 7px;
}

@media (width <= 1100px) {
  .sk-services {
    grid-template-columns: repeat(3, 1fr);
  }

  .sk-kpis {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (width <= 760px) {
  .sk-services,
  .sk-trends {
    grid-template-columns: repeat(2, 1fr);
  }

  .sk-alert-tabs {
    display: none;
  }
}
</style>
