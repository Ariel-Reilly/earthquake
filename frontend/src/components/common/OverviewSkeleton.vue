<!--
  态势总览页骨架屏。

  对齐 OverviewPage 真实布局：4 张 KPI 卡 → 左侧大地图 + 右侧预警/环境栏 → 底部三栏。
  顶部 Tab 条由页面真实壳层保留，骨架只替换加载中的结果区，避免加载时整页跳高。
  超过 3s 仍未返回时显示可操作提示，骨架撤走后提示随之卸载。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div class="sk-overview" role="status" aria-label="态势数据加载中">
    <!-- 4 KPI -->
    <section class="sk-stats">
      <div v-for="i in 4" :key="i" class="sk-stat">
        <SkeletonBar width="34px" height="34px" radius="var(--r-md)" />
        <div class="sk-stat-copy">
          <SkeletonBar width="60%" height="10px" />
          <SkeletonBar width="40%" height="20px" radius="var(--r-sm)" />
          <SkeletonBar width="52%" height="9px" />
        </div>
        <SkeletonBar class="sk-stat-spark" width="100%" height="18px" radius="var(--r-xs)" />
      </div>
    </section>

    <!-- 大地图 + 侧栏 -->
    <section class="sk-content">
      <div class="sk-map">
        <div class="sk-card-head">
          <SkeletonBar width="30%" height="15px" />
          <SkeletonBar width="22%" height="11px" />
        </div>
        <div class="sk-map-canvas">
          <SkeletonBar width="80px" height="80px" radius="50%" />
          <SkeletonBar width="60px" height="60px" radius="50%" />
          <SkeletonBar width="48px" height="48px" radius="50%" />
        </div>
      </div>
      <div class="sk-side">
        <div class="sk-card sk-warning-list">
          <div class="sk-card-head">
            <SkeletonBar width="46%" height="14px" />
            <SkeletonBar width="20%" height="10px" />
          </div>
          <div v-for="i in 4" :key="i" class="sk-warn-row">
            <SkeletonBar width="34px" height="18px" radius="var(--r-pill)" />
            <div class="sk-warn-copy">
              <SkeletonBar :width="(58 + ((i * 9) % 26)) + '%'" height="11px" />
              <SkeletonBar width="46%" height="9px" />
            </div>
          </div>
        </div>
        <div class="sk-card sk-env">
          <div class="sk-card-head">
            <SkeletonBar width="40%" height="14px" />
          </div>
          <div class="sk-env-grid">
            <SkeletonBar v-for="i in 4" :key="i" width="100%" height="30px" radius="var(--r-sm)" />
          </div>
        </div>
      </div>
    </section>

    <!-- 底部三栏 -->
    <section class="sk-bottom">
      <div v-for="i in 3" :key="i" class="sk-card sk-bottom-card">
        <div class="sk-card-head">
          <SkeletonBar width="52%" height="14px" />
          <SkeletonBar width="16%" height="10px" />
        </div>
        <template v-if="i === 1">
          <div class="sk-ring"><SkeletonBar width="112px" height="112px" radius="50%" /></div>
          <SkeletonBar width="60%" height="10px" />
        </template>
        <template v-else>
          <div v-for="j in 3" :key="j" class="sk-line-row">
            <SkeletonBar width="10px" height="10px" radius="50%" />
            <SkeletonBar :width="(56 + ((j * 13) % 30)) + '%'" height="11px" />
          </div>
        </template>
      </div>
    </section>

    <SkeletonSlowHint message="态势数据加载较慢，请稍候；数据到位后会自动填充。" />
  </div>
</template>

<style scoped>
.sk-overview {
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
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  min-height: 92px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  overflow: hidden;
}

.sk-stat-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  padding-top: 2px;
}

.sk-stat-spark {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: 12px;
  width: calc(100% - 32px) !important;
  opacity: 0.5;
}

.sk-content {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr);
  gap: 14px;
}

.sk-map,
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

.sk-map-canvas {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 26px;
  min-height: 340px;
  border-radius: var(--r-md);
  background: var(--c-surface-2);
  overflow: hidden;
}

.sk-side {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.sk-warn-row,
.sk-line-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sk-warn-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 7px;
}

.sk-env-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}

.sk-bottom {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

.sk-bottom-card {
  min-height: 168px;
}

.sk-ring {
  display: flex;
  justify-content: center;
  padding: 4px 0;
}

@media (width <= 980px) {
  .sk-content {
    grid-template-columns: 1fr;
  }

  .sk-bottom {
    grid-template-columns: 1fr;
  }
}

@media (width <= 640px) {
  .sk-stats {
    grid-template-columns: repeat(2, 1fr);
  }

  .sk-map-canvas {
    min-height: 240px;
    gap: 14px;
  }
}
</style>
