<!--
  骨架屏 - 表格加载占位符
  
  用于表格类列表页面的加载状态，展示与实际表格结构相似的占位内容
  UX_Audit_Report P1-1: 减少布局跳动，提升感知加载速度
-->
<script setup>
import SkeletonBar from './SkeletonBar.vue'

const props = defineProps({
  /** 表格行数 */
  rows: { type: Number, default: 5 },
  /** 表格列数 */
  columns: { type: Number, default: 4 },
  /** 是否显示表头 */
  showHeader: { type: Boolean, default: true }
})
</script>

<template>
  <div class="skeleton-table">
    <!-- 表头 -->
    <div v-if="showHeader" class="skeleton-table-header">
      <SkeletonBar 
        v-for="col in columns" 
        :key="'header-' + col"
        :width="col === 1 ? '60%' : '80%'"
        height="12px"
      />
    </div>
    
    <!-- 表格行 -->
    <div 
      v-for="row in rows" 
      :key="'row-' + row"
      class="skeleton-table-row"
    >
      <SkeletonBar 
        v-for="col in columns" 
        :key="'cell-' + row + '-' + col"
        :width="getRandomWidth()"
        height="14px"
      />
    </div>
  </div>
</template>

<script>
// 生成随机宽度，让骨架屏更自然
function getRandomWidth() {
  const widths = ['70%', '80%', '90%', '100%']
  return widths[Math.floor(Math.random() * widths.length)]
}
</script>

<style scoped>
.skeleton-table {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}

.skeleton-table-header {
  display: grid;
  grid-template-columns: repeat(v-bind(columns), 1fr);
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--c-separator);
}

.skeleton-table-row {
  display: grid;
  grid-template-columns: repeat(v-bind(columns), 1fr);
  gap: 16px;
  padding: 8px 0;
}
</style>
