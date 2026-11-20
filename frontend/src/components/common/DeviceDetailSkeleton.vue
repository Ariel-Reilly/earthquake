<!--
  设备详情页骨架屏。

  对齐真实布局：左右两列（minmax(0,1.65fr) / minmax(280px,0.85fr)，1024px 以下折单列）。
  左列：设备信息(4 字段) + 电量/信号状态摘要 + AI 健康研判卡；
  右列：数据上报曲线(轨迹卡片) + 附件 + 关联联动。
  各 section 的 panel-header 由真实壳层保留，骨架只替换结果内容区。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div
    class="dd-skeleton"
    role="status"
    aria-label="设备详情加载中"
    aria-busy="true"
  >
    <div class="dd-grid">
      <!-- 左列 -->
      <section class="dd-panel">
        <div class="dd-fields">
          <div v-for="i in 4" :key="'f' + i" class="dd-field">
            <SkeletonBar width="80px" height="11px" />
            <SkeletonBar :width="i % 2 === 0 ? '58%' : '44%'" height="14px" />
          </div>
        </div>

        <div class="dd-summary">
          <SkeletonBar width="72px" height="72px" radius="50%" />
          <div class="dd-summary-lines">
            <SkeletonBar width="72px" height="13px" />
            <SkeletonBar width="96px" height="11px" />
            <SkeletonBar width="100%" height="8px" />
            <SkeletonBar width="60%" height="10px" />
          </div>
        </div>

        <div class="dd-ai-card">
          <div class="dd-ai-head">
            <SkeletonBar width="120px" height="12px" />
            <SkeletonBar width="92px" height="20px" radius="var(--r-pill)" />
          </div>
          <div class="dd-ai-meters">
            <div v-for="i in 3" :key="'m' + i" class="dd-ai-meter">
              <SkeletonBar width="52px" height="10px" />
              <SkeletonBar width="100%" height="6px" radius="var(--r-pill)" />
              <SkeletonBar width="34px" height="10px" />
            </div>
          </div>
          <div class="dd-ai-reasons">
            <SkeletonBar v-for="i in 3" :key="'r' + i" width="92%" height="10px" />
          </div>
          <div class="dd-actions"><SkeletonBar width="84px" height="28px" radius="var(--r-sm)" /></div>
        </div>

        <div class="dd-actions"><SkeletonBar width="84px" height="30px" radius="var(--r-sm)" /></div>
      </section>

      <!-- 右列 -->
      <section class="dd-panel">
        <div class="dd-history">
          <div v-for="i in 5" :key="'h' + i" class="dd-history-row">
            <SkeletonBar width="24px" height="24px" radius="var(--r-sm)" />
            <div class="dd-history-lines">
              <SkeletonBar width="76%" height="11px" />
              <SkeletonBar width="90%" height="10px" />
            </div>
          </div>
        </div>

        <div class="dd-block">
          <SkeletonBar width="96px" height="12px" />
          <SkeletonBar v-for="i in 2" :key="'a' + i" width="100%" height="10px" />
        </div>

        <div class="dd-block">
          <SkeletonBar width="96px" height="12px" />
          <SkeletonBar v-for="i in 2" :key="'l' + i" width="100%" height="10px" />
        </div>
      </section>
    </div>

    <SkeletonSlowHint message="设备详情加载较慢，加载完成后会自动填充；可先查看其它设备。" />
  </div>
</template>

<style scoped>
.dd-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.dd-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(280px, 0.85fr);
  gap: var(--sp-5);
}

.dd-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
  padding: 18px 20px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.dd-fields {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.dd-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--c-separator);
}

.dd-summary {
  display: flex;
  align-items: center;
  gap: 18px;
}

.dd-summary-lines {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
}

.dd-ai-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid var(--c-separator);
  border-left: 4px solid var(--c-brand);
  border-radius: var(--r-lg);
  background: var(--c-surface-2);
}

.dd-ai-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.dd-ai-meters {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.dd-ai-meter {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.dd-ai-reasons {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.dd-actions {
  display: flex;
  justify-content: flex-end;
}

.dd-history {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.dd-history-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--c-separator);
}

.dd-history-lines {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
}

.dd-block {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding-top: 14px;
  border-top: 1px solid var(--c-separator);
}

/* 断点与全局 content-grid 一致：1024px 折单列 */
@media (width <= 1024px) {
  .dd-grid {
    grid-template-columns: 1fr;
  }
}

@media (width <= 640px) {
  .dd-ai-meters {
    grid-template-columns: 1fr;
  }

  .dd-summary > :first-child {
    width: 56px;
    height: 56px;
  }
}
</style>
