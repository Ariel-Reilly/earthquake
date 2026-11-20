<!--
  灾害点详情页「局部」骨架：只覆盖三类内容区。
    - timeline：顶部统一事件时间线占位
    - info：左列基础档案 + 摘要（AI 研判由真实页面异步块自行处理）
    - evidence：右列历史记录 / 附件 / 关联联动
  不含地图 / 打开 3D 动作区，也不替换整页；页面操作栏与各 panel-header 由真实页面保留。
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'
import SkeletonSlowHint from './SkeletonSlowHint.vue'

defineProps({
  section: { type: String, default: 'info' }, // timeline | info | evidence
})
</script>

<template>
  <div
    class="hz-skeleton"
    :class="`hz-${section}`"
    role="status"
    :aria-label="section === 'timeline' ? '事件时间线加载中' : section === 'evidence' ? '历史与附件加载中' : '灾害点信息加载中'"
    aria-busy="true"
  >
    <template v-if="section === 'timeline'">
      <div class="hz-timeline">
        <div v-for="i in 4" :key="'tl' + i" class="hz-tl-node">
          <SkeletonBar width="18px" height="18px" radius="50%" />
          <SkeletonBar width="64px" height="10px" />
        </div>
      </div>
    </template>

    <template v-else-if="section === 'info'">
      <div class="hz-fields">
        <div v-for="i in 5" :key="'f' + i" class="hz-field">
          <SkeletonBar width="88px" height="11px" />
          <SkeletonBar :width="i % 2 === 0 ? '56%' : '42%'" height="14px" />
        </div>
      </div>
      <div class="hz-summary">
        <SkeletonBar width="72px" height="72px" radius="50%" />
        <div class="hz-summary-lines">
          <SkeletonBar width="132px" height="13px" />
          <SkeletonBar width="96px" height="11px" />
          <SkeletonBar width="100%" height="8px" />
          <SkeletonBar width="64%" height="10px" />
        </div>
      </div>
      <SkeletonSlowHint message="灾害点信息加载较慢，加载完成后会自动填充。" />
    </template>

    <template v-else>
      <div class="hz-history">
        <div v-for="i in 4" :key="'h' + i" class="hz-history-row">
          <SkeletonBar width="24px" height="24px" radius="var(--r-sm)" />
          <div class="hz-history-lines">
            <SkeletonBar width="76%" height="11px" />
            <SkeletonBar width="90%" height="10px" />
          </div>
        </div>
      </div>
      <div v-for="s in 2" :key="'b' + s" class="hz-block">
        <SkeletonBar width="96px" height="12px" />
        <SkeletonBar v-for="i in 2" :key="'r' + s + '-' + i" width="100%" height="10px" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.hz-skeleton {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px 20px 18px;
}

.hz-timeline {
  display: flex;
  gap: 22px;
  padding: 12px 20px;
}

.hz-tl-node {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.hz-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 24px;
}

.hz-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--c-separator);
}

.hz-summary {
  display: flex;
  align-items: center;
  gap: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--c-separator);
}

.hz-summary-lines {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
}

.hz-history {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.hz-history-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--c-separator);
}

.hz-history-lines {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
}

.hz-block {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding-top: 14px;
  border-top: 1px solid var(--c-separator);
}

@media (width <= 640px) {
  .hz-fields {
    grid-template-columns: minmax(0, 1fr);
  }

  .hz-timeline {
    flex-wrap: wrap;
    gap: 12px;
  }
}
</style>
