<!--
  灾损评估页骨架屏。

  对齐真实页面两种模式：
  - single：核心风险卡 → 七维度雷达 + AI 推理链两栏 → 灾害类型判定卡；
  - nationwide：3 个 KPI → 结果表格（表头 + 若干行）。

  控制条/Tab 由页面真实壳层负责，骨架只替换评估结果区，避免加载时跳高。
  超过 3s 仍未返回时显示一句可操作提示，骨架撤走后提示随之卸载。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'

defineProps({
  mode: { type: String, default: 'single' }, // 'single' | 'nationwide'
})
</script>

<template>
  <div
    class="sk-damage"
    role="status"
    :aria-label="mode === 'single' ? '灾损评估加载中' : '全国测算加载中'"
  >
    <!-- ══ 单点评估骨架 ══ -->
    <template v-if="mode === 'single'">
      <section class="sk-hero">
        <SkeletonBar width="42%" height="20px" radius="var(--r-sm)" />
        <SkeletonBar width="24%" height="46px" radius="var(--r-sm)" />
        <div class="sk-hero-badges">
          <SkeletonBar width="88px" height="24px" radius="var(--r-pill)" />
          <SkeletonBar width="104px" height="24px" radius="var(--r-pill)" />
          <SkeletonBar width="76px" height="24px" radius="var(--r-pill)" />
        </div>
        <SkeletonBar width="34%" height="11px" />
      </section>

      <section class="sk-grid">
        <div class="sk-card">
          <div class="sk-card-head">
            <SkeletonBar width="46%" height="14px" />
            <SkeletonBar width="26%" height="10px" />
          </div>
          <div class="sk-radar">
            <SkeletonBar width="180px" height="180px" radius="50%" />
          </div>
          <div class="sk-dim-bars">
            <div v-for="i in 4" :key="i" class="sk-dim-row">
              <SkeletonBar width="52px" height="10px" />
              <SkeletonBar height="8px" radius="var(--r-pill)" />
              <SkeletonBar width="30px" height="10px" />
            </div>
          </div>
        </div>

        <div class="sk-card">
          <div class="sk-card-head">
            <SkeletonBar width="40%" height="14px" />
          </div>
          <div class="sk-lines">
            <div v-for="i in 4" :key="i" class="sk-line-row">
              <SkeletonBar width="18px" height="18px" radius="50%" />
              <SkeletonBar :width="(70 + ((i * 7) % 25)) + '%'" height="11px" />
            </div>
          </div>
          <div class="sk-card-divider"></div>
          <div class="sk-card-head">
            <SkeletonBar width="36%" height="14px" />
          </div>
          <div class="sk-lines">
            <div v-for="i in 3" :key="i" class="sk-line-row">
              <SkeletonBar width="14px" height="12px" radius="var(--r-xs)" />
              <SkeletonBar :width="(64 + ((i * 9) % 28)) + '%'" height="11px" />
            </div>
          </div>
        </div>
      </section>

      <section class="sk-card sk-dt">
        <div class="sk-card-head">
          <SkeletonBar width="34%" height="14px" />
          <SkeletonBar width="20%" height="10px" />
        </div>
        <div class="sk-dt-grid">
          <div v-for="i in 4" :key="i" class="sk-dt-item">
            <SkeletonBar width="60%" height="10px" />
            <SkeletonBar width="80%" height="14px" />
          </div>
        </div>
      </section>
    </template>

    <!-- ══ 全国测算骨架 ══ -->
    <template v-else>
      <section class="sk-kpis">
        <div v-for="i in 3" :key="i" class="sk-kpi">
          <SkeletonBar width="58%" height="30px" radius="var(--r-sm)" />
          <SkeletonBar width="46%" height="11px" />
        </div>
      </section>

      <section class="sk-card sk-table">
        <div class="sk-card-head">
          <SkeletonBar width="28%" height="14px" />
        </div>
        <div class="sk-table-head">
          <SkeletonBar v-for="col in 7" :key="'h' + col" width="70%" height="10px" />
        </div>
        <div v-for="row in 5" :key="'r' + row" class="sk-table-row">
          <SkeletonBar v-for="col in 7" :key="'c' + row + '-' + col" width="78%" height="11px" />
        </div>
      </section>
    </template>

    <SkeletonSlowHint message="AI 评估较慢，结果返回后会自动填充；可先查看其它信息。" />
  </div>
</template>

<style scoped>
.sk-damage {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.sk-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 24px;
  min-height: 160px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-hero-badges,
.sk-dt-grid {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
}

.sk-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
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

.sk-radar {
  display: flex;
  justify-content: center;
  padding: 6px 0;
}

.sk-dim-bars,
.sk-lines {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.sk-dim-row,
.sk-line-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sk-dim-row > :nth-child(2) {
  flex: 1;
}

.sk-card-divider {
  height: 1px;
  background: var(--c-separator);
  margin: 2px 0;
}

.sk-dt-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  justify-content: stretch;
}

.sk-dt-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sk-kpis {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

.sk-kpi {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 18px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.sk-table-head,
.sk-table-row {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 16px;
  align-items: center;
}

.sk-table-head {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--c-separator);
}

.sk-table-row {
  padding: 6px 0;
}

@media (width <= 900px) {
  .sk-grid {
    grid-template-columns: 1fr;
  }

  .sk-dt-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .sk-table-head,
  .sk-table-row {
    grid-template-columns: repeat(4, 1fr);
  }
}

@media (width <= 640px) {
  .sk-kpis {
    grid-template-columns: 1fr;
  }

  .sk-dt-grid {
    grid-template-columns: 1fr;
  }
}
</style>
