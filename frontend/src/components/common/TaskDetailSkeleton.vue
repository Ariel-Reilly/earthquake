<!--
  巡检任务详情页骨架屏。

  对齐真实布局：左右两列（1.65fr / 0.85fr，右列最小 310px）。
  左列：基本信息(6 字段+进度摘要+保存) / 运行时指标(4 卡+摘要) / 实时日志(日志行+工具栏)；
  右列：执行控制(3 卡) / 过程记录(轨迹+附件+关联)。
  页头(各 section 的 panel-header)由真实壳层保留，骨架只替换结果内容区。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'
</script>

<template>
  <div
    class="td-skeleton"
    role="status"
    aria-label="任务详情加载中"
    aria-busy="true"
  >
    <div class="td-grid">
      <!-- 左列 -->
      <div class="td-main">
        <!-- 1) 基本信息 -->
        <section class="td-panel">
          <div class="td-fields">
            <div v-for="i in 6" :key="'f' + i" class="td-field">
              <SkeletonBar width="72px" height="11px" />
              <SkeletonBar :width="i % 2 === 0 ? '62%' : '48%'" height="14px" />
            </div>
          </div>
          <div class="td-summary">
            <SkeletonBar width="72px" height="72px" radius="50%" />
            <div class="td-summary-lines">
              <SkeletonBar width="140px" height="13px" />
              <SkeletonBar width="96px" height="11px" />
              <SkeletonBar width="100%" height="8px" />
              <SkeletonBar width="68%" height="10px" />
            </div>
          </div>
          <div class="td-actions"><SkeletonBar width="84px" height="30px" radius="var(--r-sm)" /></div>
        </section>

        <!-- 2) 运行时指标 -->
        <section class="td-panel">
          <div class="td-runtime">
            <div v-for="i in 4" :key="'r' + i" class="td-runtime-card">
              <SkeletonBar width="64px" height="11px" />
              <SkeletonBar width="52px" height="26px" />
              <SkeletonBar width="100%" height="6px" radius="var(--r-pill)" />
              <SkeletonBar width="72px" height="9px" />
            </div>
          </div>
          <div class="td-runtime-summary">
            <SkeletonBar width="120px" height="13px" />
            <SkeletonBar width="60px" height="20px" />
            <div class="td-runtime-times">
              <SkeletonBar width="45%" height="11px" />
              <SkeletonBar width="45%" height="11px" />
            </div>
          </div>
        </section>

        <!-- 3) 实时日志 -->
        <section class="td-panel">
          <div class="td-logs">
            <div v-for="i in 7" :key="'l' + i" class="td-log-line">
              <SkeletonBar width="76px" height="10px" />
              <SkeletonBar width="42px" height="10px" />
              <SkeletonBar :width="(70 + (i % 4) * 7) + '%'" height="10px" />
            </div>
          </div>
          <div class="td-actions td-log-actions">
            <SkeletonBar v-for="i in 3" :key="'lb' + i" width="88px" height="28px" radius="var(--r-sm)" />
          </div>
        </section>
      </div>

      <!-- 右列 -->
      <div class="td-side">
        <section class="td-panel">
          <div class="td-controls">
            <div v-for="i in 3" :key="'c' + i" class="td-control">
              <div class="td-control-info">
                <SkeletonBar width="88px" height="12px" />
                <SkeletonBar width="92%" height="10px" />
              </div>
              <SkeletonBar width="72px" height="28px" radius="var(--r-sm)" />
            </div>
          </div>
        </section>

        <section class="td-panel">
          <div class="td-history">
            <div v-for="i in 4" :key="'h' + i" class="td-history-row">
              <SkeletonBar width="24px" height="24px" radius="var(--r-sm)" />
              <div class="td-history-lines">
                <SkeletonBar width="78%" height="11px" />
                <SkeletonBar width="92%" height="10px" />
              </div>
            </div>
          </div>
          <div class="td-attachment">
            <SkeletonBar width="96px" height="12px" />
            <SkeletonBar v-for="i in 2" :key="'a' + i" width="100%" height="10px" />
          </div>
        </section>
      </div>
    </div>

    <SkeletonSlowHint message="任务详情加载较慢，加载完成后会自动填充；可先查看其它任务。" />
  </div>
</template>

<style scoped>
.td-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.td-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(310px, 0.85fr);
  gap: 15px;
}

.td-main,
.td-side {
  display: grid;
  gap: 15px;
  min-width: 0;
  align-content: start;
}

.td-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px 20px 18px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.td-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 24px;
}

.td-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.td-summary {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 12px 0;
  border-top: 1px solid var(--c-separator);
}

.td-summary-lines {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
}

.td-actions {
  display: flex;
  justify-content: flex-end;
}

.td-runtime {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.td-runtime-card {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 12px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
}

.td-runtime-summary {
  display: flex;
  align-items: center;
  gap: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--c-separator);
}

.td-runtime-times {
  display: flex;
  flex: 1;
  justify-content: flex-end;
  gap: 12px;
}

.td-logs {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 260px;
  padding: 12px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
}

.td-log-line {
  display: flex;
  align-items: center;
  gap: 12px;
}

.td-log-actions {
  justify-content: flex-start;
  gap: 12px;
}

.td-controls {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.td-control {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
}

.td-control-info {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 7px;
}

.td-history {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.td-history-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.td-history-lines {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
}

.td-attachment {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding-top: 12px;
  border-top: 1px solid var(--c-separator);
}

/* 断点与真实页面一致：980px 折单列 + 运行时 4→2；640px 运行时→1 列 */
@media (width <= 980px) {
  .td-grid {
    grid-template-columns: 1fr;
  }

  .td-runtime {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .td-logs {
    height: 220px;
  }
}

@media (width <= 640px) {
  .td-fields {
    grid-template-columns: minmax(0, 1fr);
  }

  .td-runtime {
    grid-template-columns: minmax(0, 1fr);
  }

  .td-runtime-summary {
    flex-wrap: wrap;
  }

  .td-logs {
    height: 200px;
  }

  .td-log-line > :last-child {
    display: none;
  }
}
</style>
