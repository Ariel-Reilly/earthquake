<!--
  骨架屏 - 卡片列表加载占位符
  
  用于卡片式列表页面的加载状态（如设备列表、任务列表等）
  UX_Audit_Report P1-1: 展示与实际内容结构相似的占位
-->
<script setup>
import { computed } from 'vue'
import SkeletonBar from './SkeletonBar.vue'

const props = defineProps({
  /** 卡片数量 */
  count: { type: Number, default: 3 },
  /** 布局方式：'vertical' | 'grid' */
  layout: { type: String, default: 'vertical' },
  /** grid 模式下的列数 */
  gridColumns: { type: Number, default: 3 }
})

// CSS 变量名须小写（stylelint value-keyword-case），用别名桥接驼峰 prop
const gridcolumns = computed(() => props.gridColumns)
</script>

<template>
  <div
    class="skeleton-list"
    :class="{ 'skeleton-list-grid': layout === 'grid' }"
    role="status"
    aria-label="列表加载中"
    aria-busy="true"
  >
    <div 
      v-for="i in count" 
      :key="'card-' + i"
      class="skeleton-card"
    >
      <!-- 卡片标题 -->
      <SkeletonBar width="40%" height="10px" />
      
      <!-- 卡片主要内容 -->
      <SkeletonBar width="80%" height="18px" style="margin-top: 12px" />
      
      <!-- 卡片详情 -->
      <div class="skeleton-card-details">
        <SkeletonBar width="60%" height="12px" />
        <SkeletonBar width="50%" height="12px" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.skeleton-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
}

.skeleton-list-grid {
  display: grid;
  grid-template-columns: repeat(v-bind(gridcolumns), 1fr);
  gap: 16px;
}

.skeleton-card {
  padding: 20px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.skeleton-card-details {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 响应式调整 */
@media (width <= 1200px) {
  .skeleton-list-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (width <= 768px) {
  .skeleton-list-grid {
    grid-template-columns: 1fr;
  }
}
</style>
